/**
 * A small airport/metro table.
 *
 * This is NOT the resolver — Claude resolves "the cheapest way out of the Bay Area" far better
 * than a lookup table ever will, and Amadeus has a full reference-data endpoint behind it. This
 * table exists so the rule-based fallback parser still works with no API keys at all, and so the
 * UI can print "New York (JFK)" instead of a bare code.
 *
 * Metro codes (NYC, LON, PAR, TYO...) search every airport in the city at once, which is usually
 * what someone means and is often where the cheap fare is hiding.
 */

export const AIRPORTS = {
  // North America
  JFK: { city: 'New York', country: 'US', name: 'John F. Kennedy' },
  LGA: { city: 'New York', country: 'US', name: 'LaGuardia' },
  EWR: { city: 'New York', country: 'US', name: 'Newark' },
  NYC: { city: 'New York', country: 'US', name: 'All airports', metro: true },
  LAX: { city: 'Los Angeles', country: 'US', name: 'Los Angeles Intl' },
  SFO: { city: 'San Francisco', country: 'US', name: 'San Francisco Intl' },
  OAK: { city: 'Oakland', country: 'US', name: 'Oakland Intl' },
  SJC: { city: 'San Jose', country: 'US', name: 'Norman Y. Mineta' },
  ORD: { city: 'Chicago', country: 'US', name: "O'Hare" },
  MDW: { city: 'Chicago', country: 'US', name: 'Midway' },
  CHI: { city: 'Chicago', country: 'US', name: 'All airports', metro: true },
  MIA: { city: 'Miami', country: 'US', name: 'Miami Intl' },
  FLL: { city: 'Fort Lauderdale', country: 'US', name: 'Hollywood Intl' },
  BOS: { city: 'Boston', country: 'US', name: 'Logan' },
  SEA: { city: 'Seattle', country: 'US', name: 'Tacoma Intl' },
  DEN: { city: 'Denver', country: 'US', name: 'Denver Intl' },
  ATL: { city: 'Atlanta', country: 'US', name: 'Hartsfield-Jackson' },
  DFW: { city: 'Dallas', country: 'US', name: 'Fort Worth Intl' },
  IAH: { city: 'Houston', country: 'US', name: 'George Bush' },
  PHX: { city: 'Phoenix', country: 'US', name: 'Sky Harbor' },
  LAS: { city: 'Las Vegas', country: 'US', name: 'Harry Reid' },
  MCO: { city: 'Orlando', country: 'US', name: 'Orlando Intl' },
  IAD: { city: 'Washington', country: 'US', name: 'Dulles' },
  DCA: { city: 'Washington', country: 'US', name: 'Reagan National' },
  WAS: { city: 'Washington', country: 'US', name: 'All airports', metro: true },
  PHL: { city: 'Philadelphia', country: 'US', name: 'Philadelphia Intl' },
  SAN: { city: 'San Diego', country: 'US', name: 'San Diego Intl' },
  AUS: { city: 'Austin', country: 'US', name: 'Bergstrom' },
  YYZ: { city: 'Toronto', country: 'CA', name: 'Pearson' },
  YUL: { city: 'Montreal', country: 'CA', name: 'Trudeau' },
  YVR: { city: 'Vancouver', country: 'CA', name: 'Vancouver Intl' },
  MEX: { city: 'Mexico City', country: 'MX', name: 'Benito Juárez' },
  CUN: { city: 'Cancún', country: 'MX', name: 'Cancún Intl' },

  // Europe
  LHR: { city: 'London', country: 'GB', name: 'Heathrow' },
  LGW: { city: 'London', country: 'GB', name: 'Gatwick' },
  STN: { city: 'London', country: 'GB', name: 'Stansted' },
  LON: { city: 'London', country: 'GB', name: 'All airports', metro: true },
  CDG: { city: 'Paris', country: 'FR', name: 'Charles de Gaulle' },
  ORY: { city: 'Paris', country: 'FR', name: 'Orly' },
  PAR: { city: 'Paris', country: 'FR', name: 'All airports', metro: true },
  AMS: { city: 'Amsterdam', country: 'NL', name: 'Schiphol' },
  FRA: { city: 'Frankfurt', country: 'DE', name: 'Frankfurt Intl' },
  MUC: { city: 'Munich', country: 'DE', name: 'Munich Intl' },
  BER: { city: 'Berlin', country: 'DE', name: 'Brandenburg' },
  MAD: { city: 'Madrid', country: 'ES', name: 'Barajas' },
  BCN: { city: 'Barcelona', country: 'ES', name: 'El Prat' },
  FCO: { city: 'Rome', country: 'IT', name: 'Fiumicino' },
  MXP: { city: 'Milan', country: 'IT', name: 'Malpensa' },
  ZRH: { city: 'Zurich', country: 'CH', name: 'Zurich Intl' },
  VIE: { city: 'Vienna', country: 'AT', name: 'Vienna Intl' },
  CPH: { city: 'Copenhagen', country: 'DK', name: 'Kastrup' },
  ARN: { city: 'Stockholm', country: 'SE', name: 'Arlanda' },
  OSL: { city: 'Oslo', country: 'NO', name: 'Gardermoen' },
  HEL: { city: 'Helsinki', country: 'FI', name: 'Vantaa' },
  DUB: { city: 'Dublin', country: 'IE', name: 'Dublin Intl' },
  LIS: { city: 'Lisbon', country: 'PT', name: 'Humberto Delgado' },
  ATH: { city: 'Athens', country: 'GR', name: 'Eleftherios Venizelos' },
  IST: { city: 'Istanbul', country: 'TR', name: 'Istanbul Airport' },
  WAW: { city: 'Warsaw', country: 'PL', name: 'Chopin' },
  PRG: { city: 'Prague', country: 'CZ', name: 'Václav Havel' },

  // Middle East, Africa, Asia, Oceania
  DXB: { city: 'Dubai', country: 'AE', name: 'Dubai Intl' },
  AUH: { city: 'Abu Dhabi', country: 'AE', name: 'Zayed Intl' },
  DOH: { city: 'Doha', country: 'QA', name: 'Hamad Intl' },
  TLV: { city: 'Tel Aviv', country: 'IL', name: 'Ben Gurion' },
  CAI: { city: 'Cairo', country: 'EG', name: 'Cairo Intl' },
  JNB: { city: 'Johannesburg', country: 'ZA', name: 'O. R. Tambo' },
  CPT: { city: 'Cape Town', country: 'ZA', name: 'Cape Town Intl' },
  NRT: { city: 'Tokyo', country: 'JP', name: 'Narita' },
  HND: { city: 'Tokyo', country: 'JP', name: 'Haneda' },
  TYO: { city: 'Tokyo', country: 'JP', name: 'All airports', metro: true },
  ICN: { city: 'Seoul', country: 'KR', name: 'Incheon' },
  PEK: { city: 'Beijing', country: 'CN', name: 'Capital Intl' },
  PVG: { city: 'Shanghai', country: 'CN', name: 'Pudong' },
  HKG: { city: 'Hong Kong', country: 'HK', name: 'Hong Kong Intl' },
  SIN: { city: 'Singapore', country: 'SG', name: 'Changi' },
  BKK: { city: 'Bangkok', country: 'TH', name: 'Suvarnabhumi' },
  KUL: { city: 'Kuala Lumpur', country: 'MY', name: 'KLIA' },
  DEL: { city: 'Delhi', country: 'IN', name: 'Indira Gandhi' },
  BOM: { city: 'Mumbai', country: 'IN', name: 'Chhatrapati Shivaji' },
  SYD: { city: 'Sydney', country: 'AU', name: 'Kingsford Smith' },
  MEL: { city: 'Melbourne', country: 'AU', name: 'Tullamarine' },
  AKL: { city: 'Auckland', country: 'NZ', name: 'Auckland Intl' },
  GRU: { city: 'São Paulo', country: 'BR', name: 'Guarulhos' },
  GIG: { city: 'Rio de Janeiro', country: 'BR', name: 'Galeão' },
  EZE: { city: 'Buenos Aires', country: 'AR', name: 'Ezeiza' },
  BOG: { city: 'Bogotá', country: 'CO', name: 'El Dorado' },
  LIM: { city: 'Lima', country: 'PE', name: 'Jorge Chávez' },
  SCL: { city: 'Santiago', country: 'CL', name: 'Arturo Merino Benítez' },
};

/** Prefer the metro code when a city has one — it searches every airport at once. */
const CITY_TO_CODE = (() => {
  const map = new Map();
  for (const [code, info] of Object.entries(AIRPORTS)) {
    const key = info.city.toLowerCase();
    if (info.metro || !map.has(key)) map.set(key, code);
  }
  // Common shorthands people actually type.
  map.set('nyc', 'NYC');
  map.set('new york city', 'NYC');
  map.set('sf', 'SFO');
  map.set('bay area', 'SFO');
  map.set('la', 'LAX');
  map.set('vegas', 'LAS');
  map.set('dc', 'WAS');
  map.set('the city', 'NYC');
  return map;
})();

/** Resolve free text to an IATA code, or null when we genuinely don't know. */
export function resolveCode(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim();
  if (!trimmed) return null;

  const upper = trimmed.toUpperCase();
  if (AIRPORTS[upper]) return upper;

  return CITY_TO_CODE.get(trimmed.toLowerCase()) ?? null;
}

/** "New York (JFK)" for display; falls back to the bare code for anything not in the table. */
export function describeCode(code) {
  const info = AIRPORTS[code];
  if (!info) return code ?? '';
  return `${info.city} (${code})`;
}

/**
 * A coarse geography, used only by the synthetic provider so its demo data is plausible.
 *
 * Real flight times come from the provider — this exists so that a keyless demo doesn't quote a
 * three-hour London to Tokyo, which would tell the viewer nothing works. Country to region is a
 * far shorter table than 90 coordinate pairs and is accurate enough for banding.
 */
const COUNTRY_REGION = {
  US: 'NA', CA: 'NA', MX: 'NA',
  BR: 'SA', AR: 'SA', CO: 'SA', PE: 'SA', CL: 'SA',
  GB: 'EU', FR: 'EU', NL: 'EU', DE: 'EU', ES: 'EU', IT: 'EU', CH: 'EU', AT: 'EU',
  DK: 'EU', SE: 'EU', NO: 'EU', FI: 'EU', IE: 'EU', PT: 'EU', GR: 'EU', PL: 'EU',
  CZ: 'EU', TR: 'EU',
  AE: 'ME', QA: 'ME', IL: 'ME',
  EG: 'AF', ZA: 'AF',
  JP: 'AS', KR: 'AS', CN: 'AS', HK: 'AS', SG: 'AS', TH: 'AS', MY: 'AS', IN: 'AS',
  AU: 'OC', NZ: 'OC',
};

/** Typical one-way flying time in minutes between two regions, as [floor, ceiling]. */
const REGION_MINUTES = {
  'NA|NA': [70, 330], 'EU|EU': [60, 240], 'AS|AS': [80, 400], 'SA|SA': [70, 300],
  'ME|ME': [60, 150], 'AF|AF': [80, 320], 'OC|OC': [70, 260],
  'EU|NA': [420, 660], 'NA|SA': [400, 700], 'AS|NA': [660, 840], 'ME|NA': [720, 840],
  'AF|NA': [780, 900], 'NA|OC': [780, 960],
  'EU|ME': [240, 360], 'AS|EU': [540, 780], 'AF|EU': [360, 720], 'EU|SA': [660, 780],
  'EU|OC': [1140, 1320],
  'AS|ME': [300, 540], 'AF|ME': [300, 540], 'ME|OC': [780, 960],
  'AS|OC': [480, 660], 'AF|AS': [600, 780], 'AF|SA': [480, 660], 'OC|SA': [900, 1080],
  'AF|OC': [660, 900],
};

function regionOf(code) {
  return COUNTRY_REGION[AIRPORTS[code]?.country] ?? null;
}

/**
 * A plausible flying time for a route. `spread` is a stable 0-1 value from the caller, so the same
 * route always lands in the same place within its band.
 */
export function typicalFlightMinutes(origin, destination, spread) {
  const from = regionOf(origin);
  const to = regionOf(destination);

  // Unknown airport: assume a medium-haul international hop rather than pretending to know.
  if (!from || !to) return 180 + Math.round(spread * 420);

  const sameCountry = AIRPORTS[origin]?.country === AIRPORTS[destination]?.country;
  const band = REGION_MINUTES[[from, to].sort().join('|')] ?? [180, 600];
  const [floor, ceiling] = sameCountry ? [band[0], Math.min(band[1], 400)] : band;

  return Math.round(floor + spread * (ceiling - floor));
}

export const AIRLINES = {
  AA: 'American', DL: 'Delta', UA: 'United', WN: 'Southwest', B6: 'JetBlue',
  AS: 'Alaska', NK: 'Spirit', F9: 'Frontier', AC: 'Air Canada', BA: 'British Airways',
  VS: 'Virgin Atlantic', AF: 'Air France', KL: 'KLM', LH: 'Lufthansa', LX: 'SWISS',
  OS: 'Austrian', IB: 'Iberia', AZ: 'ITA Airways', TP: 'TAP Portugal', SK: 'SAS',
  AY: 'Finnair', EI: 'Aer Lingus', FR: 'Ryanair', U2: 'easyJet', TK: 'Turkish',
  EK: 'Emirates', QR: 'Qatar Airways', EY: 'Etihad', SQ: 'Singapore Airlines',
  CX: 'Cathay Pacific', JL: 'Japan Airlines', NH: 'ANA', KE: 'Korean Air',
  QF: 'Qantas', NZ: 'Air New Zealand', LA: 'LATAM', AM: 'Aeroméxico', ET: 'Ethiopian',
};

export function describeAirline(code, dictionary = {}) {
  return dictionary[code] ?? AIRLINES[code] ?? code;
}
