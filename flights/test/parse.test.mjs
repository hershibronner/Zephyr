import test from 'node:test';
import assert from 'node:assert/strict';

import { parseWithRules, normalizeQuery, parseQuery } from '../lib/parse.mjs';
import { clockToMinutes, isoDurationToMinutes, localMinutes, addDays } from '../lib/util.mjs';

const TODAY = '2026-08-10'; // a Monday

test('ISO durations become minutes', () => {
  assert.equal(isoDurationToMinutes('PT5H30M'), 330);
  assert.equal(isoDurationToMinutes('PT45M'), 45);
  assert.equal(isoDurationToMinutes('PT12H'), 720);
  assert.equal(isoDurationToMinutes('P1DT2H'), 1560);
  assert.equal(isoDurationToMinutes(undefined), 0);
});

test('clock text becomes minutes past midnight', () => {
  assert.equal(clockToMinutes('4pm'), 960);
  assert.equal(clockToMinutes('4 PM'), 960);
  assert.equal(clockToMinutes('16:00'), 960);
  assert.equal(clockToMinutes('12am'), 0);
  assert.equal(clockToMinutes('12pm'), 720);
  assert.equal(clockToMinutes('nonsense'), null);
  assert.equal(clockToMinutes('25:00'), null);
});

test('local times are read as wall clock, not converted', () => {
  // Amadeus stamps carry no offset; "after 4PM" has to mean 4PM where the traveller is standing.
  assert.equal(localMinutes('2026-08-16T16:35:00'), 995);
});

test('the rule parser reads the shape in the brief', () => {
  const query = normalizeQuery(
    parseWithRules('From Boston to Lisbon on Sun Aug 16, only after 4PM, budget $600, no red-eyes', TODAY));

  assert.equal(query.origin.iata, 'BOS');
  assert.equal(query.destination.iata, 'LIS');
  assert.equal(query.outbound.date, '2026-08-16');
  assert.equal(query.outbound.departAfter, '16:00');
  assert.equal(query.filters.maxTotalPrice, 600);
  assert.equal(query.filters.currency, 'USD');
  assert.equal(query.filters.avoidRedEye, true);
});

test('a month/day in the past rolls to next year', () => {
  const query = parseWithRules('New York to Paris on Feb 3', TODAY);
  assert.equal(query.outbound.date, '2027-02-03');
});

test('a bare weekday resolves forward, never to today', () => {
  const query = parseWithRules('SFO to DEN next Friday', TODAY);
  assert.equal(query.outbound.date, '2026-08-14');

  const monday = parseWithRules('SFO to DEN on Monday', TODAY);
  assert.equal(monday.outbound.date, addDays(TODAY, 7), 'today is Monday, so "Monday" means next week');
});

test('nonstop and cabin are picked up', () => {
  const query = normalizeQuery(parseWithRules('2 adults from San Francisco to Denver nonstop in business class', TODAY));
  assert.equal(query.filters.maxStops, 0);
  assert.equal(query.filters.cabin, 'BUSINESS');
  assert.equal(query.filters.adults, 2);
});

test('normalizeQuery clamps hostile or missing values', () => {
  const query = normalizeQuery({
    filters: { adults: 99, children: -4, infants: 5, maxTotalPrice: -10, currency: 'usd', maxStops: -1 },
    priorities: { price: 40, duration: -3, stops: NaN, timing: 0.5, comfort: 0.5 },
  });

  assert.equal(query.filters.adults, 9);
  assert.equal(query.filters.children, 0);
  assert.ok(query.filters.infants <= query.filters.adults);
  assert.equal(query.filters.maxTotalPrice, null, 'a negative budget is no budget');
  assert.equal(query.filters.currency, 'USD');
  assert.equal(query.filters.maxStops, 0);
  assert.equal(query.priorities.price, 1);
  assert.equal(query.priorities.duration, 0);
  assert.equal(query.priorities.stops, 0);
});

test('an all-zero weight vector falls back to balanced priorities', () => {
  const query = normalizeQuery({ priorities: { price: 0, duration: 0, stops: 0, timing: 0, comfort: 0 } });
  assert.ok(query.priorities.price > 0, 'every option would score identically otherwise');
});

test('parseQuery falls back to rules with no API key and still describes itself', async () => {
  const { query, parser } = await parseQuery('From Boston to Lisbon on Aug 16 after 4pm', { today: TODAY });
  assert.equal(parser, 'rules');
  assert.equal(query.origin.iata, 'BOS');
  assert.match(query.interpretation, /Boston/);
});
