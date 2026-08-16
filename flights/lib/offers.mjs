/**
 * The internal offer shape, and the translation from Amadeus into it.
 *
 * Every provider gets normalised to this shape before filtering or ranking runs, so adding a
 * second provider later means writing one adapter — not touching the scoring logic. The mock
 * provider produces this shape directly.
 *
 *   Offer {
 *     id, source, price: {total, currency, perTraveller},
 *     legs: [Leg], carriers: [code], seatsRemaining
 *   }
 *   Leg { durationMinutes, stops, segments: [Segment], layovers: [Layover] }
 *   Segment { from, to, departAt, arriveAt, carrier, flightNumber, durationMinutes }
 *   Layover { airport, minutes, changesAirport }
 */

import { isoDurationToMinutes, localMinutes } from './util.mjs';

/** Layovers are derived, not given: the gap between one segment landing and the next taking off. */
function deriveLayovers(segments) {
  const layovers = [];
  for (let index = 1; index < segments.length; index += 1) {
    const previous = segments[index - 1];
    const next = segments[index];
    const arrive = Date.parse(previous.arriveAt);
    const depart = Date.parse(next.departAt);
    layovers.push({
      airport: previous.to,
      minutes: Number.isNaN(arrive) || Number.isNaN(depart) ? 0 : Math.round((depart - arrive) / 60_000),
      // A connection that lands at one airport and leaves from another is a taxi ride, not a
      // layover, and the printed connection time is a lie.
      changesAirport: previous.to !== next.from,
    });
  }
  return layovers;
}

export function buildLeg(segments) {
  const durationMinutes = segments.reduce((total, segment) => total + segment.durationMinutes, 0);
  const layovers = deriveLayovers(segments);
  const layoverMinutes = layovers.reduce((total, layover) => total + layover.minutes, 0);

  return {
    segments,
    layovers,
    stops: segments.length - 1,
    // Elapsed door to door, which is what a traveller feels — not the sum of the flying times.
    durationMinutes: durationMinutes + layoverMinutes,
    departAt: segments[0].departAt,
    arriveAt: segments[segments.length - 1].arriveAt,
    from: segments[0].from,
    to: segments[segments.length - 1].to,
  };
}

/** Amadeus flight-offers -> Offer[]. `dictionaries` carries carrier names for display. */
export function fromAmadeus(payload, travellers = 1) {
  const offers = payload?.data ?? [];

  return offers.map((offer) => {
    const legs = (offer.itineraries ?? []).map((itinerary) => {
      const segments = (itinerary.segments ?? []).map((segment) => ({
        from: segment.departure?.iataCode ?? '',
        to: segment.arrival?.iataCode ?? '',
        departAt: segment.departure?.at ?? '',
        arriveAt: segment.arrival?.at ?? '',
        carrier: segment.carrierCode ?? '',
        flightNumber: `${segment.carrierCode ?? ''}${segment.number ?? ''}`,
        aircraft: segment.aircraft?.code ?? null,
        durationMinutes: isoDurationToMinutes(segment.duration),
      }));

      const leg = buildLeg(segments);
      // Amadeus gives the itinerary duration directly; prefer it when present, since it accounts
      // for the date line and other things a naive sum gets wrong.
      const stated = isoDurationToMinutes(itinerary.duration);
      if (stated > 0) leg.durationMinutes = stated;
      return leg;
    });

    const total = Number(offer.price?.grandTotal ?? offer.price?.total ?? 0);

    return {
      id: `amadeus-${offer.id}`,
      source: 'amadeus',
      price: {
        total,
        currency: offer.price?.currency ?? 'USD',
        perTraveller: travellers > 0 ? total / travellers : total,
      },
      legs,
      carriers: [...new Set(legs.flatMap((leg) => leg.segments.map((segment) => segment.carrier)))],
      seatsRemaining: offer.numberOfBookableSeats ?? null,
      cabin: offer.travelerPricings?.[0]?.fareDetailsBySegment?.[0]?.cabin ?? null,
      raw: undefined,
    };
  });
}

/** True when a leg departs late and lands early the next morning — the classic red-eye. */
export function isRedEye(leg) {
  const depart = localMinutes(leg.departAt);
  const arrive = localMinutes(leg.arriveAt);
  if (depart == null || arrive == null) return false;
  const crossesNight = depart >= 21 * 60 || depart <= 2 * 60;
  const landsEarly = arrive <= 8 * 60;
  const overnight = leg.durationMinutes >= 240;
  return crossesNight && landsEarly && overnight;
}
