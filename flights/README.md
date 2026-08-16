# Skylark

A flight search you talk to in your own words.

> *"I wanna travel from New York to Lisbon on Sun Aug 16, only after 4PM, and my budget is $600."*

Skylark reads that, turns it into a real search, and then does the part every other flight site
skips: it works out which option is actually **best**, not just which number is smallest.

```bash
node flights/server.mjs      # http://localhost:5174 — runs with no keys and no npm install
node --test "flights/test/*.test.mjs"
```

It runs out of the box with a synthetic fare provider and a keyword parser, so you can see the whole
thing work before signing up for anything. Add keys to make it real.

---

## Why "best" is a different question from "cheapest"

Sorting by price is easy and mostly wrong. The $180 fare with a 35-minute connection in Charles de
Gaulle and a 2:40am arrival is not a better trip than the $215 nonstop — it just has a smaller
number attached. So every option gets scored on five axes, each normalised against the rest of the
results:

| Axis | What it measures |
|---|---|
| `price` | The cheapest fare in the set scores 1; twice that scores 0.5 |
| `duration` | Same shape, against the fastest option — elapsed door to door, not flying time |
| `stops` | Nonstop 1, one stop 0.65, two 0.35 |
| `timing` | Civilised departure and arrival hours, plus fit against the times you stated |
| `comfort` | Layover length, airport changes during a connection, red-eyes, staying on one airline |

Those five are combined using **weights read from what you actually said**. "Cheapest possible"
pushes price to 1.0 and everything else down. "I need to be in the office by 9" pushes timing to
1.0. Nobody has to fill in a preferences form.

Every result then carries its reasons and its warnings, because a ranking you can't interrogate is
one you shouldn't trust:

```
Best overall · Cheapest        $407
17:30 → 07:55   NYC → LIS · 14h 25m · 1 stop
  + Cheapest of everything we found
  + Single airline the whole way — connections are protected
  ! 4h 33m sitting in Charlotte (CLT)
```

And when the top pick isn't the cheapest one, it says what the extra buys:

> *$110 more than the cheapest, for 3h 15m shorter and one fewer stop.*

---

## Filters vs priorities

The distinction the parser cares about most:

- A **filter** removes options. *"Nonstop."* *"Under $600."* *"I have to land before 6pm."*
- A **priority** ranks them. *"I'd rather not have a long layover."* *"Cheap would be nice."*

Soft wishes put in the filter bucket are what produce empty result pages, so anything ambiguous
becomes a priority.

### When nothing matches everything

An empty page is the worst possible answer — it tells you nothing. So when the filters can't all be
satisfied, Skylark gives up the smallest, least important set of them that lets something through,
and says exactly what it dropped.

Getting this right is subtler than it looks. Ask for *"nonstop under $40"* and the obvious approach
— drop constraints one at a time from the least important end — drops "nonstop" first, finds it
didn't help, drops the budget too, and serves you a connecting flight when perfectly good nonstops
were sitting right there. Skylark instead picks the **optimal** set to keep: every offer already
tells you which constraints it satisfies, so the best achievable outcome is just the most valuable
of those sets. It's a single pass, and it can't make that mistake.

Constraints are ranked so that a more important one is never sacrificed to rescue a lesser one.
From first-to-give to most-protected:

```
layover length → red-eyes → trip length → airlines → stops → budget → departure time → arrival time
```

Times sit above budget deliberately. *"Only after 4PM"* usually means you physically cannot leave
earlier — a cheap 10am flight is not a worse answer, it's no answer. A fare $40 over budget is at
least a flight you could take.

---

## Setup

Everything is optional. Put keys in `flights/.env` (see `.env.example`) or export them.

### AI search — `ANTHROPIC_API_KEY`

Turns free text into a structured query using Claude with a JSON schema, so the result is always the
same shape — no prose to regex out of, no retry-until-it-parses loop. Get a key at
[console.anthropic.com](https://console.anthropic.com).

Without it, a keyword parser handles the common shapes (`X to Y`, `on Aug 16`, `after 4pm`,
`under $600`, `nonstop`, `2 adults`). It's a fallback, not a peer — it will not understand
*"somewhere warm in February for under a grand"*.

The call runs at `effort: "low"` — extracting a search query is not reasoning-heavy work and this
sits in front of a search box — with the system prompt cached and only the date and query varying
per request.

### Real fares — `AMADEUS_CLIENT_ID` / `AMADEUS_CLIENT_SECRET`

1. Sign up at [developers.amadeus.com](https://developers.amadeus.com) (free, no approval wait)
2. Create an app, copy the API Key and API Secret
3. `AMADEUS_ENV=test` (default) or `production`

The **test** environment is a real API against a limited cached dataset: major city pairs work,
obscure routes come back empty. That's the sandbox, not a bug — the same code hits live inventory
when you switch to `production`.

Without credentials, a labelled synthetic provider generates fares. It's seeded from the route and
date, so results are stable, and it's grounded in a coarse geography so a long haul actually looks
like one. Everywhere it surfaces, it's marked **demo fares — not bookable**.

---

## Layout

```
flights/
├─ server.mjs          zero-dependency HTTP server + JSON API
├─ lib/
│  ├─ parse.mjs        natural language → structured query (Claude, or keyword fallback)
│  ├─ amadeus.mjs      OAuth2, flight offers, location lookup, flexible-date fan-out
│  ├─ offers.mjs       the internal offer shape; Amadeus → internal translation
│  ├─ filter.mjs       hard constraints + optimal relaxation
│  ├─ rank.mjs         scoring, badges, reasons, warnings
│  ├─ mock.mjs         deterministic synthetic provider
│  ├─ airports.mjs     IATA table, metro codes, coarse geography for the mock
│  ├─ search.mjs       the pipeline, independent of HTTP
│  └─ util.mjs         durations, local wall-clock times
├─ public/             the UI — vanilla, no build step
└─ test/               node:test, no network required
```

Adding another fare provider means writing one adapter into the shape in `offers.mjs`. Nothing in
the filtering or ranging logic knows where an offer came from.

## API

| Route | |
|---|---|
| `POST /api/search` | `{"query": "..."}` → parsed query, applied/relaxed filters, ranked results, set statistics |
| `GET /api/health` | which parser and which fare source are actually wired up |
| `GET /api/locations?q=` | airport/city lookup (needs Amadeus credentials) |

## Notes on time

Amadeus returns segment times as *local* wall-clock strings with no offset (`2026-08-16T16:35:00`).
That is exactly what a traveller means by "after 4PM", so Skylark compares those strings directly.
Converting them to UTC first would silently turn "leave after 4PM" into a different question.

## Limits

- One-way search is fully wired; round-trip parses and passes a return date to Amadeus, but the
  ranking scores the outbound leg's timing only.
- Flexible date windows are sampled (up to 7 dates spread across the range), not exhaustive.
- The synthetic provider has no time zones — every stamp is on one clock, so a demo transatlantic
  arrival reads several hours later than it would in reality. Real provider data carries the real
  local times, which is what the timing rules are written against.
- Skylark does not sell tickets. It points at the option worth buying.
