import test from 'node:test';
import assert from 'node:assert/strict';

import { buildLeg, isRedEye } from '../lib/offers.mjs';
import { applyFilters } from '../lib/filter.mjs';
import { rankOffers } from '../lib/rank.mjs';
import { normalizeQuery } from '../lib/parse.mjs';
import { mockOffers } from '../lib/mock.mjs';

const DATE = '2026-08-16';

function segment(from, to, departAt, arriveAt, carrier = 'AA') {
  const minutes = (Date.parse(`${arriveAt}Z`) - Date.parse(`${departAt}Z`)) / 60000;
  return { from, to, departAt, arriveAt, carrier, flightNumber: `${carrier}100`, aircraft: null, durationMinutes: minutes };
}

function offer(id, total, segments, extra = {}) {
  const leg = buildLeg(segments);
  return {
    id,
    source: 'test',
    price: { total, currency: 'USD', perTraveller: total },
    legs: [leg],
    carriers: [...new Set(segments.map((s) => s.carrier))],
    seatsRemaining: 9,
    ...extra,
  };
}

/** A nonstop at 17:00, and a cheaper one-stop with a punishing connection. */
const NONSTOP = offer('nonstop', 320, [segment('BOS', 'LIS', `${DATE}T17:00:00`, `${DATE}T23:30:00`)]);
const CHEAP_UGLY = offer('cheap', 210, [
  segment('BOS', 'CDG', `${DATE}T17:10:00`, `${DATE}T23:50:00`, 'AF'),
  segment('CDG', 'LIS', `2026-08-17T00:25:00`, `2026-08-17T02:40:00`, 'TP'),
]);
const MORNING = offer('morning', 260, [segment('BOS', 'LIS', `${DATE}T08:00:00`, `${DATE}T14:20:00`)]);

const baseQuery = (overrides = {}) => normalizeQuery({
  origin: { text: 'Boston', iata: 'BOS', confident: true },
  destination: { text: 'Lisbon', iata: 'LIS', confident: true },
  outbound: { date: DATE, ...overrides.outbound },
  filters: { ...overrides.filters },
  priorities: { ...overrides.priorities },
});

test('layovers are derived from the gap between segments', () => {
  const leg = CHEAP_UGLY.legs[0];
  assert.equal(leg.stops, 1);
  assert.equal(leg.layovers.length, 1);
  assert.equal(leg.layovers[0].airport, 'CDG');
  assert.equal(leg.layovers[0].minutes, 35);
  assert.equal(leg.layovers[0].changesAirport, false);
});

test('an airport change during a connection is flagged, not counted as a normal layover', () => {
  const split = buildLeg([
    segment('BOS', 'LGW', `${DATE}T09:00:00`, `${DATE}T20:00:00`),
    segment('LHR', 'LIS', `2026-08-17T09:00:00`, `2026-08-17T11:40:00`),
  ]);
  assert.equal(split.layovers[0].changesAirport, true);
});

test('a late departure landing at dawn is a red-eye', () => {
  const redEye = buildLeg([segment('BOS', 'LIS', `${DATE}T22:40:00`, `2026-08-17T06:10:00`)]);
  assert.equal(isRedEye(redEye), true);
  assert.equal(isRedEye(NONSTOP.legs[0]), false);
});

test('"after 16:00" removes the morning flight', () => {
  const result = applyFilters([NONSTOP, CHEAP_UGLY, MORNING], baseQuery({ outbound: { departAfter: '16:00' } }));
  assert.deepEqual(result.offers.map((each) => each.id).sort(), ['cheap', 'nonstop']);
  assert.equal(result.relaxed.length, 0);
  assert.equal(result.droppedCount, 1);
});

test('an impossible budget is relaxed rather than returning nothing, and says so', () => {
  const result = applyFilters([NONSTOP, CHEAP_UGLY, MORNING], baseQuery({
    outbound: { departAfter: '16:00' },
    filters: { maxTotalPrice: 50 },
  }));

  assert.ok(result.offers.length > 0, 'an empty page tells the traveller nothing');
  assert.deepEqual(result.relaxed.map((each) => each.id), ['maxTotalPrice']);
  assert.ok(result.applied.some((each) => each.id === 'departAfter'), 'the schedule constraint survives the budget');
});

test('a hard schedule outranks the budget when both cannot hold', () => {
  // Nothing is under $150, so one of the two constraints has to give.
  const result = applyFilters([NONSTOP, CHEAP_UGLY, MORNING], baseQuery({
    outbound: { departAfter: '16:00' },
    filters: { maxTotalPrice: 150 },
  }));

  assert.deepEqual(result.relaxed.map((each) => each.id), ['maxTotalPrice'], 'budget gives before the schedule does');
  assert.ok(result.offers.every((each) => each.legs[0].departAt.slice(11, 13) >= '16'));
});

test('price-obsessed weights put the cheapest fare on top', () => {
  const query = baseQuery({ priorities: { price: 1, duration: 0.1, stops: 0.1, timing: 0.1, comfort: 0.1 } });
  const { results } = rankOffers([NONSTOP, CHEAP_UGLY, MORNING], query);
  assert.equal(results[0].id, 'cheap');
});

test('weighting comfort and stops beats a cheap fare with a 35-minute connection', () => {
  const query = baseQuery({ priorities: { price: 0.5, duration: 0.5, stops: 0.9, timing: 0.4, comfort: 0.9 } });
  // Just the two evening options — the morning flight wins on these weights, and rightly so,
  // but it is not what this test is about.
  const { results } = rankOffers([NONSTOP, CHEAP_UGLY], query);
  assert.equal(results[0].id, 'nonstop', 'the $110 saving is not worth a 35m connection at midnight');
  assert.ok(results[0].badges.includes('Best overall'));
});

test('the top result explains what the extra money buys', () => {
  const query = baseQuery({ priorities: { price: 0.5, duration: 0.5, stops: 0.9, timing: 0.4, comfort: 0.9 } });
  const { results } = rankOffers([NONSTOP, CHEAP_UGLY], query);
  assert.match(results[0].tradeoff, /\$110 more than the cheapest/);
  assert.match(results[0].tradeoff, /one fewer stop|shorter/);
});

test('the cheapest and fastest options are always badged even when they do not win', () => {
  const query = baseQuery({ priorities: { price: 0.3, duration: 0.3, stops: 1, timing: 0.6, comfort: 1 } });
  const { results } = rankOffers([NONSTOP, CHEAP_UGLY, MORNING], query);
  const badged = results.flatMap((each) => each.badges);
  assert.ok(badged.includes('Cheapest'));
  assert.ok(badged.includes('Best overall'));
});

test('a tight connection surfaces as a warning', () => {
  const query = baseQuery();
  const { results } = rankOffers([CHEAP_UGLY], query);
  assert.ok(results[0].warnings.some((text) => /35m to connect/.test(text)));
  assert.ok(results[0].warnings.some((text) => /Two airlines/.test(text)));
});

test('the cheapest option is told it is the cheapest', () => {
  const { results } = rankOffers([NONSTOP, CHEAP_UGLY, MORNING], baseQuery());
  const cheapest = results.find((each) => each.id === 'cheap');
  assert.ok(cheapest.reasons.includes('Cheapest of everything we found'));
});

test('stats describe the set, not just the winner', () => {
  const { stats } = rankOffers([NONSTOP, CHEAP_UGLY, MORNING], baseQuery());
  assert.equal(stats.count, 3);
  assert.equal(stats.cheapestPrice, 210);
  assert.equal(stats.medianPrice, 260);
});

test('ranking an empty set is not an error', () => {
  const { results, stats } = rankOffers([], baseQuery());
  assert.deepEqual(results, []);
  assert.equal(stats, null);
});

test('the mock provider is deterministic, so demos and tests are stable', () => {
  const first = mockOffers({ origin: 'BOS', destination: 'LIS', date: DATE });
  const second = mockOffers({ origin: 'BOS', destination: 'LIS', date: DATE });
  assert.deepEqual(first, second);

  const elsewhere = mockOffers({ origin: 'BOS', destination: 'CDG', date: DATE });
  assert.notDeepEqual(first[0].price.total, elsewhere[0].price.total);
});

test('mock fares scale with the number of travellers', () => {
  const single = mockOffers({ origin: 'BOS', destination: 'LIS', date: DATE, travellers: 1 });
  const couple = mockOffers({ origin: 'BOS', destination: 'LIS', date: DATE, travellers: 2 });
  assert.equal(couple[0].price.total, single[0].price.total * 2);
  assert.equal(couple[0].price.perTraveller, single[0].price.perTraveller);
});
