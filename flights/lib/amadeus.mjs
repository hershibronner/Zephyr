/**
 * Amadeus Self-Service client — OAuth2 token handling, flight offers, and location lookup.
 *
 * Sign up at https://developers.amadeus.com, create an app, and set:
 *
 *   AMADEUS_CLIENT_ID / AMADEUS_CLIENT_SECRET
 *   AMADEUS_ENV=test (default) or production
 *
 * The test environment serves a real API against a limited cached dataset: routes between major
 * cities work, obscure ones come back empty. That is a property of the sandbox, not a bug — the
 * same code hits live inventory once you switch AMADEUS_ENV to production.
 *
 * The date-flexible search below fans out one request per candidate date rather than using the
 * cheapest-date endpoint, because that endpoint isn't available on every account tier and its
 * prices are indicative rather than bookable. Requests run concurrently and are capped, so a
 * "sometime in March" search doesn't turn into thirty-one serial round trips.
 */

const HOSTS = {
  test: 'https://test.api.amadeus.com',
  production: 'https://api.amadeus.com',
};

/** Fanning out over a flexible date window is useful; doing it 31 times is not. */
const MAX_DATE_FANOUT = 7;

export class AmadeusClient {
  constructor({ clientId, clientSecret, env = 'test' } = {}) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.host = HOSTS[env] ?? HOSTS.test;
    this.token = null;
    this.tokenExpiresAt = 0;
    this.pendingToken = null;
  }

  get configured() {
    return Boolean(this.clientId && this.clientSecret);
  }

  /**
   * Tokens last ~30 minutes. We refresh a minute early, and share one in-flight request so a burst
   * of concurrent searches doesn't mint a token each.
   */
  async accessToken() {
    if (this.token && Date.now() < this.tokenExpiresAt) return this.token;
    if (this.pendingToken) return this.pendingToken;

    this.pendingToken = (async () => {
      const response = await fetch(`${this.host}/v1/security/oauth2/token`, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
          grant_type: 'client_credentials',
          client_id: this.clientId,
          client_secret: this.clientSecret,
        }),
      });

      if (!response.ok) {
        throw new Error(`Amadeus auth failed (${response.status}): ${(await response.text()).slice(0, 200)}`);
      }

      const payload = await response.json();
      this.token = payload.access_token;
      this.tokenExpiresAt = Date.now() + Math.max(0, (payload.expires_in ?? 1800) - 60) * 1000;
      return this.token;
    })();

    try {
      return await this.pendingToken;
    } finally {
      this.pendingToken = null;
    }
  }

  async get(path, params) {
    const token = await this.accessToken();
    const url = new URL(path, this.host);
    for (const [key, value] of Object.entries(params ?? {})) {
      if (value != null && value !== '') url.searchParams.set(key, String(value));
    }

    const response = await fetch(url, { headers: { authorization: `Bearer ${token}` } });

    if (response.status === 401) {
      // Token rejected mid-flight (rotated key, clock skew). One clean retry.
      this.token = null;
      this.tokenExpiresAt = 0;
      const retryToken = await this.accessToken();
      const retry = await fetch(url, { headers: { authorization: `Bearer ${retryToken}` } });
      if (!retry.ok) throw await amadeusError(retry);
      return retry.json();
    }

    if (!response.ok) throw await amadeusError(response);
    return response.json();
  }

  /** Resolve free text to airport/city codes — the escape hatch when the parser wasn't sure. */
  async findLocations(keyword, limit = 5) {
    const payload = await this.get('/v1/reference-data/locations', {
      subType: 'AIRPORT,CITY',
      keyword,
      'page[limit]': limit,
    });
    return (payload.data ?? []).map((entry) => ({
      code: entry.iataCode,
      name: entry.name,
      city: entry.address?.cityName ?? null,
      country: entry.address?.countryCode ?? null,
      type: entry.subType,
    }));
  }

  /**
   * One flight-offers request. `maxPrice` is deliberately not forwarded: Amadeus applies it per
   * traveller, while travellers state budgets for the whole trip. Filtering locally against the
   * grand total keeps the meaning the traveller intended.
   */
  async searchOnce({ origin, destination, departureDate, returnDate, adults, children, infants, cabin, nonStop, currency, max = 50 }) {
    return this.get('/v2/shopping/flight-offers', {
      originLocationCode: origin,
      destinationLocationCode: destination,
      departureDate,
      returnDate,
      adults,
      children: children || undefined,
      infants: infants || undefined,
      travelClass: cabin,
      nonStop: nonStop ? 'true' : undefined,
      currencyCode: currency,
      max,
    });
  }
}

async function amadeusError(response) {
  const text = await response.text();
  let detail = text.slice(0, 300);
  try {
    const parsed = JSON.parse(text);
    detail = parsed.errors?.map((entry) => entry.detail ?? entry.title).join('; ') ?? detail;
  } catch {
    // Not JSON — the raw prefix is the best detail we have.
  }
  const error = new Error(`Amadeus request failed (${response.status}): ${detail}`);
  error.status = response.status;
  return error;
}

/** The candidate departure dates for a query: one exact date, or a sampled flexible window. */
export function candidateDates(window, { today }) {
  if (window?.date) return [window.date];

  const earliest = window?.earliestDate;
  const latest = window?.latestDate;
  if (!earliest) return [today];

  const dates = [];
  let cursor = earliest < today ? today : earliest;
  const end = latest ?? cursor;
  while (cursor <= end && dates.length < 64) {
    dates.push(cursor);
    cursor = new Date(Date.parse(`${cursor}T00:00:00Z`) + 86_400_000).toISOString().slice(0, 10);
  }
  if (dates.length === 0) return [today];
  if (dates.length <= MAX_DATE_FANOUT) return dates;

  // Sample evenly across the window instead of taking the first N — the cheap day is as likely to
  // be at the end of a flexible range as the start.
  const step = (dates.length - 1) / (MAX_DATE_FANOUT - 1);
  return Array.from({ length: MAX_DATE_FANOUT }, (_, index) => dates[Math.round(index * step)]);
}
