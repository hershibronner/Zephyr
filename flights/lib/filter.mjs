/**
 * Hard constraints, with progressive relaxation.
 *
 * A search that returns nothing is the worst outcome — worse than a slightly wrong result, because
 * it tells the traveller nothing. So constraints are applied in a defined order, and when the set
 * comes back empty we drop the least-important one and try again, recording what we dropped.
 *
 * "Nothing under $400 — the cheapest that leaves after 4PM is $462" is a useful answer.
 * "No results" is not.
 */

import { localMinutes } from './util.mjs';
import { isRedEye } from './offers.mjs';

/**
 * Ordered least-important first: this is the order constraints get dropped in.
 *
 * Time constraints sit at the very end, protected even above budget. "Only after 4PM" usually
 * means someone physically cannot leave earlier — a cheap 10am flight is not a worse answer, it
 * is no answer. A fare $40 over budget at least remains a flight they could take. Whatever gets
 * relaxed is reported back prominently, never quietly.
 */
function buildConstraints(query) {
  const { filters, outbound } = query;
  const constraints = [];

  const add = (id, label, test) => constraints.push({ id, label, test });

  if (filters.maxLayoverMinutes != null) {
    add('maxLayover', `layovers under ${filters.maxLayoverMinutes}m`, (offer) =>
      everyLayover(offer, (layover) => layover.minutes <= filters.maxLayoverMinutes));
  }

  if (filters.minLayoverMinutes != null) {
    add('minLayover', `layovers over ${filters.minLayoverMinutes}m`, (offer) =>
      everyLayover(offer, (layover) => layover.minutes >= filters.minLayoverMinutes));
  }

  if (filters.avoidRedEye) {
    add('redEye', 'no red-eyes', (offer) => !offer.legs.some(isRedEye));
  }

  if (filters.maxDurationMinutes != null) {
    add('maxDuration', `under ${Math.round(filters.maxDurationMinutes / 60)}h per leg`, (offer) =>
      offer.legs.every((leg) => leg.durationMinutes <= filters.maxDurationMinutes));
  }

  if (filters.excludeAirlines.length > 0) {
    add('excludeAirlines', `not ${filters.excludeAirlines.join(', ')}`, (offer) =>
      !offer.carriers.some((carrier) => filters.excludeAirlines.includes(carrier)));
  }

  if (filters.includeAirlines.length > 0) {
    add('includeAirlines', `only ${filters.includeAirlines.join(', ')}`, (offer) =>
      offer.carriers.every((carrier) => filters.includeAirlines.includes(carrier)));
  }

  if (filters.maxStops != null) {
    const label = filters.maxStops === 0 ? 'nonstop only' : `at most ${filters.maxStops} stop(s)`;
    add('maxStops', label, (offer) => offer.legs.every((leg) => leg.stops <= filters.maxStops));
  }

  if (filters.maxTotalPrice != null) {
    add('maxTotalPrice', `under ${formatMoney(filters.maxTotalPrice, filters.currency)}`, (offer) =>
      offer.price.total <= filters.maxTotalPrice);
  }

  // Time-of-day rules apply to the outbound leg, which is the one people put times on.
  if (outbound.departAfter != null) {
    const cutoff = toMinutes(outbound.departAfter);
    add('departAfter', `departs after ${outbound.departAfter}`, (offer) =>
      atOrAfter(localMinutes(offer.legs[0]?.departAt), cutoff));
  }

  if (outbound.departBefore != null) {
    const cutoff = toMinutes(outbound.departBefore);
    add('departBefore', `departs before ${outbound.departBefore}`, (offer) =>
      atOrBefore(localMinutes(offer.legs[0]?.departAt), cutoff));
  }

  if (outbound.arriveBefore != null) {
    const cutoff = toMinutes(outbound.arriveBefore);
    add('arriveBefore', `arrives before ${outbound.arriveBefore}`, (offer) =>
      atOrBefore(localMinutes(offer.legs[0]?.arriveAt), cutoff));
  }

  return constraints;
}

function everyLayover(offer, test) {
  return offer.legs.every((leg) => leg.layovers.every(test));
}

function toMinutes(clock) {
  const [hours, minutes] = String(clock).split(':').map(Number);
  return (hours || 0) * 60 + (minutes || 0);
}

function atOrAfter(actual, cutoff) {
  return actual != null && actual >= cutoff;
}

function atOrBefore(actual, cutoff) {
  return actual != null && actual <= cutoff;
}

export function formatMoney(amount, currency) {
  const symbol = { USD: '$', GBP: '£', EUR: '€' }[currency];
  const rounded = Math.round(amount);
  return symbol ? `${symbol}${rounded.toLocaleString('en-US')}` : `${rounded.toLocaleString('en-US')} ${currency}`;
}

/**
 * Apply every constraint; when that empties the set, give up the smallest, least-important group
 * of constraints that lets something through.
 *
 * The obvious approach — drop them one at a time from the least important end until results
 * appear — gets this wrong. Asked for "nonstop under $40" it would drop "nonstop" (no help, still
 * nothing under $40), then drop the budget too, and end up showing connecting flights when
 * perfectly good nonstops were available all along.
 *
 * So instead: every offer already tells us exactly which constraints it satisfies. The best set we
 * can possibly keep is the satisfied-set of whichever surviving offer satisfies the most valuable
 * combination — no search needed, and the answer is optimal rather than merely reachable. Value is
 * lexicographic by importance (weight 2^rank), so a more important constraint is never sacrificed
 * to rescue a less important one.
 *
 * Returns { offers, applied, relaxed, droppedCount }; `relaxed` is what the UI tells the traveller
 * we could not honour.
 */
export function applyFilters(offers, query) {
  const constraints = buildConstraints(query);

  if (constraints.length === 0 || offers.length === 0) {
    return { offers, applied: [], relaxed: [], droppedCount: 0 };
  }

  // Which constraints each offer satisfies, as a bitmask over `constraints`.
  const masks = offers.map((offer) =>
    constraints.reduce((mask, constraint, index) => (constraint.test(offer) ? mask | (1 << index) : mask), 0));

  const value = (mask) =>
    constraints.reduce((total, _, index) => (mask & (1 << index) ? total + 2 ** index : total), 0);

  const keepMask = masks.reduce((best, mask) => (value(mask) > value(best) ? mask : best), 0);

  const kept = offers.filter((_, index) => (masks[index] & keepMask) === keepMask);

  const applied = [];
  const relaxed = [];
  constraints.forEach((constraint, index) => {
    const entry = { id: constraint.id, label: constraint.label };
    if (keepMask & (1 << index)) applied.push(entry);
    else relaxed.push(entry);
  });

  return { offers: kept, applied, relaxed, droppedCount: offers.length - kept.length };
}
