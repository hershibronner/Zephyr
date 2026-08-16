/**
 * Small helpers shared across the flight search. Deliberately dependency-free.
 *
 * A note on time zones: Amadeus returns segment times as *local* wall-clock strings with no
 * offset ("2026-08-16T16:35:00"). That is exactly what a traveller means by "after 4pm", so we
 * compare those strings directly rather than converting to UTC — converting would silently turn
 * "leave after 4pm" into a different question.
 */

/** Parse an ISO-8601 duration ("PT5H30M") into minutes. */
export function isoDurationToMinutes(iso) {
  if (typeof iso !== 'string') return 0;
  const match = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?/.exec(iso);
  if (!match) return 0;
  const [, days, hours, minutes] = match;
  return Number(days ?? 0) * 1440 + Number(hours ?? 0) * 60 + Number(minutes ?? 0);
}

/** "5h 30m", or "45m" when under an hour. */
export function formatMinutes(total) {
  const minutes = Math.max(0, Math.round(total));
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest}m`;
  if (rest === 0) return `${hours}h`;
  return `${hours}h ${rest}m`;
}

/** Minutes past local midnight for an Amadeus-style "2026-08-16T16:35:00" stamp. */
export function localMinutes(stamp) {
  const match = /T(\d{2}):(\d{2})/.exec(stamp ?? '');
  if (!match) return null;
  return Number(match[1]) * 60 + Number(match[2]);
}

/** The "2026-08-16" part of a local stamp. */
export function localDate(stamp) {
  return typeof stamp === 'string' ? stamp.slice(0, 10) : null;
}

/** "16:35" for display. */
export function localClock(stamp) {
  const minutes = localMinutes(stamp);
  if (minutes == null) return '';
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}

/** Minutes since local midnight from "16:00" / "4pm" / "16" — used for parsed time constraints. */
export function clockToMinutes(text) {
  if (typeof text !== 'string') return null;
  const trimmed = text.trim().toLowerCase();

  const withMeridiem = /^(\d{1,2})(?::(\d{2}))?\s*(am|pm)$/.exec(trimmed);
  if (withMeridiem) {
    let hour = Number(withMeridiem[1]) % 12;
    if (withMeridiem[3] === 'pm') hour += 12;
    return hour * 60 + Number(withMeridiem[2] ?? 0);
  }

  const twentyFour = /^(\d{1,2}):(\d{2})$/.exec(trimmed);
  if (twentyFour) {
    const hour = Number(twentyFour[1]);
    const minute = Number(twentyFour[2]);
    if (hour > 23 || minute > 59) return null;
    return hour * 60 + minute;
  }

  return null;
}

/** Days between two "YYYY-MM-DD" strings, treated as calendar dates (no DST games). */
export function daysBetween(from, to) {
  const start = Date.parse(`${from}T00:00:00Z`);
  const end = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.round((end - start) / 86_400_000);
}

/** "YYYY-MM-DD" `days` after `date`. */
export function addDays(date, days) {
  const base = Date.parse(`${date}T00:00:00Z`);
  if (Number.isNaN(base)) return null;
  return new Date(base + days * 86_400_000).toISOString().slice(0, 10);
}

/** Clamp to [0, 1]; every scoring component returns a value in that range. */
export function unit(value) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(1, Math.max(0, value));
}
