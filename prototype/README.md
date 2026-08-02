# Zephyr prototype

A working Zephyr that runs in a browser, so the app can be *used* — and judged — before its design
is committed to Compose and shipped in an APK.

```bash
node prototype/build.mjs        # bundle -> dist/zephyr.html (self-contained, open it directly)
node prototype/parity.test.mjs  # prove the prototype agrees with the Android app
gradle -p core test             # run the Kotlin suite; also regenerates parity-fixtures.json
```

## Why it isn't just a mockup

Every number comes from `zephyr-core.js`, a port of `core/src/main/kotlin/dev/zephyr/core`. Two
implementations of one set of rules normally drift, and a prototype that quietly disagrees with the
shipping app is worse than no prototype — it produces confident decisions about figures the user
will never actually see.

So Kotlin is the source of truth. `ParityFixtureTest` writes down the answers it gives for 69 cases
spanning every formula, and `parity.test.mjs` replays each one through the JavaScript. Change either
side alone and the check goes red.

Three real bugs were found by using it, all of which would have shipped:

- **Adaptive maintenance added logged exercise where it had to subtract it.** `TdeeCalculator`
  defines maintenance as the baseline *before* exercise, since the ledger adds each day's burn on
  top. The sign error inflated the estimate by twice the exercise burn — enough to erase the whole
  deficit, and only for people who train.
- **The weekly projection included today**, which is always part-logged, so it overstated the
  deficit every morning.
- **The rate came from the smoothed trend's endpoints.** That smoothing is seeded at the first
  weigh-in and lags for weeks, understating real loss by about a third and biasing maintenance
  150–250 kcal low — toward a deeper deficit than the user asked for. Rates now come from a
  least-squares fit of the raw weigh-ins; the smoothed line remains what's displayed.

## What's simulated

Only what a browser genuinely cannot do, and it's labelled in the app:

- **Steps** — no pedometer, so the simulator adds them.
- **GPS** — a tracked session accrues distance on a timer instead of from satellites.
- **Time** — the simulator shifts the clock so the coach's whole day can be inspected without
  waiting for it, and seeds a month of history so adaptive maintenance can be seen switching on.

Everything else — targets, macros, trend, streak, progression, and every decision the coach makes —
is the real logic.

## Files

| | |
|---|---|
| `zephyr-core.js` | the domain logic, parity-tested against Kotlin |
| `app.js` | screens, state, interaction |
| `styles.css` | the app's real design tokens |
| `build.mjs` | flattens the modules into one self-contained page |
| `parity.test.mjs` | replays the Kotlin fixtures through the JavaScript |
| `parity-fixtures.json` | generated — do not hand-edit |
