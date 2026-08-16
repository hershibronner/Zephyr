/**
 * Natural language -> a structured flight query.
 *
 * "I wanna travel Sun Aug 16 only after 4PM, budget $400, no red-eyes" has to become something a
 * search API can answer. Claude does that job here, constrained by a JSON schema so the result is
 * always the same shape — there is no prose to regex out of, and no retry-until-it-parses loop.
 *
 * The output is deliberately richer than an airline search form:
 *
 *   - hard filters   things the traveller said were non-negotiable (budget, "after 4PM", nonstop)
 *   - priorities     what they seem to care about, as weights that drive ranking
 *   - interpretation a plain sentence we show back, so a misread is visible instead of silent
 *
 * With no ANTHROPIC_API_KEY set, a rule-based parser handles the common shapes so the app still
 * runs end to end. It is a fallback, not a peer: it will not understand "somewhere warm in
 * February for under a grand".
 */

import { clockToMinutes, addDays } from './util.mjs';
import { resolveCode } from './airports.mjs';

const API_URL = 'https://api.anthropic.com/v1/messages';
const MODEL = 'claude-opus-5';

/**
 * The schema is the contract. Structured outputs guarantee the response validates against it, so
 * everything downstream can read these fields without defensive parsing.
 *
 * Numeric bounds (minimum/maximum) aren't supported by structured outputs, so ranges are clamped
 * in normalizeQuery() below rather than declared here.
 */
const nullable = (schema) => ({ anyOf: [schema, { type: 'null' }] });

const LOCATION = {
  type: 'object',
  additionalProperties: false,
  required: ['text', 'iata', 'confident'],
  properties: {
    text: { type: 'string', description: 'The place as the traveller referred to it.' },
    iata: nullable({
      type: 'string',
      description:
        'IATA airport or metro code. Prefer the metro code (NYC, LON, PAR, TYO, WAS, CHI) when the traveller named a city rather than a specific airport — it searches every airport there, which is often where the cheap fare is.',
    }),
    confident: {
      type: 'boolean',
      description: 'False when the place is ambiguous or you had to guess (e.g. "Springfield").',
    },
  },
};

const WINDOW = {
  type: 'object',
  additionalProperties: false,
  required: ['date', 'earliestDate', 'latestDate', 'departAfter', 'departBefore', 'arriveBefore'],
  properties: {
    date: nullable({ type: 'string', description: 'Exact date as YYYY-MM-DD when one was given.' }),
    earliestDate: nullable({
      type: 'string',
      description: 'YYYY-MM-DD. Set with latestDate when the traveller is flexible ("early March").',
    }),
    latestDate: nullable({ type: 'string', description: 'YYYY-MM-DD, inclusive end of a flexible window.' }),
    departAfter: nullable({ type: 'string', description: 'Local 24h time "HH:MM". "after 4PM" -> "16:00".' }),
    departBefore: nullable({ type: 'string', description: 'Local 24h time "HH:MM".' }),
    arriveBefore: nullable({ type: 'string', description: 'Local 24h time "HH:MM" at the destination.' }),
  },
};

export const QUERY_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['origin', 'destination', 'tripType', 'outbound', 'inbound', 'filters', 'priorities', 'interpretation', 'clarifications'],
  properties: {
    origin: LOCATION,
    destination: LOCATION,
    tripType: { type: 'string', enum: ['one_way', 'round_trip'] },
    outbound: WINDOW,
    inbound: nullable(WINDOW),
    filters: {
      type: 'object',
      additionalProperties: false,
      required: [
        'maxTotalPrice', 'currency', 'adults', 'children', 'infants', 'cabin',
        'maxStops', 'maxDurationMinutes', 'includeAirlines', 'excludeAirlines',
        'avoidRedEye', 'minLayoverMinutes', 'maxLayoverMinutes',
      ],
      properties: {
        maxTotalPrice: nullable({
          type: 'number',
          description: 'Budget for the WHOLE trip, all travellers. Only set when a limit was stated.',
        }),
        currency: { type: 'string', description: 'ISO 4217 code inferred from the budget, e.g. USD, EUR, GBP.' },
        adults: { type: 'integer' },
        children: { type: 'integer', description: 'Aged 2-11.' },
        infants: { type: 'integer', description: 'Under 2, on a lap.' },
        cabin: { type: 'string', enum: ['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST'] },
        maxStops: nullable({ type: 'integer', description: '0 for nonstop only, 1 for at most one stop.' }),
        maxDurationMinutes: nullable({ type: 'integer', description: 'Per leg, door to door.' }),
        includeAirlines: { type: 'array', items: { type: 'string' }, description: 'Two-letter IATA carrier codes.' },
        excludeAirlines: { type: 'array', items: { type: 'string' } },
        avoidRedEye: { type: 'boolean', description: 'True only if they said so or implied it strongly.' },
        minLayoverMinutes: nullable({ type: 'integer' }),
        maxLayoverMinutes: nullable({ type: 'integer' }),
      },
    },
    priorities: {
      type: 'object',
      additionalProperties: false,
      required: ['price', 'duration', 'stops', 'timing', 'comfort'],
      description:
        'Weights from 0 to 1 for what this traveller actually cares about. They are relative, not a probability distribution. Someone who says "cheapest possible" is price 1.0 / duration 0.2; someone flying to a morning meeting is timing 1.0 / price 0.3.',
      properties: {
        price: { type: 'number' },
        duration: { type: 'number' },
        stops: { type: 'number' },
        timing: { type: 'number', description: 'How much the stated departure/arrival times matter.' },
        comfort: { type: 'number', description: 'Layover quality, cabin, avoiding overnight travel.' },
      },
    },
    interpretation: {
      type: 'string',
      description: 'One sentence, addressed to the traveller, restating what you understood. This is shown in the UI so a misreading is caught immediately.',
    },
    clarifications: {
      type: 'array',
      items: { type: 'string' },
      description: 'Questions worth asking — only where a wrong guess would change the results materially. Usually empty.',
    },
  },
};

const SYSTEM_PROMPT = `You turn a traveller's own words into a structured flight search.

How to read a request:

- Dates. Resolve everything relative to the date given in the user turn. A bare weekday or "Aug 16" with no year means the next such date, not one in the past. "Next weekend" is the coming Sat-Sun. When the traveller is genuinely flexible ("sometime in March", "a long weekend in spring"), set earliestDate/latestDate instead of a single date.
- Times. "after 4PM" is departAfter 16:00 on the outbound. "land before dinner" is arriveBefore, around 18:00. Only set a time constraint the traveller actually expressed.
- Budget. maxTotalPrice covers the entire trip for everyone travelling, because that is how people quote budgets. "under $400 each" with two travellers is 800.
- Places. Prefer a metro code over a single airport when a city was named. Set confident false when a place is genuinely ambiguous rather than guessing silently.
- Round trip vs one way. Only set round_trip when a return is actually implied ("for a week", "back on the 20th"). A bare "I want to go to Lisbon on the 3rd" is one_way.

Hard filters vs priorities is the important distinction. A filter removes options; a priority ranks them. "I need to be there by 9am" is a filter. "I'd rather not have a long layover" is a priority. Putting a soft wish in filters can empty the results, so when in doubt, express it as a priority.

Set the priority weights from what the traveller emphasised, not from a default. If they led with price, price dominates. If they are catching a connection or a meeting, timing dominates. If they said nothing about what matters, use a balanced spread (price 0.7, duration 0.5, stops 0.4, timing 0.3, comfort 0.3).

Write interpretation as one plain sentence to the traveller ("Searching one-way New York to Lisbon on Sun 16 Aug, departing after 16:00, under $400."). It is displayed above the results, so it is how a misreading gets caught.

Leave clarifications empty unless a wrong guess would materially change the results.`;

/** The neutral query we fall back to, and the shape every parser must return. */
function emptyQuery() {
  return {
    origin: { text: '', iata: null, confident: false },
    destination: { text: '', iata: null, confident: false },
    tripType: 'one_way',
    outbound: { date: null, earliestDate: null, latestDate: null, departAfter: null, departBefore: null, arriveBefore: null },
    inbound: null,
    filters: {
      maxTotalPrice: null, currency: 'USD', adults: 1, children: 0, infants: 0,
      cabin: 'ECONOMY', maxStops: null, maxDurationMinutes: null,
      includeAirlines: [], excludeAirlines: [], avoidRedEye: false,
      minLayoverMinutes: null, maxLayoverMinutes: null,
    },
    priorities: { price: 0.7, duration: 0.5, stops: 0.4, timing: 0.3, comfort: 0.3 },
    interpretation: '',
    clarifications: [],
  };
}

/**
 * Ask Claude. Returns null on any failure so the caller can fall back rather than fail the search
 * — a degraded result beats an error page when someone is trying to book a flight.
 */
async function parseWithClaude(text, today, apiKey) {
  const body = {
    model: MODEL,
    max_tokens: 4000,
    // Effort is the cost/latency lever here. Extracting a search query is not a reasoning-heavy
    // task, and this call sits in front of a search box.
    output_config: {
      effort: 'low',
      format: { type: 'json_schema', schema: QUERY_SCHEMA },
    },
    // The system prompt is byte-stable, so it caches; the date and the query — the parts that
    // change every request — live in the user turn, after the cache breakpoint.
    system: [{ type: 'text', text: SYSTEM_PROMPT, cache_control: { type: 'ephemeral' } }],
    messages: [{ role: 'user', content: `Today is ${today}.\n\nTraveller's request:\n${text}` }],
  };

  // Server-side fallback: if a classifier ever declines the request, Anthropic re-runs it on
  // another model in the same round trip instead of handing us a refusal.
  const withFallback = { ...body, fallbacks: 'default' };

  let response = await postMessages(withFallback, apiKey, 'server-side-fallback-2026-07-01');
  if (response.status === 400) {
    // Older API surface without the fallback beta — the search itself is unaffected.
    response = await postMessages(body, apiKey, null);
  }

  if (!response.ok) {
    console.error(`  parse: Claude returned ${response.status} — ${(await response.text()).slice(0, 300)}`);
    return null;
  }

  const payload = await response.json();

  if (payload.stop_reason === 'refusal') {
    console.error('  parse: request was declined by safety classifiers');
    return null;
  }
  if (payload.stop_reason === 'max_tokens') {
    console.error('  parse: response was truncated');
    return null;
  }

  const block = payload.content?.find((entry) => entry.type === 'text');
  if (!block) return null;

  try {
    return JSON.parse(block.text);
  } catch (error) {
    console.error(`  parse: model output was not JSON — ${error.message}`);
    return null;
  }
}

function postMessages(body, apiKey, beta) {
  const headers = {
    'content-type': 'application/json',
    'x-api-key': apiKey,
    'anthropic-version': '2023-06-01',
  };
  if (beta) headers['anthropic-beta'] = beta;
  return fetch(API_URL, { method: 'POST', headers, body: JSON.stringify(body) });
}

const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
const MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august', 'september', 'october', 'november', 'december'];

/** Resolve "aug 16" / "sun" / "tomorrow" against today, always landing on today or later. */
function resolveDate(text, today) {
  const lower = text.toLowerCase();

  const iso = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(lower);
  if (iso) return iso[0];

  if (/\btomorrow\b/.test(lower)) return addDays(today, 1);
  if (/\btoday\b|\btonight\b/.test(lower)) return today;

  const monthDay = new RegExp(`\\b(${MONTHS.map((m) => `${m.slice(0, 3)}[a-z]*`).join('|')})\\.?\\s+(\\d{1,2})\\b`).exec(lower);
  if (monthDay) {
    const month = MONTHS.findIndex((name) => name.startsWith(monthDay[1].slice(0, 3)));
    const day = Number(monthDay[2]);
    const year = Number(today.slice(0, 4));
    const candidate = `${year}-${String(month + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
    return candidate >= today ? candidate : `${year + 1}${candidate.slice(4)}`;
  }

  const weekday = new RegExp(`\\b(${WEEKDAYS.map((d) => `${d.slice(0, 3)}[a-z]*`).join('|')})\\b`).exec(lower);
  if (weekday) {
    const target = WEEKDAYS.findIndex((name) => name.startsWith(weekday[1].slice(0, 3)));
    const current = new Date(`${today}T00:00:00Z`).getUTCDay();
    const delta = (target - current + 7) % 7 || 7;
    return addDays(today, delta);
  }

  return null;
}

/**
 * Words that can follow a place name and are definitely not part of it. Without this, "Denver
 * nonstop in business class" parses as a city called "denver nonstop".
 */
const PLACE_STOP_WORDS = new Set([
  'on', 'for', 'under', 'over', 'after', 'before', 'by', 'next', 'this', 'tomorrow', 'today',
  'tonight', 'with', 'in', 'leaving', 'departing', 'returning', 'nonstop', 'non', 'stop', 'direct',
  'business', 'economy', 'premium', 'first', 'class', 'only', 'cheapest', 'cheap', 'fastest',
  'quickest', 'and', 'but', 'round', 'return', 'no', 'my', 'budget', 'sometime', 'anytime',
  'please', 'ideally', 'preferably', 'week', 'weekend', 'the', 'a', 'i', 'we',
]);

/** Trim trailing filler off a captured place, then title-case it for display. */
function cleanPlace(raw) {
  const words = raw.trim().split(/\s+/).filter(Boolean);
  while (words.length > 1 && PLACE_STOP_WORDS.has(words[words.length - 1])) words.pop();
  return words
    .map((word) => (word.length <= 3 && word === word.toUpperCase() ? word : word[0].toUpperCase() + word.slice(1)))
    .join(' ');
}

/** Anything after this word ends the place name. */
const PLACE_BOUNDARY = `(?=[,.;]|\\s+(?:${[...PLACE_STOP_WORDS].join('|')})\\b|\\s+\\d|$)`;

/**
 * Rule-based fallback. Handles the shapes people actually type most often; anything subtler is
 * why the Claude path exists.
 */
export function parseWithRules(text, today) {
  const query = emptyQuery();
  const lower = text.toLowerCase();

  // "from X to Y" first — the explicit form wins when both could match. Then a bare "X to Y",
  // which is how most people actually write it.
  const route =
    new RegExp(`\\bfrom\\s+([a-z][a-z .'-]*?)\\s+to\\s+([a-z][a-z .'-]*?)${PLACE_BOUNDARY}`, 'i').exec(lower) ??
    new RegExp(`\\b([a-z][a-z .'-]*?)\\s+to\\s+([a-z][a-z .'-]*?)${PLACE_BOUNDARY}`, 'i').exec(lower) ??
    /\b([a-z]{3})\s*(?:->|—|-)\s*([a-z]{3})\b/i.exec(lower);

  if (route) {
    query.origin.text = cleanPlace(route[1]);
    query.destination.text = cleanPlace(route[2]);
    query.origin.iata = resolveCode(query.origin.text);
    query.destination.iata = resolveCode(query.destination.text);
    query.origin.confident = query.origin.iata != null;
    query.destination.confident = query.destination.iata != null;
  }

  query.outbound.date = resolveDate(text, today);

  const after = /\bafter\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)/i.exec(text);
  if (after) {
    const minutes = clockToMinutes(after[1]);
    if (minutes != null) {
      query.outbound.departAfter = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
      query.priorities.timing = 0.8;
    }
  }

  // "before 6pm" is ambiguous on its own, and the two readings give completely different flights.
  // A nearby arrival verb settles it: "land by 6" is a deadline at the far end, "leave before 6"
  // is one at the near end.
  const before = /\b(land|arrive|get in|be there|get there|touch down)?\w*\s*(?:before|by)\s+(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)/i.exec(text);
  if (before) {
    const minutes = clockToMinutes(before[2]);
    if (minutes != null) {
      const clock = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
      const isArrival = Boolean(before[1]) || /\b(land|arriv|get in|be there|touch down)/i.test(text.slice(0, before.index));
      if (isArrival) query.outbound.arriveBefore = clock;
      else query.outbound.departBefore = clock;
      query.priorities.timing = 0.8;
    }
  }

  const budget = /(?:under|below|less than|max|budget(?:\s+is|\s+of)?|up to)?\s*([$£€])\s*(\d[\d,]*)/i.exec(text);
  if (budget) {
    query.filters.maxTotalPrice = Number(budget[2].replace(/,/g, ''));
    query.filters.currency = { $: 'USD', '£': 'GBP', '€': 'EUR' }[budget[1]] ?? 'USD';
    query.priorities.price = 1;
  }

  if (/\b(non-?stop|direct)\b/.test(lower)) {
    query.filters.maxStops = 0;
    query.priorities.stops = 1;
  } else if (/\bat most one stop|max one stop|one stop\b/.test(lower)) {
    query.filters.maxStops = 1;
  }

  if (/\bno red[- ]?eye|avoid red[- ]?eye|not overnight\b/.test(lower)) query.filters.avoidRedEye = true;
  if (/\bbusiness class\b/.test(lower)) query.filters.cabin = 'BUSINESS';
  else if (/\bfirst class\b/.test(lower)) query.filters.cabin = 'FIRST';
  else if (/\bpremium economy\b/.test(lower)) query.filters.cabin = 'PREMIUM_ECONOMY';

  const adults = /(\d+)\s+(?:adults?|people|passengers?|of us|travell?ers?)/i.exec(text);
  if (adults) query.filters.adults = Number(adults[1]);
  const children = /(\d+)\s+(?:child(?:ren)?|kids?)/i.exec(text);
  if (children) query.filters.children = Number(children[1]);

  if (/\bcheapest|cheap\b/.test(lower)) query.priorities.price = 1;
  if (/\bfastest|quickest|shortest\b/.test(lower)) query.priorities.duration = 1;

  const roundTrip = /\bround[- ]?trip|return(?:ing)?\b|\bback on\b|\bfor (?:a|\d+) (?:week|night|day)/i.test(text);
  if (roundTrip) query.tripType = 'round_trip';

  return query;
}

/** Clamp and tidy whatever the parser produced, so downstream code can trust the ranges. */
export function normalizeQuery(raw) {
  const base = emptyQuery();
  const query = {
    ...base,
    ...raw,
    origin: { ...base.origin, ...raw?.origin },
    destination: { ...base.destination, ...raw?.destination },
    outbound: { ...base.outbound, ...raw?.outbound },
    inbound: raw?.inbound ? { ...base.outbound, ...raw.inbound } : null,
    filters: { ...base.filters, ...raw?.filters },
    priorities: { ...base.priorities, ...raw?.priorities },
    clarifications: Array.isArray(raw?.clarifications) ? raw.clarifications : [],
  };

  const filters = query.filters;
  filters.adults = Math.min(9, Math.max(1, Math.round(filters.adults) || 1));
  filters.children = Math.min(9, Math.max(0, Math.round(filters.children) || 0));
  filters.infants = Math.min(filters.adults, Math.max(0, Math.round(filters.infants) || 0));
  filters.currency = (filters.currency || 'USD').toUpperCase().slice(0, 3);
  filters.includeAirlines = (filters.includeAirlines ?? []).map((code) => String(code).toUpperCase());
  filters.excludeAirlines = (filters.excludeAirlines ?? []).map((code) => String(code).toUpperCase());
  if (filters.maxTotalPrice != null && !(filters.maxTotalPrice > 0)) filters.maxTotalPrice = null;
  if (filters.maxStops != null) filters.maxStops = Math.max(0, Math.round(filters.maxStops));

  for (const key of Object.keys(query.priorities)) {
    const value = Number(query.priorities[key]);
    query.priorities[key] = Number.isFinite(value) ? Math.min(1, Math.max(0, value)) : 0;
  }
  // An all-zero weight vector would make every option score identically; fall back to balanced.
  if (Object.values(query.priorities).every((weight) => weight === 0)) {
    query.priorities = { ...base.priorities };
  }

  // Resolve any code the model left as free text, and vice versa, so the UI always has both.
  for (const end of ['origin', 'destination']) {
    const place = query[end];
    if (!place.iata) {
      const resolved = resolveCode(place.text);
      if (resolved) {
        place.iata = resolved;
        place.confident = true;
      }
    } else {
      place.iata = String(place.iata).toUpperCase().slice(0, 3);
    }
  }

  if (query.tripType !== 'round_trip') query.inbound = null;

  return query;
}

/**
 * The entry point. `today` is passed in rather than read from the clock so the whole pipeline is
 * testable and so a request from a different time zone can say what "today" means to them.
 */
export async function parseQuery(text, { today, apiKey } = {}) {
  const day = today ?? new Date().toISOString().slice(0, 10);

  if (apiKey) {
    try {
      const parsed = await parseWithClaude(text, day, apiKey);
      if (parsed) return { query: normalizeQuery(parsed), parser: 'claude' };
    } catch (error) {
      console.error(`  parse: Claude call failed — ${error.message}`);
    }
  }

  const query = normalizeQuery(parseWithRules(text, day));
  if (!query.interpretation) query.interpretation = describeQuery(query);
  return { query, parser: 'rules' };
}

/** A plain-language restatement, used when the rule parser ran and as a UI fallback. */
export function describeQuery(query) {
  const parts = [];
  parts.push(query.tripType === 'round_trip' ? 'Round trip' : 'One way');
  if (query.origin.text || query.origin.iata) parts.push(`from ${query.origin.text || query.origin.iata}`);
  if (query.destination.text || query.destination.iata) parts.push(`to ${query.destination.text || query.destination.iata}`);
  if (query.outbound.date) parts.push(`on ${query.outbound.date}`);
  if (query.outbound.departAfter) parts.push(`departing after ${query.outbound.departAfter}`);
  if (query.outbound.departBefore) parts.push(`departing before ${query.outbound.departBefore}`);
  if (query.outbound.arriveBefore) parts.push(`arriving before ${query.outbound.arriveBefore}`);
  if (query.filters.maxTotalPrice) parts.push(`under ${query.filters.maxTotalPrice} ${query.filters.currency}`);
  if (query.filters.maxStops === 0) parts.push('nonstop only');
  return `${parts.join(' ')}.`;
}
