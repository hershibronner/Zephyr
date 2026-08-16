/**
 * The search pipeline: parse -> fetch -> normalise -> filter -> rank.
 *
 * Kept apart from the HTTP layer so the whole thing can be driven from a test or a script without
 * starting a server.
 */

import { parseQuery, describeQuery } from './parse.mjs';
import { AmadeusClient, candidateDates } from './amadeus.mjs';
import { fromAmadeus } from './offers.mjs';
import { mockOffers } from './mock.mjs';
import { applyFilters } from './filter.mjs';
import { rankOffers } from './rank.mjs';
import { describeCode } from './airports.mjs';

/** How many ranked results the UI gets. Enough to scroll, not enough to drown in. */
const RESULT_LIMIT = 25;

export function createSearcher({ amadeus, anthropicApiKey, now } = {}) {
  const today = () => (now ? now() : new Date().toISOString().slice(0, 10));

  /** Pull offers for one leg from whichever provider is configured. */
  async function fetchOffers(query, dates) {
    const { filters } = query;
    const travellers = filters.adults + filters.children;
    const origin = query.origin.iata;
    const destination = query.destination.iata;

    if (!amadeus?.configured) {
      return {
        provider: 'mock',
        offers: dates.flatMap((date) =>
          mockOffers({
            origin,
            destination,
            date,
            travellers,
            cabin: filters.cabin,
            currency: filters.currency,
          })),
        notices: [],
      };
    }

    const notices = [];
    const settled = await Promise.allSettled(
      dates.map((date) =>
        amadeus.searchOnce({
          origin,
          destination,
          departureDate: date,
          returnDate: query.tripType === 'round_trip' ? query.inbound?.date ?? undefined : undefined,
          adults: filters.adults,
          children: filters.children,
          infants: filters.infants,
          cabin: filters.cabin,
          // Ask the API for nonstops only when that is a hard requirement — otherwise we want the
          // connecting options in the set so the ranking can weigh them.
          nonStop: filters.maxStops === 0,
          currency: filters.currency,
        })));

    const offers = [];
    for (const [index, outcome] of settled.entries()) {
      if (outcome.status === 'fulfilled') {
        offers.push(...fromAmadeus(outcome.value, travellers));
      } else {
        notices.push(`No results for ${dates[index]}: ${outcome.reason.message}`);
      }
    }

    // A flexible search that found nothing anywhere is worth saying out loud rather than
    // rendering as a blank page.
    if (offers.length === 0 && notices.length === 0) {
      notices.push('The provider returned no itineraries for these dates.');
    }

    return { provider: 'amadeus', offers, notices };
  }

  return {
    /** Parse only — used by the UI to show its reading of the request before results land. */
    async parse(text) {
      return parseQuery(text, { today: today(), apiKey: anthropicApiKey });
    },

    /**
     * Full search. Returns everything the UI needs to explain itself: the parsed query, which
     * constraints were applied or relaxed, the ranked results, and how the set compares.
     */
    async search(text) {
      const startedAt = Date.now();
      const { query, parser } = await parseQuery(text, { today: today(), apiKey: anthropicApiKey });

      const missing = [];
      if (!query.origin.iata) missing.push('where you are flying from');
      if (!query.destination.iata) missing.push('where you are flying to');

      if (missing.length > 0) {
        return {
          ok: false,
          query,
          parser,
          needs: missing,
          message: `I could not work out ${missing.join(' or ')}. Try naming the cities, for example "Boston to Lisbon".`,
          results: [],
        };
      }

      const dates = candidateDates(query.outbound, { today: today() });
      const { provider, offers, notices } = await fetchOffers(query, dates);

      if (offers.length === 0) {
        return {
          ok: true,
          query,
          parser,
          provider,
          dates,
          results: [],
          stats: null,
          filters: { applied: [], relaxed: [], droppedCount: 0 },
          notices,
          message: `No flights came back for ${describeCode(query.origin.iata)} to ${describeCode(query.destination.iata)} on ${dates.join(', ')}.`,
          elapsedMs: Date.now() - startedAt,
        };
      }

      const filtered = applyFilters(offers, query);
      const { results, stats } = rankOffers(filtered.offers, query);

      return {
        ok: true,
        query,
        parser,
        provider,
        dates,
        interpretation: query.interpretation || describeQuery(query),
        route: {
          from: describeCode(query.origin.iata),
          to: describeCode(query.destination.iata),
        },
        searched: offers.length,
        filters: {
          applied: filtered.applied,
          relaxed: filtered.relaxed,
          droppedCount: filtered.droppedCount,
        },
        stats,
        results: results.slice(0, RESULT_LIMIT),
        notices,
        elapsedMs: Date.now() - startedAt,
      };
    },
  };
}

export { AmadeusClient };
