/**
 * A deterministic synthetic provider.
 *
 * Its job is to let the whole pipeline — parse, filter, rank, render — run and be tested with no
 * credentials at all, and to give the ranking something with real texture to sort: a cheap fare
 * with an ugly connection, a nonstop that costs more, a red-eye, a routing that changes airport.
 *
 * It is seeded from the route and date, so the same search always produces the same results. That
 * makes the tests meaningful and the demo stable. Prices here are invented and the results are
 * labelled as such everywhere they surface — nothing in here is bookable.
 */

import { buildLeg } from './offers.mjs';
import { addDays } from './util.mjs';
import { typicalFlightMinutes } from './airports.mjs';

/** mulberry32 — small, fast, and good enough for shaping demo data. */
function seededRandom(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function hash(text) {
  let value = 2166136261;
  for (let index = 0; index < text.length; index += 1) {
    value ^= text.charCodeAt(index);
    value = Math.imul(value, 16777619);
  }
  return value >>> 0;
}

const CARRIERS = ['AA', 'DL', 'UA', 'B6', 'AS', 'BA', 'AF', 'KL', 'LH', 'TP', 'IB', 'TK', 'EK'];
const HUBS = ['ATL', 'ORD', 'DFW', 'CLT', 'AMS', 'CDG', 'FRA', 'LHR', 'MAD', 'IST', 'DXB', 'LIS'];

function stamp(date, minutesFromMidnight) {
  const dayOffset = Math.floor(minutesFromMidnight / 1440);
  const withinDay = ((minutesFromMidnight % 1440) + 1440) % 1440;
  const day = dayOffset === 0 ? date : addDays(date, dayOffset);
  const hours = String(Math.floor(withinDay / 60)).padStart(2, '0');
  const minutes = String(withinDay % 60).padStart(2, '0');
  return `${day}T${hours}:${minutes}:00`;
}

/**
 * Build one offer: pick a departure time, decide on stops, derive a price from the shape of the
 * trip so that cheap options genuinely are the awkward ones — which is the only way the ranking
 * has anything to prove.
 */
function buildOffer(index, context) {
  const { random, origin, destination, date, baseMinutes, basePrice, travellers, cabin } = context;

  const departMinutes = Math.round(random() * 1380 + 30);
  const stops = random() < 0.32 ? 0 : random() < 0.82 ? 1 : 2;
  const carrier = CARRIERS[Math.floor(random() * CARRIERS.length)];

  const segments = [];
  let cursor = departMinutes;
  let waypoint = origin;

  // A connection through the place you are already going to, or already leaving, is not a
  // connection. Real inventory does not do it, and neither should the demo data.
  const usableHubs = HUBS.filter((hub) => hub !== origin && hub !== destination);

  for (let leg = 0; leg <= stops; leg += 1) {
    const isLast = leg === stops;
    const next = isLast ? destination : usableHubs[Math.floor(random() * usableHubs.length)];

    // A nonstop is the direct routing by definition, so it barely varies. Connections detour, and
    // the further off the direct line the hub sits, the more they add — hence the wider spread and
    // the fixed penalty per extra leg.
    const share = baseMinutes / (stops + 1);
    const flying = stops === 0
      ? Math.round(share * (0.95 + random() * 0.12))
      : Math.round(share * (0.8 + random() * 0.45)) + 20;

    segments.push({
      from: waypoint,
      to: next,
      departAt: stamp(date, cursor),
      arriveAt: stamp(date, cursor + flying),
      carrier: random() < 0.15 ? CARRIERS[Math.floor(random() * CARRIERS.length)] : carrier,
      flightNumber: `${carrier}${100 + Math.floor(random() * 899)}`,
      aircraft: null,
      durationMinutes: flying,
    });

    cursor += flying;
    if (!isLast) {
      // A deliberately wide spread: some 35-minute sprints, some five-hour sits.
      const layover = random() < 0.2 ? 30 + Math.round(random() * 25) : 55 + Math.round(random() * 320);
      cursor += layover;
    }
    waypoint = next;
  }

  const built = buildLeg(segments);

  // Price falls with inconvenience — more stops, worse hours, longer elapsed time — so the
  // cheapest option is usually the one with something wrong with it, as in real inventory.
  const stopDiscount = [1, 0.78, 0.66][stops];
  const hour = departMinutes / 60;
  const hourDiscount = hour < 6.5 || hour > 21.5 ? 0.85 : 1;
  const lengthDiscount = 1 - Math.min(0.2, (built.durationMinutes / baseMinutes - 1) * 0.12);
  const cabinMultiplier = { ECONOMY: 1, PREMIUM_ECONOMY: 1.9, BUSINESS: 3.4, FIRST: 6.1 }[cabin] ?? 1;
  const noise = 0.88 + random() * 0.3;

  const perTraveller = Math.round(basePrice * stopDiscount * hourDiscount * lengthDiscount * cabinMultiplier * noise);

  return {
    id: `mock-${origin}${destination}-${date}-${index}`,
    source: 'mock',
    price: {
      total: perTraveller * travellers,
      currency: context.currency,
      perTraveller,
    },
    legs: [built],
    carriers: [...new Set(segments.map((segment) => segment.carrier))],
    seatsRemaining: random() < 0.25 ? 1 + Math.floor(random() * 3) : 4 + Math.floor(random() * 6),
    cabin,
  };
}

/**
 * Generate a stable set of offers for one route and date. `count` is the number of candidates the
 * ranking gets to choose from.
 */
export function mockOffers({ origin, destination, date, travellers = 1, cabin = 'ECONOMY', currency = 'USD', count = 16 }) {
  const seed = hash(`${origin}|${destination}|${date}|${cabin}`);
  const random = seededRandom(seed);

  // Stable per route, and grounded in a coarse geography so a long haul actually looks like one.
  const spread = (hash(`${origin}${destination}`) % 1000) / 1000;
  const baseMinutes = typicalFlightMinutes(origin, destination, spread);

  // Fares rise with flying time but sub-linearly per mile, which is why a transatlantic hop is not
  // six times a domestic one. The exponent gives roughly the right shape across both ends.
  const rate = 0.28 + (hash(`${destination}${origin}`) % 100) / 500;
  const basePrice = 40 + Math.round(baseMinutes ** 1.15 * rate);

  const context = { random, origin, destination, date, baseMinutes, basePrice, travellers, cabin, currency };
  return Array.from({ length: count }, (_, index) => buildOffer(index, context));
}
