/**
 * End-to-end through the pipeline with no network: rule parser + mock provider.
 * This is the test that catches a break between the pieces rather than inside one.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { createSearcher } from '../lib/search.mjs';
import { candidateDates } from '../lib/amadeus.mjs';

const TODAY = '2026-08-10';
const searcher = createSearcher({ now: () => TODAY });

test('a full sentence becomes ranked, explained results', async () => {
  const payload = await searcher.search('From Boston to Lisbon on Sun Aug 16, only after 4PM, budget $900');

  assert.equal(payload.ok, true);
  assert.equal(payload.provider, 'mock');
  assert.deepEqual(payload.dates, ['2026-08-16']);
  assert.equal(payload.route.from, 'Boston (BOS)');
  assert.equal(payload.route.to, 'Lisbon (LIS)');
  assert.ok(payload.results.length > 0);

  const top = payload.results[0];
  assert.ok(top.badges.includes('Best overall'));
  assert.ok(top.score > 0 && top.score <= 100);
  assert.ok(Array.isArray(top.reasons));
  assert.ok(top.legs[0].departAt.slice(11, 16) >= '16:00', 'the 4PM constraint held');
});

test('results are ordered best-first', async () => {
  const payload = await searcher.search('New York to Paris on Aug 20');
  const scores = payload.results.map((each) => each.score);
  assert.deepEqual(scores, [...scores].sort((a, b) => b - a));
});

test('an unrecognisable origin asks rather than guessing', async () => {
  const payload = await searcher.search('take me somewhere nice');
  assert.equal(payload.ok, false);
  assert.ok(payload.needs.length > 0);
  assert.match(payload.message, /flying from|flying to/);
});

test('a flexible window fans out across sampled dates', () => {
  const dates = candidateDates({ date: null, earliestDate: '2026-09-01', latestDate: '2026-09-15' }, { today: TODAY });
  assert.ok(dates.length > 1 && dates.length <= 7, 'sampled, not one request per day');
  assert.equal(dates[0], '2026-09-01');
  assert.equal(dates[dates.length - 1], '2026-09-15', 'the end of the window is sampled too');
});

test('a window that starts in the past begins today', () => {
  const dates = candidateDates({ earliestDate: '2026-08-01', latestDate: '2026-08-12' }, { today: TODAY });
  assert.equal(dates[0], TODAY);
});

test('an exact date is one request', () => {
  assert.deepEqual(candidateDates({ date: '2026-08-16' }, { today: TODAY }), ['2026-08-16']);
});

test('a nonstop-only search returns only nonstops, or says it could not', async () => {
  const payload = await searcher.search('San Francisco to Denver on Aug 14 nonstop');
  assert.ok(payload.results.length > 0);
  const relaxedStops = payload.filters.relaxed.some((each) => each.id === 'maxStops');
  if (!relaxedStops) {
    assert.ok(payload.results.every((each) => each.legs.every((leg) => leg.stops === 0)));
  }
});

test('a budget nobody can meet is reported, not silently ignored', async () => {
  const payload = await searcher.search('London to Tokyo on Sep 3 under $4');
  assert.ok(payload.results.length > 0, 'still shows options');
  assert.ok(payload.filters.relaxed.some((each) => each.id === 'maxTotalPrice'));
});

test('two travellers double the total but not the per-person fare', async () => {
  const one = await searcher.search('Boston to Lisbon on Aug 16');
  const two = await searcher.search('2 adults from Boston to Lisbon on Aug 16');
  assert.equal(two.stats.cheapestPrice, one.stats.cheapestPrice * 2);
});
