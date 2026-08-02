# Play Store submission notes

Working reference for publishing Zephyr. Nothing here is submitted automatically — these are the
answers and assets to have ready.

## Identity

| Field | Value |
|---|---|
| App name | Zephyr |
| Package | `app.zephyr.fitness` — **permanent once uploaded**, cannot be changed |
| Category | Health & Fitness |
| Content rating | Everyone (complete the IARC questionnaire; no objectionable content) |
| Privacy policy URL | Publish `docs/privacy-policy.md` via GitHub Pages and link it here |

## Data safety form

Zephyr's answers are unusually simple because nothing is collected:

- **Does your app collect or share any of the required user data types?** → **No.**

  All health, fitness, location and personal data stays in app-private storage on the device. There
  is no account, no backend, no analytics SDK, and no crash reporting.

- **Is all user data encrypted in transit?** → Not applicable (no user data is transmitted). The one
  outbound call — an Open Food Facts product lookup — sends only a search term or barcode, which is
  not user data, and uses HTTPS.

- **Do you provide a way to request data deletion?** → Data is device-local; uninstalling removes it.

If analytics or cloud sync is ever added, this section must be rewritten before that release ships.

## Declarations that commonly cause rejection

### 1. Foreground service — location

`FOREGROUND_SERVICE_LOCATION` requires a declaration in Play Console describing the use case, plus a
**demo video** showing the in-app flow that starts it.

- Use case: tracking distance, pace and elevation during a run or hike the user explicitly starts.
- The service runs only between the user tapping Start and Finish, and shows a persistent
  notification throughout.
- Zephyr deliberately does **not** request `ACCESS_BACKGROUND_LOCATION`, which avoids the much
  heavier background-location review entirely.

Before requesting the permission the app must show a **prominent disclosure** screen explaining what
location is used for — Play requires this, and it is checked during review.

### 2. Health apps policy

- The app must not present itself as a medical device or give medical advice. The onboarding screen
  and privacy policy both carry an explicit disclaimer.
- Calorie targets are capped at a 25% deficit, floored above resting metabolic rate, and floored
  again at an absolute minimum, so the app cannot prescribe a dangerous intake regardless of what
  goal the user selects. This is enforced in `CalorieTarget` and covered by unit tests.

### 3. Photo/camera

Camera is used only for on-device barcode scanning. No images are stored or transmitted.

## Release build checklist

- [ ] Generate an upload keystore and store it as repo secrets (`KEYSTORE_BASE64`,
      `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). **Keep a backup — losing the upload key
      means never being able to update the listing.**
- [ ] Bump `versionCode` (must increase on every upload) and `versionName`.
- [ ] Build an **AAB** (`bundleRelease`), not an APK — Play requires app bundles.
- [ ] Verify R8 shrinking didn't break Room or serialization (`proguard-rules.pro` keeps both).
- [ ] Confirm `targetSdk` meets Play's current requirement.
- [ ] Test the release build on a real device before uploading — debug and release differ.

## Store listing assets needed

| Asset | Spec |
|---|---|
| App icon | 512×512 PNG |
| Feature graphic | 1024×500 PNG |
| Phone screenshots | 2–8, min 1080px on the short side |
| Short description | ≤80 characters |
| Full description | ≤4000 characters |

### Draft short description

> One number a day. Eat, run, hike, lift — Zephyr keeps the score and gets you moving.

### Draft full description

> **Looking better comes down to two things: energy balance, and enough protein and training to keep
> the muscle you have. Zephyr is built around exactly that.**
>
> Most fitness apps split your day into pieces that don't talk to each other — a food diary that
> doesn't know you ran 10k, a run tracker that doesn't know what you ate. Zephyr keeps one ledger.
> Everything you eat subtracts from your daily number. Every run, hike, lift and step adds back.
>
> **It learns what your body actually burns.** Calorie formulas are wrong by up to 15% for any given
> person, which is why so many people stall on a "correct" target. After two weeks, Zephyr stops
> guessing and starts measuring — comparing your smoothed weight trend against what you actually ate
> and burned — then adjusts your target to match reality.
>
> **It gets you out the door.** Set the shape of your week — run Tuesday, lift Wednesday, hike
> Saturday — and Zephyr handles the progression, building your distance safely and backing off
> before you break. It reminds you before a session, tells you when you're behind on steps while
> there's still time to fix it, and protects your streak.
>
> **What's inside**
> • One daily energy ring: eaten, burned, remaining
> • Food logging with barcode scanning
> • GPS tracking for runs and hikes, with route, pace and elevation
> • Strength logging that tells you what weight to use next
> • Step goals set from your own history, not a number from a 1960s advert
> • Weight trend that ignores daily water-weight noise
> • A coach you can turn up, turn down, or quiet at night
>
> **No account. No ads. No tracking.** Your data stays on your phone.
>
> Zephyr gives general fitness and nutrition guidance. It is not medical advice.
