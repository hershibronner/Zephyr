/**
 * Ranking — the part that decides what "best" means.
 *
 * Sorting by price is easy and mostly wrong: the $180 fare with a 55-minute connection in Charles
 * de Gaulle and a 2:10am arrival is not a better trip than the $215 nonstop. So every offer gets
 * scored on five axes, each normalised to 0-1 against the rest of the result set, then combined
 * using weights taken from what the traveller actually said they cared about.
 *
 *   price     cheapest fare in the set scores 1; twice that scores 0.5
 *   duration  same shape, against the fastest option
 *   stops     nonstop 1, one stop 0.65, two 0.35
 *   timing    civilised departure and arrival hours, plus fit against stated time windows
 *   comfort   layover length, airport changes, red-eyes, staying on one airline
 *
 * Every score carries its own explanation, because a ranking a traveller can't interrogate is one
 * they won't trust. The `reasons` and `warnings` arrays are what the UI shows under each result.
 */

import { formatMinutes, localMinutes, unit } from './util.mjs';
import { isRedEye } from './offers.mjs';
import { formatMoney } from './filter.mjs';
import { describeCode } from './airports.mjs';

/** Below this, a missed inbound flight means missing the connection. */
const TIGHT_LAYOVER_MINUTES = 45;
/** Above this, the layover stops being a connection and becomes an unpaid stopover. */
const LONG_LAYOVER_MINUTES = 240;

const STOP_SCORES = [1, 0.65, 0.35, 0.15];

function stopScore(stops) {
  return STOP_SCORES[Math.min(stops, STOP_SCORES.length - 1)];
}

/**
 * A ratio rather than a min-max spread: the cheapest option scores 1, and everything else scores
 * relative to it. Min-max normalisation would score the second-cheapest of two near-identical
 * fares as 0, which badly overstates a $5 difference.
 */
function ratioScore(value, best) {
  if (!(value > 0) || !(best > 0)) return 0;
  return unit(best / value);
}

/** How pleasant a departure or arrival hour is, on its own terms. */
function hourComfort(minutes) {
  if (minutes == null) return 0.6;
  const hour = minutes / 60;
  if (hour >= 7 && hour < 20) return 1;
  if (hour >= 6 && hour < 7) return 0.8;
  if (hour >= 20 && hour < 22) return 0.8;
  if (hour >= 22 || hour < 5) return 0.3;
  return 0.55;
}

function timingScore(offer, query) {
  const leg = offer.legs[0];
  if (!leg) return 0.5;

  const departMinutes = localMinutes(leg.departAt);
  const arriveMinutes = localMinutes(leg.arriveAt);
  let score = hourComfort(departMinutes) * 0.5 + hourComfort(arriveMinutes) * 0.5;

  // Someone who says "only after 4PM" is usually describing when they get free, not a wish to fly
  // at midnight. Options soon after the cutoff are mildly preferred; the preference decays over
  // six hours and never drops the score by more than a fifth.
  const after = query.outbound.departAfter;
  if (after && departMinutes != null) {
    const cutoff = clockMinutes(after);
    const wait = Math.max(0, departMinutes - cutoff);
    score *= 1 - 0.2 * unit(wait / 360);
  }

  // A hard arrival deadline that is only just met is stressful; reward genuine margin.
  const arriveBefore = query.outbound.arriveBefore;
  if (arriveBefore && arriveMinutes != null) {
    const margin = clockMinutes(arriveBefore) - arriveMinutes;
    score *= 0.75 + 0.25 * unit(margin / 120);
  }

  return unit(score);
}

function clockMinutes(clock) {
  const [hours, minutes] = String(clock).split(':').map(Number);
  return (hours || 0) * 60 + (minutes || 0);
}

function layoverQuality(layover) {
  const { minutes } = layover;
  let quality;
  if (minutes < TIGHT_LAYOVER_MINUTES) quality = 0.25;
  else if (minutes < 60) quality = 0.7;
  else if (minutes <= 150) quality = 1;
  else if (minutes <= LONG_LAYOVER_MINUTES) quality = 0.8;
  else if (minutes <= 480) quality = 0.45;
  else quality = 0.2;

  // Landing at one airport and departing from another is a taxi across a city with your bags.
  if (layover.changesAirport) quality *= 0.4;
  return quality;
}

function comfortScore(offer) {
  const layovers = offer.legs.flatMap((leg) => leg.layovers);
  let score = layovers.length === 0
    ? 1
    : layovers.reduce((total, layover) => total + layoverQuality(layover), 0) / layovers.length;

  // Separate tickets on separate airlines mean a delay on the first leg is your problem, not
  // theirs. Worth a real penalty, not a rounding error.
  if (offer.carriers.length > 1) score *= 0.85;
  if (offer.legs.some(isRedEye)) score *= 0.6;

  return unit(score);
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function buildReasons(offer, scored, stats, query) {
  const reasons = [];
  const leg = offer.legs[0];

  if (offer.price.total === stats.cheapestPrice) {
    reasons.push('Cheapest of everything we found');
  } else if (stats.medianPrice > 0 && offer.price.total < stats.medianPrice * 0.9) {
    const saving = Math.round((1 - offer.price.total / stats.medianPrice) * 100);
    reasons.push(`${saving}% below the typical fare on this route today`);
  }

  if (leg?.stops === 0) {
    reasons.push('Nonstop');
  } else if (leg && offer.legs.every((each) => each.stops <= 1)) {
    const clean = offer.legs.flatMap((each) => each.layovers).every((layover) => layover.minutes >= 60 && layover.minutes <= 180 && !layover.changesAirport);
    if (clean) {
      const layover = leg.layovers[0];
      reasons.push(`Comfortable ${formatMinutes(layover.minutes)} connection in ${describeCode(layover.airport)}`);
    }
  }

  if (leg && stats.fastestMinutes > 0 && leg.durationMinutes <= stats.fastestMinutes * 1.05 && leg.stops > 0) {
    reasons.push('Among the fastest routings available');
  }

  if (stats.medianMinutes > 0 && leg && leg.durationMinutes < stats.medianMinutes - 60) {
    reasons.push(`${formatMinutes(stats.medianMinutes - leg.durationMinutes)} shorter than the typical option`);
  }

  if (query.outbound.arriveBefore && leg) {
    const margin = clockMinutes(query.outbound.arriveBefore) - (localMinutes(leg.arriveAt) ?? 0);
    if (margin >= 90) reasons.push(`Lands ${formatMinutes(margin)} before your deadline`);
  }

  if (offer.carriers.length === 1 && (leg?.stops ?? 0) > 0) {
    reasons.push('Single airline the whole way — connections are protected');
  }

  return reasons.slice(0, 3);
}

function buildWarnings(offer) {
  const warnings = [];

  for (const leg of offer.legs) {
    for (const layover of leg.layovers) {
      if (layover.changesAirport) {
        warnings.push(`Changes airport during the connection (arrives ${layover.airport}) — allow extra time`);
      } else if (layover.minutes < TIGHT_LAYOVER_MINUTES) {
        warnings.push(`Only ${formatMinutes(layover.minutes)} to connect in ${describeCode(layover.airport)}`);
      } else if (layover.minutes > LONG_LAYOVER_MINUTES) {
        warnings.push(`${formatMinutes(layover.minutes)} sitting in ${describeCode(layover.airport)}`);
      }
    }
    if (isRedEye(leg)) warnings.push('Overnight flight — you arrive having slept on a plane');
  }

  if (offer.carriers.length > 1) {
    warnings.push(`Two airlines (${offer.carriers.join(' + ')}) — a delay may not be rebooked for free`);
  }

  if (offer.seatsRemaining != null && offer.seatsRemaining <= 3) {
    warnings.push(`Only ${offer.seatsRemaining} seat${offer.seatsRemaining === 1 ? '' : 's'} left at this price`);
  }

  return warnings.slice(0, 3);
}

/**
 * Score, explain, and sort. Returns { results, stats, badges } where results are ordered best
 * first and each carries its own score breakdown.
 */
export function rankOffers(offers, query) {
  if (offers.length === 0) {
    return { results: [], stats: null };
  }

  const prices = offers.map((offer) => offer.price.total);
  const durations = offers.map((offer) => offer.legs.reduce((total, leg) => total + leg.durationMinutes, 0));

  const stats = {
    count: offers.length,
    cheapestPrice: Math.min(...prices),
    medianPrice: median(prices),
    dearestPrice: Math.max(...prices),
    fastestMinutes: Math.min(...durations),
    medianMinutes: median(durations),
    currency: offers[0].price.currency,
  };

  const weights = query.priorities;
  const weightTotal = Object.values(weights).reduce((total, weight) => total + weight, 0) || 1;

  const scored = offers.map((offer, index) => {
    const totalDuration = durations[index];
    const scores = {
      price: ratioScore(offer.price.total, stats.cheapestPrice),
      duration: ratioScore(totalDuration, stats.fastestMinutes),
      stops: stopScore(Math.max(...offer.legs.map((leg) => leg.stops))),
      timing: timingScore(offer, query),
      comfort: comfortScore(offer),
    };

    const total = Object.entries(scores).reduce((sum, [key, value]) => sum + value * weights[key], 0) / weightTotal;

    return {
      ...offer,
      totalDurationMinutes: totalDuration,
      scores,
      score: Math.round(total * 1000) / 10,
      reasons: [],
      warnings: buildWarnings(offer),
      badges: [],
    };
  });

  for (const result of scored) {
    result.reasons = buildReasons(result, result.scores, stats, query);
  }

  scored.sort((a, b) => b.score - a.score || a.price.total - b.price.total);

  // Badges are assigned after sorting so "Best overall" always lands on the top row.
  const cheapest = scored.reduce((best, offer) => (offer.price.total < best.price.total ? offer : best));
  const fastest = scored.reduce((best, offer) => (offer.totalDurationMinutes < best.totalDurationMinutes ? offer : best));

  // One offer can hold several badges — the cheapest is often also the slowest, but when the
  // cheapest is also the fastest it deserves to say so.
  scored[0].badges.push('Best overall');
  cheapest.badges.push('Cheapest');
  fastest.badges.push('Fastest');

  // When the best option isn't the cheapest, say what the extra money buys. This is the single
  // most useful line on the page and it is why the ranking exists at all.
  const best = scored[0];
  if (best !== cheapest) {
    const extra = best.price.total - cheapest.price.total;
    const saved = cheapest.totalDurationMinutes - best.totalDurationMinutes;
    const fewerStops = Math.max(...cheapest.legs.map((leg) => leg.stops)) - Math.max(...best.legs.map((leg) => leg.stops));

    const gains = [];
    if (saved >= 30) gains.push(`${formatMinutes(saved)} shorter`);
    if (fewerStops > 0) gains.push(fewerStops === 1 ? 'one fewer stop' : `${fewerStops} fewer stops`);
    if (best.scores.comfort > cheapest.scores.comfort + 0.2) gains.push('a much better connection');
    if (best.scores.timing > cheapest.scores.timing + 0.2) gains.push('far better timing');

    best.tradeoff = gains.length > 0
      ? `${formatMoney(extra, stats.currency)} more than the cheapest, for ${listPhrase(gains)}.`
      : `${formatMoney(extra, stats.currency)} more than the cheapest.`;
  }

  return { results: scored, stats };
}

function listPhrase(items) {
  if (items.length === 1) return items[0];
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}
