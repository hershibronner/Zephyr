/**
 * Relaxation is the part most likely to be quietly wrong: it only runs when a search has already
 * failed, so a bad choice here is invisible unless it is pinned down.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { buildLeg } from '../lib/offers.mjs';
import { applyFilters } from '../lib/filter.mjs';
import { normalizeQuery } from '../lib/parse.mjs';

const DATE = '2026-09-03';

function nonstop(id, total, departAt, arriveAt) {
  return {
    id,
    source: 'test',
    price: { total, currency: 'USD', perTraveller: total },
    legs: [buildLeg([{ from: 'LON', to: 'TYO', departAt, arriveAt, carrier: 'BA', flightNumber: 'BA5', aircraft: null, durationMinutes: 700 }])],
    carriers: ['BA'],
    seatsRemaining: 9,
  };
}

function oneStop(id, total, departAt) {
  const segments = [
    { from: 'LON', to: 'DXB', departAt, arriveAt: `${DATE}T18:00:00`, carrier: 'EK', flightNumber: 'EK1', aircraft: null, durationMinutes: 420 },
    { from: 'DXB', to: 'TYO', departAt: `${DATE}T20:00:00`, arriveAt: `2026-09-04T10:00:00`, carrier: 'EK', flightNumber: 'EK2', aircraft: null, durationMinutes: 540 },
  ];
  return {
    id,
    source: 'test',
    price: { total, currency: 'USD', perTraveller: total },
    legs: [buildLeg(segments)],
    carriers: ['EK'],
    seatsRemaining: 9,
  };
}

const query = (filters, outbound = {}) => normalizeQuery({
  origin: { text: 'London', iata: 'LON', confident: true },
  destination: { text: 'Tokyo', iata: 'TYO', confident: true },
  outbound: { date: DATE, ...outbound },
  filters,
});

const DIRECT = nonstop('direct', 900, `${DATE}T11:00:00`, `2026-09-04T06:00:00`);
const CONNECTING = oneStop('connecting', 620, `${DATE}T11:00:00`);

test('an unreachable budget gives way without also sacrificing "nonstop"', () => {
  // The greedy version of this dropped "nonstop" first, discovered it did not help, dropped the
  // budget as well, and served a connecting flight when a nonstop was right there.
  const result = applyFilters([DIRECT, CONNECTING], query({ maxTotalPrice: 40, maxStops: 0 }));

  assert.deepEqual(result.relaxed.map((each) => each.id), ['maxTotalPrice']);
  assert.deepEqual(result.applied.map((each) => each.id), ['maxStops']);
  assert.deepEqual(result.offers.map((each) => each.id), ['direct']);
});

test('when everything can be honoured, nothing is relaxed', () => {
  const result = applyFilters([DIRECT, CONNECTING], query({ maxTotalPrice: 1000, maxStops: 0 }));
  assert.deepEqual(result.relaxed, []);
  assert.deepEqual(result.offers.map((each) => each.id), ['direct']);
});

test('a more important constraint is never traded away to rescue a lesser one', () => {
  // Only the connecting flight is under $700; only the nonstop has zero stops. Budget outranks
  // stops, so the budget survives and "nonstop" is what gives.
  const result = applyFilters([DIRECT, CONNECTING], query({ maxTotalPrice: 700, maxStops: 0 }));

  assert.deepEqual(result.applied.map((each) => each.id), ['maxTotalPrice']);
  assert.deepEqual(result.relaxed.map((each) => each.id), ['maxStops']);
  assert.deepEqual(result.offers.map((each) => each.id), ['connecting']);
});

test('a stated departure time outranks the budget', () => {
  const evening = nonstop('evening', 1500, `${DATE}T19:00:00`, `2026-09-04T14:00:00`);
  const result = applyFilters(
    [DIRECT, CONNECTING, evening],
    query({ maxTotalPrice: 700 }, { departAfter: '18:00' }));

  assert.deepEqual(result.applied.map((each) => each.id), ['departAfter']);
  assert.deepEqual(result.relaxed.map((each) => each.id), ['maxTotalPrice']);
  assert.deepEqual(result.offers.map((each) => each.id), ['evening']);
});

test('with no constraints at all, everything survives untouched', () => {
  const result = applyFilters([DIRECT, CONNECTING], query({}));
  assert.equal(result.offers.length, 2);
  assert.equal(result.droppedCount, 0);
});

test('an empty offer list does not throw', () => {
  const result = applyFilters([], query({ maxTotalPrice: 100 }));
  assert.deepEqual(result.offers, []);
});
