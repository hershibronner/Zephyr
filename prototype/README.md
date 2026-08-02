# Zephyr prototype

A working Zephyr that runs in a browser, so the app can be *used* — and judged — before its design
is committed to Compose and shipped in an APK.

## Run it

```bash
npm start
```

Or, from anywhere, without cloning — download the repo as a ZIP, then in Terminal:

```bash
cd ~/Downloads/Zephyr-* && node prototype/serve.mjs
```

### If macOS blocks the launcher

`Start Zephyr (Mac).command` is convenient but downloaded scripts are quarantined, and macOS 15
(Sequoia) removed the old right-click -> Open bypass — the warning it shows has only an **OK**
button, with no way through. Either use the Terminal line above, which Gatekeeper doesn't apply to,
or clear the quarantine flag once:

```bash
xattr -d com.apple.quarantine "Start Zephyr (Mac).command"
```

That's the whole setup. No `npm install`, no dependencies, no lockfile — Node's own libraries are
enough, and a dev server isn't worth an install step in a repo whose real build is Gradle.

```
  local    http://localhost:5173
  network  http://192.168.1.42:5173   ← open this on your phone
```

The server prints a LAN address as well as localhost. Open that one on your phone while it's on the
same Wi-Fi: a fitness app tested only on a desktop tells you about half of what you need to know.

Editing `zephyr-core.js`, `app.js` or `styles.css` rebuilds and reloads every open tab, phone
included. `PORT=8080 npm start` if 5173 is taken.

Your data lives in that browser's local storage, so it persists across restarts and each device
keeps its own. Settings → *Erase everything and start over* clears it.

## Other commands

```bash
npm run build   # bundle -> dist/zephyr.html, a single self-contained file you can open directly
npm test        # prove the prototype agrees with the Android app
gradle -p core test   # the Kotlin suite; also regenerates parity-fixtures.json
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
| `serve.mjs` | dev server: rebuild on save, reload every open tab |
| `build.mjs` | flattens the modules into one self-contained page |
| `parity.test.mjs` | replays the Kotlin fixtures through the JavaScript |
| `parity-fixtures.json` | generated — do not hand-edit |
