/**
 * Skylark front end. Vanilla, no build step — the server hands this file over as-is.
 *
 * The interface has one job beyond showing results: make the search legible. Everything the parser
 * decided is shown back as chips, and every result carries the reasons it ranked where it did. A
 * ranking you can't argue with is a ranking you can't trust.
 */

const form = document.getElementById('search-form');
const input = document.getElementById('query');
const submit = document.getElementById('submit');
const statusBox = document.getElementById('status');
const reading = document.getElementById('reading');
const interpretation = document.getElementById('interpretation');
const chips = document.getElementById('chips');
const relaxedLine = document.getElementById('relaxed');
const clarifications = document.getElementById('clarifications');
const summary = document.getElementById('summary');
const results = document.getElementById('results');
const sources = document.getElementById('sources');

const CURRENCY_SYMBOLS = { USD: '$', GBP: '£', EUR: '€' };

function money(amount, currency) {
  const symbol = CURRENCY_SYMBOLS[currency];
  const rounded = Math.round(amount).toLocaleString('en-US');
  return symbol ? `${symbol}${rounded}` : `${rounded} ${currency}`;
}

function duration(minutes) {
  const total = Math.round(minutes);
  const hours = Math.floor(total / 60);
  const rest = total % 60;
  if (hours === 0) return `${rest}m`;
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`;
}

function clock(stamp) {
  return typeof stamp === 'string' ? stamp.slice(11, 16) : '';
}

function dayOffset(from, to) {
  if (typeof from !== 'string' || typeof to !== 'string') return 0;
  const start = Date.parse(`${from.slice(0, 10)}T00:00:00Z`);
  const end = Date.parse(`${to.slice(0, 10)}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end)) return 0;
  return Math.round((end - start) / 86400000);
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function setStatus(message, isError = false) {
  statusBox.hidden = !message;
  statusBox.className = isError ? 'status error' : 'status';
  statusBox.textContent = message ?? '';
}

/** The chips are the parsed query made visible — if we misread something, it shows up here. */
function renderChips(payload) {
  chips.replaceChildren();
  const { query, filters, dates, provider, parser } = payload;
  const values = [];

  if (payload.route) values.push(`${payload.route.from} → ${payload.route.to}`);
  values.push(query.tripType === 'round_trip' ? 'Round trip' : 'One way');
  if (dates?.length === 1) values.push(dates[0]);
  else if (dates?.length > 1) values.push(`${dates.length} dates: ${dates[0]} – ${dates[dates.length - 1]}`);

  const travellers = query.filters.adults + query.filters.children + query.filters.infants;
  if (travellers > 1) values.push(`${travellers} travellers`);
  if (query.filters.cabin !== 'ECONOMY') values.push(query.filters.cabin.replace('_', ' ').toLowerCase());

  for (const constraint of filters?.applied ?? []) values.push(constraint.label);

  for (const value of values) chips.append(element('span', 'chip', value));
  for (const constraint of filters?.relaxed ?? []) chips.append(element('span', 'chip dropped', constraint.label));

  chips.append(element('span', 'chip', parser === 'claude' ? 'read by Claude' : 'read by keyword rules'));
  if (provider === 'mock') chips.append(element('span', 'chip dropped', 'demo fares — not bookable'));
}

function renderSummary(payload) {
  const { stats, searched } = payload;
  if (!stats) {
    summary.hidden = true;
    return;
  }

  summary.hidden = false;
  summary.replaceChildren();

  const stat = (label, value) => {
    const box = element('div', 'stat');
    box.append(element('div', 'label', label), element('div', 'value', value));
    return box;
  };

  summary.append(
    stat('Options compared', String(searched ?? stats.count)),
    stat('Cheapest', money(stats.cheapestPrice, stats.currency)),
    stat('Typical', money(stats.medianPrice, stats.currency)),
    stat('Fastest', duration(stats.fastestMinutes)),
  );
}

function renderLeg(leg) {
  const box = element('div', 'leg');

  const times = element('div', 'times');
  times.append(document.createTextNode(clock(leg.departAt)));
  times.append(element('span', 'arrow', '→'));
  times.append(document.createTextNode(clock(leg.arriveAt)));

  const overnight = dayOffset(leg.departAt, leg.arriveAt);
  if (overnight > 0) times.append(element('span', 'plus-day', `+${overnight}`));
  box.append(times);

  const route = element('div', 'route');
  const stopsText = leg.stops === 0 ? 'Nonstop' : leg.stops === 1 ? '1 stop' : `${leg.stops} stops`;
  route.append(document.createTextNode(`${leg.from} → ${leg.to}`));
  route.append(element('span', 'dot', '·'));
  route.append(document.createTextNode(duration(leg.durationMinutes)));
  route.append(element('span', 'dot', '·'));
  route.append(document.createTextNode(stopsText));
  box.append(route);

  const path = leg.segments
    .map((segment) => `${segment.flightNumber} ${segment.from}–${segment.to}`)
    .join('   ');
  const layovers = leg.layovers.map((layover) => `${duration(layover.minutes)} in ${layover.airport}`).join(', ');
  box.append(element('div', 'path', layovers ? `${path}    ·    ${layovers}` : path));

  return box;
}

function renderOffer(offer, index) {
  const card = element('article', index === 0 ? 'offer top' : 'offer');

  const head = element('div', 'offer-head');
  const left = element('div');

  if (offer.badges.length > 0) {
    const badges = element('div', 'badges');
    offer.badges.forEach((label, position) => {
      badges.append(element('span', position === 0 ? 'badge' : 'badge secondary', label));
    });
    left.append(badges);
  }

  for (const leg of offer.legs) left.append(renderLeg(leg));
  head.append(left);

  const price = element('div', 'price');
  price.append(element('span', 'amount', money(offer.price.total, offer.price.currency)));
  if (offer.price.perTraveller && offer.price.perTraveller !== offer.price.total) {
    price.append(element('span', 'per', `${money(offer.price.perTraveller, offer.price.currency)} each`));
  } else {
    price.append(element('span', 'per', 'total'));
  }
  head.append(price);
  card.append(head);

  if (offer.tradeoff) card.append(element('p', 'tradeoff', offer.tradeoff));

  if (offer.reasons.length > 0) {
    const list = element('ul', 'reasons');
    for (const reason of offer.reasons) list.append(element('li', null, reason));
    card.append(list);
  }

  if (offer.warnings.length > 0) {
    const list = element('ul', 'warnings');
    for (const warning of offer.warnings) list.append(element('li', null, warning));
    card.append(list);
  }

  const score = element('div', 'score');
  const bars = element('div', 'score-bars');
  for (const [name, value] of Object.entries(offer.scores)) {
    const bar = element('div', 'score-bar', name);
    const track = element('div', 'track');
    const fill = element('div', 'fill');
    fill.style.width = `${Math.round(value * 100)}%`;
    track.append(fill);
    bar.append(track);
    bars.append(bar);
  }
  score.append(bars);

  const total = element('div', 'score-total');
  total.append(document.createTextNode('score '));
  total.append(element('b', null, String(offer.score)));
  score.append(total);
  card.append(score);

  return card;
}

function render(payload) {
  results.replaceChildren();

  if (payload.needs) {
    reading.hidden = true;
    summary.hidden = true;
    setStatus(payload.message, true);
    return;
  }

  reading.hidden = false;
  interpretation.textContent = payload.interpretation ?? '';
  renderChips(payload);

  const relaxed = payload.filters?.relaxed ?? [];
  relaxedLine.hidden = relaxed.length === 0;
  if (relaxed.length > 0) {
    relaxedLine.textContent =
      `Nothing matched everything you asked for, so we set aside ${relaxed.map((item) => `“${item.label}”`).join(' and then ')} to find you something.`;
  }

  const questions = payload.query?.clarifications ?? [];
  clarifications.hidden = questions.length === 0;
  clarifications.replaceChildren(...questions.map((question) => element('li', null, question)));

  renderSummary(payload);

  if (payload.results.length === 0) {
    setStatus(payload.message ?? 'No flights came back for that search.', false);
    return;
  }

  setStatus(null);
  payload.results.forEach((offer, index) => results.append(renderOffer(offer, index)));
}

async function runSearch(text) {
  submit.disabled = true;
  setStatus('Reading your request and searching…');
  results.replaceChildren();
  summary.hidden = true;

  try {
    const response = await fetch('/api/search', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ query: text }),
    });
    const payload = await response.json();

    if (!response.ok) {
      setStatus(payload.error ?? `Search failed (${response.status})`, true);
      return;
    }
    render(payload);
  } catch (error) {
    setStatus(`Could not reach the server: ${error.message}`, true);
  } finally {
    submit.disabled = false;
  }
}

form.addEventListener('submit', (event) => {
  event.preventDefault();
  const text = input.value.trim();
  if (text) runSearch(text);
});

input.addEventListener('keydown', (event) => {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') form.requestSubmit();
});

document.getElementById('examples').addEventListener('click', (event) => {
  const fill = event.target.closest('button')?.dataset.fill;
  if (!fill) return;
  input.value = fill;
  input.focus();
  runSearch(fill);
});

// Show what the server is actually wired up to, so demo data is never mistaken for real fares.
fetch('/api/health')
  .then((response) => response.json())
  .then((health) => {
    sources.replaceChildren();
    const line = (label, value) => {
      const span = element('span');
      span.append(document.createTextNode(`${label} `), element('b', null, value));
      return span;
    };
    sources.append(
      line('search:', health.ai ? 'Claude' : 'keyword rules'),
      line('fares:', health.fares),
    );
  })
  .catch(() => {});
