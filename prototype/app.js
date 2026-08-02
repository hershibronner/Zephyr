/**
 * Zephyr prototype — a working app you can actually use.
 *
 * Every number here comes from `zephyr-core.js`, which is parity-tested against the Kotlin the
 * Android app ships. What this prototype fakes is only what a browser genuinely cannot do: the
 * pedometer and GPS. Those are simulated and clearly labelled; everything else — the targets, the
 * trend, the streak, the coach's decisions — is the real thing.
 */

import * as Z from './zephyr-core.js';

const STORAGE_KEY = 'zephyr.prototype.v1';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

const blank = () => ({
  onboarded: false,
  onboardStep: 0,
  profile: {
    sex: 'MALE', birthYear: '', heightCm: '', weightKg: '', goalWeightKg: '',
    activityLevel: 'LIGHT', goalPace: 'LOSE_STEADY',
  },
  stepGoal: Z.STEP_GOAL_DEFAULT,
  stepGoalManual: false,
  coach: {
    tone: 'BALANCED',
    quietHours: { start: '21:30', end: '07:00' },
    enabledCategories: Object.keys(Z.NudgeCategory),
    maxPerDay: Z.CoachTone.BALANCED.maxPerDay,
  },
  food: [],       // {id, date, slot, name, kcal, proteinG, carbsG, fatG}
  weights: [],    // {date, weightKg}
  sessions: [],   // {id, date, type, durationSeconds, distanceMetres, elevationGainMetres, kcal, netKcal}
  strength: [],   // {id, date, exercise, sets:[{reps, weightKg}]}
  steps: {},      // ISO date -> count
  plan: Z.defaultTemplate(),
  nudgeLog: [],   // {key, date, isCelebration}
  clockOffsetMinutes: 0,
  tab: 'today',
  /** Day being viewed. Null means today; the date strip sets it to look back. */
  selectedDate: null,
});

let S = load();
let sheet = null;      // {kind, ...}
let live = null;       // in-flight session
let liveTimer = null;

function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return blank();
    return { ...blank(), ...JSON.parse(raw) };
  } catch {
    return blank();
  }
}

function save() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(S)); } catch { /* private mode */ }
}

const uid = () => Math.random().toString(36).slice(2, 10);

// ---------------------------------------------------------------------------
// Clock — offsettable so a whole day can be inspected without waiting for it
// ---------------------------------------------------------------------------

function now() {
  return new Date(Date.now() + S.clockOffsetMinutes * 60000);
}
function todayISO() {
  const d = now();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
const nowHour = () => now().getHours();
const nowMinute = () => now().getMinutes();
const clockLabel = () => `${String(nowHour()).padStart(2, '0')}:${String(nowMinute()).padStart(2, '0')}`;

/** The day on screen, which is today unless the date strip says otherwise. */
const viewDate = () => S.selectedDate ?? todayISO();
const isToday = () => viewDate() === todayISO();

// ---------------------------------------------------------------------------
// Derived state — one snapshot everything renders from
// ---------------------------------------------------------------------------

function derive() {
  const date = viewDate();
  const p = S.profile;
  const ready = S.onboarded && p.weightKg !== '' && p.heightCm !== '' && p.birthYear !== '';

  const steps = S.steps[date] ?? 0;
  // A past day is finished, so judge its steps against the whole day rather than the current hour —
  // otherwise looking back at 9am makes every previous day appear to be failing.
  const [h, m] = isToday() ? [nowHour(), nowMinute()] : [23, 59];
  const stepStatus = Z.stepStatus(steps, S.stepGoal, h, m);
  const trend = Z.weightTrend(S.weights);

  if (!ready) {
    return {
      date, ready: false, stepStatus, trend,
      balance: Z.energyBalance({ date, targetKcal: 0, consumedKcal: 0, exerciseKcal: 0, proteinTargetG: 0, macros: { proteinG: 0, carbsG: 0, fatG: 0 } }),
      target: null, macros: null, adaptive: null, streak: { current: 0, longest: 0, atRisk: false },
      week: Z.weeklySummary([]), todaysFood: [], todaysSessions: [], prescriptions: [], missedYesterday: [], adherence: null,
    };
  }

  const profile = {
    sex: p.sex, heightCm: +p.heightCm, weightKg: +p.weightKg,
    ageYears: Z.ageOn(+p.birthYear, date), activityLevel: p.activityLevel,
  };
  const bmrKcal = Z.bmr(profile.weightKg, profile.heightCm, profile.ageYears, profile.sex);
  const formula = Z.formulaTdee(profile);

  // Adaptive maintenance from the last 28 days of complete-enough data
  const window = Z.addDays(date, -Z.ADAPTIVE_FULL_DAYS);
  const intakeByDate = new Map();
  S.food.filter(f => f.date >= window).forEach(f => intakeByDate.set(f.date, (intakeByDate.get(f.date) ?? 0) + f.kcal));
  const burnByDate = new Map();
  S.sessions.filter(s => s.date >= window).forEach(s => burnByDate.set(s.date, (burnByDate.get(s.date) ?? 0) + s.netKcal));
  const records = [...intakeByDate.keys()].sort().map(d => ({ intakeKcal: intakeByDate.get(d), exerciseKcal: burnByDate.get(d) ?? 0 }));

  // Rate comes from a straight-line fit of the raw weigh-ins, not the smoothed trend's endpoints:
  // the smoothing is seeded at the first reading and lags for weeks, biasing maintenance low.
  const startDate = [...intakeByDate.keys()].sort()[0];
  const windowWeights = S.weights.filter(w => !startDate || w.date >= startDate);
  const adaptive = Z.adaptiveTdee(formula, records, Z.fittedChangeKg(windowWeights));

  const maintenance = (adaptive.measuredTdeeKcal != null) ? adaptive.tdeeKcal : formula;
  const target = Z.calorieTarget(maintenance, bmrKcal, Z.GoalPace[p.goalPace].kgPerWeek, p.sex);
  const macros = Z.macros(target.targetKcal, +p.goalWeightKg, Z.GoalPace[p.goalPace].kgPerWeek < 0);

  const todaysFood = S.food.filter(f => f.date === date);
  const todaysSessions = S.sessions.filter(s => s.date === date);
  const totals = todaysFood.reduce((a, f) => ({
    proteinG: a.proteinG + Math.round(f.proteinG || 0),
    carbsG: a.carbsG + Math.round(f.carbsG || 0),
    fatG: a.fatG + Math.round(f.fatG || 0),
  }), { proteinG: 0, carbsG: 0, fatG: 0 });

  const balance = Z.energyBalance({
    date, targetKcal: target.targetKcal,
    consumedKcal: todaysFood.reduce((a, f) => a + f.kcal, 0),
    exerciseKcal: todaysSessions.reduce((a, s) => a + s.netKcal, 0),
    proteinTargetG: macros.proteinG, macros: totals, maintenanceKcal: maintenance,
  });

  // Streak over everything logged
  const allDates = [...new Set(S.food.map(f => f.date))].sort();
  const outcomes = allDates.map(d => {
    const consumed = S.food.filter(f => f.date === d).reduce((a, f) => a + f.kcal, 0);
    const burned = S.sessions.filter(s => s.date === d).reduce((a, s) => a + s.netKcal, 0);
    return {
      date: d, loggedFood: true,
      withinCalorieTarget: consumed <= target.targetKcal + burned,
      hitStepGoal: (S.steps[d] ?? 0) >= S.stepGoal,
      completedSession: S.sessions.some(s => s.date === d),
    };
  });
  const streak = Z.calculateStreak(outcomes, date);

  // Complete days only — today is still being logged and would fake a deeper deficit.
  const week = Z.weeklySummary(Z.lastCompleteDays(date).map(d => {
    const dayFood = S.food.filter(f => f.date === d);
    return Z.energyBalance({
      date: d, targetKcal: target.targetKcal,
      consumedKcal: dayFood.reduce((a, f) => a + f.kcal, 0),
      exerciseKcal: S.sessions.filter(s => s.date === d).reduce((a, s) => a + s.netKcal, 0),
      proteinTargetG: macros.proteinG,
      macros: { proteinG: 0, carbsG: 0, fatG: 0 },
      maintenanceKcal: maintenance,
    });
  }));

  // This week's prescriptions
  const weekStart = Z.startOfWeek(date);
  const planStart = S.sessions.length
    ? Z.startOfWeek(S.sessions.map(s => s.date).sort()[0])
    : weekStart;
  const weekIndex = Math.max(0, Math.round(Z.daysBetween(planStart, weekStart) / 7));
  const longestRun = S.sessions.filter(s => s.type === 'RUN' && s.date >= Z.addDays(date, -30))
    .reduce((m, s) => Math.max(m, s.distanceMetres), 0) || null;
  const prescriptions = Z.prescriptionsForWeek(S.plan, weekStart, weekIndex, { longestRunMetres: longestRun, longestHikeMinutes: null });

  const completed = S.sessions.map(s => ({ date: s.date, type: s.type }));
  const adherenceResult = Z.adherence(prescriptions, completed, date);

  const yesterday = Z.addDays(date, -1);
  const lastWeekStart = Z.startOfWeek(yesterday);
  const yPrescriptions = Z.prescriptionsForWeek(S.plan, lastWeekStart, Math.max(0, weekIndex - (lastWeekStart === weekStart ? 0 : 1)), { longestRunMetres: longestRun, longestHikeMinutes: null });
  const missedYesterday = Z.adherence(yPrescriptions, completed, yesterday).missed.filter(m => m.date === yesterday);

  return {
    date, ready: true, profile, bmrKcal, formula, maintenance, target, macros,
    balance, stepStatus, trend, adaptive, streak, week,
    todaysFood, todaysSessions, prescriptions, adherence: adherenceResult, missedYesterday,
  };
}

// ---------------------------------------------------------------------------
// Coach
// ---------------------------------------------------------------------------

function coachSettings() {
  return {
    tone: S.coach.tone,
    quietHours: S.coach.quietHours,
    enabledCategories: S.coach.enabledCategories,
    maxPerDay: S.coach.maxPerDay,
  };
}

function coachState(d, opts = {}) {
  const date = d.date;
  const lastFood = S.food.filter(f => f.date === date).slice(-1)[0];
  const lastWeigh = S.weights.length ? S.weights.map(w => w.date).sort().slice(-1)[0] : null;
  const sentToday = S.nudgeLog.filter(n => n.date === date && !n.isCelebration).length;

  return {
    date, hour: opts.hour ?? nowHour(), minute: opts.minute ?? nowMinute(),
    balance: d.balance,
    stepStatus: opts.hour != null ? Z.stepStatus(d.stepStatus.steps, d.stepStatus.goal, opts.hour, opts.minute ?? 0) : d.stepStatus,
    stepsInLastHour: opts.stepsInLastHour ?? 400,
    streakDays: d.streak.current,
    streakSecuredToday: !d.streak.atRisk && d.streak.current > 0,
    todaysPrescriptions: d.prescriptions.filter(p => p.date === date),
    completedSessionToday: d.todaysSessions.length > 0,
    missedYesterday: d.missedYesterday,
    hoursSinceFoodLog: lastFood ? 0 : null,
    daysSinceWeighIn: lastWeigh ? Z.daysBetween(lastWeigh, date) : null,
    alreadySentKeys: opts.ignoreSent ? [] : S.nudgeLog.map(n => n.key),
    sentTodayCount: opts.ignoreSent ? 0 : sentToday,
    isTrackingSession: !!live,
  };
}

function pendingNudges(d) {
  return Z.evaluateNudges(coachState(d), coachSettings());
}

/** Every notification the coach would send across a whole day, for tuning its voice. */
function dayTimeline(d) {
  const sent = [];
  const out = [];
  for (let m = 0; m < 24 * 60; m += 5) {
    const hour = Math.floor(m / 60), minute = m % 60;
    const nudges = Z.evaluateNudges(
      { ...coachState(d, { hour, minute, ignoreSent: true }), alreadySentKeys: sent.map(s => s.key), sentTodayCount: sent.filter(s => !s.isCelebration).length },
      coachSettings(),
    );
    for (const n of nudges) {
      sent.push(n);
      out.push({ ...n, at: `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}` });
    }
  }
  return out;
}

// ---------------------------------------------------------------------------
// Food library — enough to make logging feel real
// ---------------------------------------------------------------------------

const FOODS = [
  ['Chicken breast, grilled', 165, 31, 0, 3.6],
  ['Salmon fillet', 208, 20, 0, 13],
  ['Beef mince, 5% fat', 137, 21, 0, 5],
  ['Eggs, whole', 143, 13, 1.1, 9.5],
  ['Greek yogurt, 0%', 59, 10, 3.6, 0.4],
  ['Cottage cheese', 98, 11, 3.4, 4.3],
  ['Whey protein powder', 380, 80, 8, 4],
  ['Tofu, firm', 144, 17, 3, 9],
  ['Lentils, cooked', 116, 9, 20, 0.4],
  ['Black beans, cooked', 132, 9, 24, 0.5],
  ['White rice, cooked', 130, 2.7, 28, 0.3],
  ['Brown rice, cooked', 123, 2.7, 26, 1],
  ['Pasta, cooked', 158, 6, 31, 0.9],
  ['Potato, boiled', 87, 2, 20, 0.1],
  ['Sweet potato, baked', 90, 2, 21, 0.2],
  ['Oats, dry', 379, 13, 67, 7],
  ['Wholemeal bread', 247, 13, 41, 3.4],
  ['Banana', 89, 1.1, 23, 0.3],
  ['Apple', 52, 0.3, 14, 0.2],
  ['Blueberries', 57, 0.7, 14, 0.3],
  ['Avocado', 160, 2, 9, 15],
  ['Almonds', 579, 21, 22, 50],
  ['Peanut butter', 588, 25, 20, 50],
  ['Olive oil', 884, 0, 0, 100],
  ['Broccoli', 34, 2.8, 7, 0.4],
  ['Spinach', 23, 2.9, 3.6, 0.4],
  ['Mixed salad', 17, 1.4, 3, 0.2],
  ['Cheddar cheese', 402, 25, 1.3, 33],
  ['Milk, semi-skimmed', 50, 3.4, 4.8, 1.8],
  ['Protein bar', 350, 30, 35, 10],
  ['Pizza, cheese', 266, 11, 33, 10],
  ['Burger, beef', 295, 17, 24, 14],
  ['Fries', 312, 3.4, 41, 15],
  ['Chocolate, dark', 546, 5, 61, 31],
  ['Beer', 43, 0.5, 3.6, 0],
  ['Red wine', 85, 0.1, 2.6, 0],
];

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const app = document.getElementById('app');

function render() {
  const d = derive();

  if (!S.onboarded) {
    app.innerHTML = `<div id="shell"><main class="ob-main">${onboardingView()}</main></div>`;
    wire(d);
    runCountUps();
    return;
  }

  const pending = pendingNudges(d);
  app.innerHTML = `
    <div id="shell">
      ${topbar(pending.length)}
      <main>${screen(d)}</main>
      ${d.ready && S.tab === 'today' ? `<button class="fab" data-act="add-food">Log food</button>` : ''}
      ${nav()}
      ${sheet ? sheetView(d) : ''}
    </div>`;
  wire(d);
}

function topbar(pendingCount) {
  const initials = (S.profile.name || 'You').trim().slice(0, 2).toUpperCase();
  const d = now();
  const stamp = d.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'short' });

  return `<div class="topbar">
    <button class="avatar" data-act="settings" aria-label="Profile and settings">${esc(initials)}</button>
    <div class="greet">
      <div class="hi">${esc(greetingFor(d.getHours()))}</div>
      <div class="date">${esc(stamp)}</div>
    </div>
    <button class="icon-btn" data-act="sim" aria-label="Simulator">${icon('sliders')}</button>
    <button class="icon-btn" data-act="coach" aria-label="Coach">
      ${icon('bell')}${pendingCount ? `<span class="badge">${pendingCount}</span>` : ''}
    </button>
  </div>`;
}

const greetingFor = hour =>
  hour < 5 ? 'Still up?' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening';

/** Seven days ending today, so a forgotten meal can be added the next morning. */
function dateStrip() {
  const today = todayISO();
  const selected = viewDate();

  return `<div class="dates">${Array.from({ length: 7 }, (_, i) => {
    const date = Z.addDays(today, i - 6);
    const d = new Date(date + 'T00:00:00Z');
    const logged = S.food.some(f => f.date === date) || S.sessions.some(s => s.date === date);
    return `<button class="day ${date === selected ? 'on' : ''}" data-act="pick-date" data-date="${date}">
      <span class="dow">${d.toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' }).slice(0, 3)}</span>
      <span class="num">${d.getUTCDate()}</span>
      <span class="pip ${logged ? '' : 'hidden'}"></span>
    </button>`;
  }).join('')}</div>`;
}

function nav() {
  const tabs = [
    ['today', 'Today', 'ring'],
    ['food', 'Food', 'fork'],
    ['move', 'Move', 'run'],
    ['plan', 'Plan', 'calendar'],
    ['progress', 'Progress', 'chart'],
  ];
  return `<nav>${tabs.map(([id, label, ic]) =>
    `<button data-tab="${id}" class="${S.tab === id ? 'on' : ''}" aria-label="${label}">${icon(ic)}</button>`).join('')}</nav>`;
}

function screen(d) {
  switch (S.tab) {
    case 'food': return foodView(d);
    case 'move': return moveView(d);
    case 'plan': return planView(d);
    case 'progress': return progressView(d);
    default: return todayView(d);
  }
}

// ---- Today ---------------------------------------------------------------

function todayView(d) {
  if (!d.ready) return `<div class="empty">Finish setup to see your day.</div>`;
  const b = d.balance;
  const over = b.remainingKcal < 0;
  const next = d.prescriptions.find(p => p.date === d.date);
  const done = next && S.sessions.some(s => s.type === next.slot.type && s.date === d.date);

  return `
    ${dateStrip()}

    <div class="hero-card ${over ? 'over' : ''}">
      <span class="hero-eyebrow">${isToday() ? 'Today' : esc(prettyDate(d.date))}</span>
      ${(() => {
        const text = Z.fmt(Math.abs(b.remainingKcal));
        // Step the display size down as digits are added, so a four-figure budget still sits
        // comfortably inside the ring instead of touching both edges.
        const size = text.length >= 6 ? 42 : text.length >= 5 ? 50 : 58;
        return ring(b.fractionConsumed, d.stepStatus.fractionOfGoal, over, `
          <div class="ring-big" style="font-size:${size}px">${text}</div>
          <div class="ring-label">${over ? 'kcal over' : 'kcal left'}</div>`);
      })()}
      <div class="hero-split">
        <div><div class="v">${Z.fmt(b.consumedKcal)}</div><div class="l">Eaten</div></div>
        <div><div class="v">${Z.fmt(b.exerciseKcal)}</div><div class="l">Burned</div></div>
        <div><div class="v">${Z.fmt(b.targetKcal)}</div><div class="l">Target</div></div>
      </div>
    </div>

    <div class="tiles">
      ${tile('t-sky', 'run', Z.fmt(d.stepStatus.steps), `of ${Z.fmt(d.stepStatus.goal)} steps`, d.stepStatus.fractionOfGoal)}
      ${tile('t-green', 'leaf', `${b.macros.proteinG}g`, `of ${b.proteinTargetG}g protein`, b.proteinFraction)}
      ${tile('t-orange', 'flame', Z.fmt(b.exerciseKcal), d.todaysSessions.length === 1 ? '1 session' : `${d.todaysSessions.length} sessions`)}
      ${tile('t-pink', 'bolt', String(d.streak.current), d.streak.current === 1 ? 'day streak' : 'day streak')}
    </div>

    ${next ? `
      <div class="section"><h2 class="title">Your plan</h2>
        <button class="link-btn" data-act="tab-plan">See week</button></div>
      <div class="plan-card ${done ? 't-green' : 't-violet'}">
        <span class="chip">${done ? 'Done' : esc(Z.formatClock(next.slot.timeOfDay))}</span>
        <div class="h">${done ? '✓ ' : ''}${esc(next.headline)}</div>
        <div class="d">${esc(next.detail)}</div>
      </div>` : ''}

    <div class="section"><h2 class="title">Weight</h2>
      <button class="link-btn" data-act="weigh">Log</button></div>
    ${d.trend.currentTrendKg != null ? trendCard(d) : `
      <button class="card tap flat" data-act="weigh" style="border:0;font-family:inherit;text-align:left;width:100%">
        <div class="kicker">Weigh in</div>
        <div style="font-size:19px;font-weight:780;letter-spacing:-.03em">Log today's weight</div>
        <p class="tiny">Zephyr needs a couple of weeks of weigh-ins before it can measure what you actually burn.</p>
      </button>`}

    ${d.todaysFood.length ? `
      <div class="section"><h2 class="title">${isToday() ? "Today's food" : 'Food'}</h2>
        <button class="link-btn" data-act="tab-food">All</button></div>
      <div class="list">${d.todaysFood.map(f => `
        <div class="item">
          <div><div class="name">${esc(f.name)}</div><div class="meta">${esc(f.slot)}</div></div>
          <div style="display:flex;align-items:center;gap:10px">
            <span class="val">${Z.fmt(f.kcal)}</span>
            <button class="x" data-act="del-food" data-id="${f.id}" aria-label="Remove">×</button>
          </div>
        </div>`).join('')}</div>` : ''}`;
}

function trendCard(d) {
  const rate = d.trend.weeklyRateKg;
  const note = rate == null ? 'Keep weighing in — a few more days and the trend becomes reliable.'
    : Math.abs(rate) < 0.05 ? 'Holding steady.'
      : rate < 0 ? `Down ${Math.abs(Z.kgToLb(rate)).toFixed(1)} lb a week — right on it.`
        : `Up ${Z.kgToLb(rate).toFixed(1)} lb a week.`;

  return `<button class="card tap flat" data-act="weigh" style="border:0;font-family:inherit;text-align:left;width:100%">
    <div class="kicker">Trend weight</div>
    <div style="font-size:36px;font-weight:830;letter-spacing:-.045em;font-variant-numeric:tabular-nums;line-height:1">
      ${Z.kgToLb(d.trend.currentTrendKg).toFixed(1)}<span style="font-size:19px;font-weight:700;color:var(--muted)"> lb</span>
    </div>
    <div class="tiny">${esc(note)}</div>
    ${d.adaptive?.measuredTdeeKcal != null
      ? `<div class="callout good">Zephyr now measures your maintenance at ${Z.fmt(d.adaptive.measuredTdeeKcal)} kcal, from ${d.adaptive.daysOfData} days of your own data — not a formula.</div>` : ''}
  </button>`;
}

// ---- Food ----------------------------------------------------------------

function foodView(d) {
  if (!d.ready) return `<div class="empty">Finish setup first.</div>`;
  const slots = ['Breakfast', 'Lunch', 'Dinner', 'Snack'];

  return `
    <div class="row">
      <div><h1 class="screen">Food</h1><p class="sub">${Z.fmt(d.balance.consumedKcal)} of ${Z.fmt(d.balance.adjustedTargetKcal)} kcal</p></div>
      <button class="btn" data-act="add-food">Add</button>
    </div>
    ${slots.map(slot => {
      const items = d.todaysFood.filter(f => f.slot === slot);
      const total = items.reduce((a, f) => a + f.kcal, 0);
      return `<div>
        <div class="row" style="margin-bottom:8px">
          <span class="kicker">${slot.toUpperCase()}</span>
          <span class="tiny">${total ? Z.fmt(total) + ' kcal' : ''}</span>
        </div>
        ${items.length ? `<div class="list">${items.map(f => `
          <div class="item">
            <div class="col"><span class="name">${esc(f.name)}</span>
            <span class="meta">${Math.round(f.proteinG)}P · ${Math.round(f.carbsG)}C · ${Math.round(f.fatG)}F</span></div>
            <div class="row" style="gap:8px"><span class="val">${Z.fmt(f.kcal)}</span>
            <button class="x" data-act="del-food" data-id="${f.id}" aria-label="Remove">×</button></div>
          </div>`).join('')}</div>`
        : `<button class="pill block" data-act="add-food" data-slot="${slot}">Add ${slot.toLowerCase()}</button>`}
      </div>`;
    }).join('')}
    <div class="card">
      <div class="kicker">MACROS TODAY</div>
      ${bar('PROTEIN', `${d.balance.macros.proteinG} / ${d.macros.proteinG} g`, d.balance.proteinFraction, 'var(--green)')}
      ${bar('CARBS', `${d.balance.macros.carbsG} / ${d.macros.carbsG} g`, d.macros.carbsG ? d.balance.macros.carbsG / d.macros.carbsG : 0, 'var(--sky)')}
      ${bar('FAT', `${d.balance.macros.fatG} / ${d.macros.fatG} g`, d.macros.fatG ? d.balance.macros.fatG / d.macros.fatG : 0, 'var(--ember)')}
    </div>`;
}

// ---- Move ----------------------------------------------------------------

function moveView(d) {
  if (!d.ready) return `<div class="empty">Finish setup first.</div>`;

  if (live) {
    const secs = live.elapsedSeconds;
    const pace = Z.paceSecondsPerMile(live.distanceMetres, secs);
    const burn = Z.activityBurn(live.type, secs, live.distanceMetres, live.elevationGainMetres, +S.profile.weightKg);
    return `
      <div><h1 class="screen">${esc(Z.ACTIVITY_LABEL[live.type])}</h1>
      <p class="sub">${live.paused ? 'Paused' : 'Tracking — simulated GPS'}</p></div>
      <div class="center">
        <div class="live-big">${Z.metresToMiles(live.distanceMetres).toFixed(2)}</div>
        <div class="tiny" style="font-weight:700;letter-spacing:.1em;text-transform:uppercase">miles</div>
      </div>
      <div class="live-grid">
        <div class="live-cell"><div class="v">${hms(secs)}</div><div class="l">TIME</div></div>
        <div class="live-cell"><div class="v">${Z.formatPace(pace)}</div><div class="l">PACE /MI</div></div>
        <div class="live-cell"><div class="v">${burn.netKcal}</div><div class="l">KCAL</div></div>
      </div>
      ${live.type === 'HIKE' ? `<div class="live-cell"><div class="v">${Z.formatFeet(live.elevationGainMetres)}</div><div class="l">CLIMB</div></div>` : ''}
      <div class="btn-row">
        <button class="btn ghost" data-act="live-pause">${live.paused ? 'Resume' : 'Pause'}</button>
        <button class="btn" data-act="live-finish">Finish</button>
      </div>
      <p class="tiny center">The phone app tracks this with real GPS. Here the route is simulated so the screen and the maths can be used for real.</p>`;
  }

  const recent = [...S.sessions].sort((a, b) => b.date.localeCompare(a.date) || b.id.localeCompare(a.id)).slice(0, 12);

  return `
    <div><h1 class="screen">Move</h1><p class="sub">Start a session, or add one you've already done.</p></div>
    <div class="card">
      <div class="kicker">START</div>
      <div class="btn-row">
        <button class="btn" data-act="start" data-type="RUN">Run</button>
        <button class="btn ghost" data-act="start" data-type="HIKE">Hike</button>
        <button class="btn ghost" data-act="start" data-type="WALK">Walk</button>
      </div>
      <button class="pill block" data-act="manual-session">Add a session manually</button>
    </div>

    <div class="card">
      <div class="kicker">STRENGTH</div>
      <button class="pill block" data-act="add-lift">Log a lift</button>
      ${S.strength.length ? `<div class="list">${[...S.strength].slice(-4).reverse().map(w => `
        <div class="item"><div class="col"><span class="name">${esc(w.exercise)}</span>
        <span class="meta">${esc(w.date)} · ${w.sets.map(s => `${s.reps}×${s.weightKg}kg`).join(', ')}</span></div>
        <span class="val">${nextLiftHint(w.exercise)}</span></div>`).join('')}</div>` : ''}
    </div>

    <div class="kicker">HISTORY</div>
    ${recent.length ? `<div class="list">${recent.map(s => `
      <div class="item">
        <div class="col"><div class="name">${esc(Z.ACTIVITY_LABEL[s.type])}${s.distanceMetres ? ' · ' + Z.formatMiles(s.distanceMetres) : ''}</div>
        <div class="meta">${esc(s.date)} · ${hms(s.durationSeconds)}${s.distanceMetres ? ' · ' + Z.formatPace(Z.paceSecondsPerMile(s.distanceMetres, s.durationSeconds)) + '/mi' : ''}</div></div>
        <div class="row" style="gap:8px"><span class="val">${s.netKcal} kcal</span>
        <button class="x" data-act="del-session" data-id="${s.id}" aria-label="Remove">×</button></div>
      </div>`).join('')}</div>`
    : `<div class="empty">Nothing logged yet. Start a session above.</div>`}`;
}

function nextLiftHint(exercise) {
  const history = S.strength.filter(w => w.exercise === exercise);
  if (!history.length) return '';
  const last = history[history.length - 1];
  const next = Z.nextStrengthPrescription(last.sets, [8, 12], 0, /squat|deadlift|lunge|leg/i.test(exercise));
  return next ? `next ${Math.round(Z.kgToLb(next.weightKg) / 5) * 5} lb` : '';
}

// ---- Plan ----------------------------------------------------------------

const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function planView(d) {
  if (!d.ready) return `<div class="empty">Finish setup first.</div>`;
  const a = d.adherence;

  return `
    <div><h1 class="screen">Plan</h1><p class="sub">You choose the shape of the week. Zephyr sets the load.</p></div>

    ${a && a.planned > 0 ? `<div class="card">
      <div class="row"><span class="kicker">THIS WEEK</span><span class="val" style="color:var(--green);font-weight:700">${a.completed}/${a.planned}</span></div>
      ${bar('ADHERENCE', `${a.percent}%`, a.percent / 100, a.percent >= 70 ? 'var(--green)' : 'var(--warn)')}
    </div>` : ''}

    <div class="list">
      ${d.prescriptions.map(p => {
        const done = S.sessions.some(s => s.type === p.slot.type && Z.daysBetween(p.date, s.date) >= 0 && Z.daysBetween(p.date, s.date) <= 1);
        const past = p.date < d.date;
        return `<div class="item">
          <div class="col">
            <span class="name">${done ? '✓ ' : ''}${esc(p.headline)}</span>
            <span class="meta">${DAY_NAMES[Z.isoDayOfWeek(p.date) - 1]} ${esc(Z.formatClock(p.slot.timeOfDay))}${p.isDeload ? ' · deload week' : ''}</span>
          </div>
          <span class="val" style="color:${done ? 'var(--green)' : past ? 'var(--warn)' : 'var(--t3)'};font-size:11px">
            ${done ? 'done' : past ? 'missed' : ''}</span>
        </div>`;
      }).join('')}
    </div>

    <div class="card">
      <div class="kicker">YOUR WEEK</div>
      <p class="tiny">Tap a day to change what's on it.</p>
      ${DAY_NAMES.map((name, i) => {
        const dow = i + 1;
        const slot = S.plan.find(s => s.dayOfWeek === dow && s.enabled);
        return `<button class="pill block" data-act="edit-day" data-dow="${dow}">
          <b style="display:inline-block;width:42px">${name}</b>
          <span style="color:${slot ? 'var(--green)' : 'var(--t3)'}">${slot ? esc(Z.ACTIVITY_LABEL[slot.type]) + ' · ' + esc(Z.formatClock(slot.timeOfDay)) : 'Rest'}</span>
        </button>`;
      }).join('')}
    </div>

    <p class="tiny">Running volume rises by at most 10% a week and every fourth week backs off. That ramp is deliberately boring — the usual way a motivated beginner loses a month is two enthusiastic weeks followed by an injury.</p>`;
}

// ---- Progress ------------------------------------------------------------

function progressView(d) {
  if (!d.ready) return `<div class="empty">Finish setup first.</div>`;
  const w = d.week;
  const goalDate = Z.projectGoalDate(d.trend, +S.profile.goalWeightKg, d.date);

  return `
    <div><h1 class="screen">Progress</h1><p class="sub">The scale lies daily. The trend doesn't.</p></div>

    ${d.trend.points.length > 1 ? `<div class="card">
      <div class="kicker">WEIGHT</div>
      ${weightChart(d.trend, +S.profile.goalWeightKg)}
      <div class="chart-legend">
        <span class="legend-key"><span class="legend-swatch" style="background:var(--t3)"></span>weigh-ins</span>
        <span class="legend-key"><span class="legend-swatch" style="background:var(--mint)"></span>trend</span>
        <span class="legend-key"><span class="legend-swatch" style="background:var(--violet)"></span>goal</span>
      </div>
      ${goalDate ? `<p class="tiny">At this rate you hit ${Z.kgToLb(+S.profile.goalWeightKg).toFixed(0)} lb around <b style="color:var(--ink)">${esc(prettyDate(goalDate))}</b>.</p>`
        : `<p class="tiny">Not enough consistent movement yet to project a goal date.</p>`}
    </div>` : `<div class="empty">Log a few weigh-ins and the trend line appears here.</div>`}

    <div class="card">
      <div class="kicker">LAST 7 FULL DAYS</div>
      ${w.isEmpty ? `<p class="tiny">Nothing logged this week yet.</p>` : `
        ${statLine('Average intake', `${Z.fmt(w.averageIntakeKcal)} kcal`, `target ${Z.fmt(d.target.targetKcal)}`)}
        ${statLine('Average burn', `${Z.fmt(w.averageBurnKcal)} kcal`, 'from logged sessions')}
        ${statLine('Adherence', `${w.adherencePercent}%`, 'days inside the plan')}
        ${statLine('Projected', Z.formatRateLb(w.projectedWeeklyKg), 'a week, from how you ate')}`}
    </div>

    <div class="card">
      <div class="kicker">MAINTENANCE</div>
      ${statLine('Formula estimate', `${Z.fmt(Math.round(d.formula))} kcal`, 'Mifflin-St Jeor × activity')}
      ${d.adaptive.measuredTdeeKcal != null
        ? `${statLine('Measured from your data', `${Z.fmt(d.adaptive.measuredTdeeKcal)} kcal`, `${d.adaptive.daysOfData} days · ${d.adaptive.confidence.toLowerCase()} confidence`)}
           ${statLine('In use', `${Z.fmt(d.maintenance)} kcal`, 'blended toward the measurement')}`
        : `<p class="tiny">Zephyr needs ${Z.ADAPTIVE_MIN_DAYS} days of food logs plus weigh-ins before it can measure what you actually burn. It has ${d.adaptive.daysOfData}.</p>`}
    </div>

    <div class="card">
      <div class="kicker">STREAK</div>
      <div class="row">
        <div class="col"><span style="font-size:30px;font-weight:700;font-variant-numeric:tabular-nums">${d.streak.current}</span><span class="tiny">current</span></div>
        <div class="col" style="text-align:right"><span style="font-size:18px;font-weight:650;color:var(--muted)">${d.streak.longest}</span><span class="tiny">longest</span></div>
      </div>
      <p class="tiny">A day counts if you logged your food and either stayed in budget, hit your steps, or trained. Showing up counts.</p>
    </div>`;
}

function statLine(label, value, note) {
  return `<div class="stat-line"><div class="col"><span class="l">${esc(label)}</span><span class="n">${esc(note)}</span></div><span class="v">${esc(value)}</span></div>`;
}

function weightChart(trend, goalKg) {
  const pts = trend.points;
  const W = 340, H = 168, pad = 10;
  const values = pts.flatMap(p => [p.trendKg, p.rawKg]).filter(v => v != null).concat([goalKg]);
  const lo = Math.min(...values) - 0.4, hi = Math.max(...values) + 0.4;
  const span = Math.max(hi - lo, 0.8);
  const x = i => pad + (pts.length === 1 ? 0 : i * (W - pad * 2) / (pts.length - 1));
  const y = v => H - pad - (v - lo) / span * (H - pad * 2);

  const trendPath = pts.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.trendKg).toFixed(1)}`).join('');
  const dots = pts.map((p, i) => p.rawKg == null ? '' :
    `<circle cx="${x(i).toFixed(1)}" cy="${y(p.rawKg).toFixed(1)}" r="2" fill="#66727F"/>`).join('');
  const goalY = goalKg >= lo && goalKg <= hi ? y(goalKg) : null;

  return `<svg class="chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="Weight trend">
    ${goalY != null ? `<line x1="${pad}" y1="${goalY.toFixed(1)}" x2="${W - pad}" y2="${goalY.toFixed(1)}" stroke="#7C6BFF" stroke-width="1" stroke-dasharray="4 4" opacity=".8"/>` : ''}
    ${dots}
    <path d="${trendPath}" fill="none" stroke="#00E5A0" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>
  </svg>`;
}

// ---- Onboarding ----------------------------------------------------------

/**
 * One question per screen.
 *
 * A single long form is faster to build and worse to use: it shows everything being asked of you at
 * once, which reads as a chore. Asking one thing at a time makes each answer feel like progress,
 * and it gives every step room for a real question and a real reason.
 *
 * The last screen is the payoff — the number, revealed. Everything before it is earning that.
 */
const OB_STEPS = ['intro', 'sex', 'age', 'height', 'weight', 'goal', 'pace', 'activity', 'reveal'];

function obDraft() {
  S.ob = S.ob ?? { sex: '', age: '', ft: '', inch: '', lb: '', goalLb: '', pace: '', activity: '' };
  return S.ob;
}

function obStepName() { return OB_STEPS[Math.min(S.onboardStep, OB_STEPS.length - 1)]; }

/** Whether the current screen has what it needs to move on. */
function obReady() {
  const o = obDraft();
  switch (obStepName()) {
    case 'sex': return !!o.sex;
    case 'age': return +o.age >= 13 && +o.age <= 100;
    case 'height': return +o.ft >= 3 && +o.ft <= 8 && +o.inch >= 0 && +o.inch <= 11;
    case 'weight': return +o.lb >= 70 && +o.lb <= 660;
    case 'goal': return +o.goalLb >= 70 && +o.goalLb <= 660;
    case 'pace': return !!o.pace;
    case 'activity': return !!o.activity;
    default: return true;
  }
}

function obProfile() {
  const o = obDraft();
  if (!(o.age && o.ft !== '' && o.lb && o.goalLb)) return null;
  return {
    sex: o.sex || 'MALE',
    heightCm: Z.ftInToCm(+o.ft, +(o.inch || 0)),
    weightKg: Z.lbToKg(+o.lb),
    goalWeightKg: Z.lbToKg(+o.goalLb),
    ageYears: +o.age,
    activityLevel: o.activity || 'LIGHT',
    goalPace: o.pace || 'LOSE_STEADY',
  };
}

function obPlan() {
  const p = obProfile();
  if (!p) return null;
  const bmrKcal = Z.bmr(p.weightKg, p.heightCm, p.ageYears, p.sex);
  const target = Z.calorieTarget(bmrKcal * Z.ActivityLevel[p.activityLevel].multiplier, bmrKcal,
    Z.GoalPace[p.goalPace].kgPerWeek, p.sex);
  const m = Z.macros(target.targetKcal, p.goalWeightKg, Z.GoalPace[p.goalPace].kgPerWeek < 0);
  return { target, macros: m, profile: p };
}

function onboardingView() {
  const o = obDraft();
  const step = obStepName();
  const index = OB_STEPS.indexOf(step);
  const progress = index / (OB_STEPS.length - 1);

  const body = {
    intro: obIntro,
    sex: () => obChoice('First — who am I working with?', 'It changes the maths, nothing else.', 'sex', [
      ['MALE', 'Man', ''], ['FEMALE', 'Woman', ''], ['UNSPECIFIED', 'Rather not say', 'Zephyr splits the difference'],
    ], o.sex),
    age: () => obNumber('How old are you?', 'Your body burns differently at 22 than at 42.', [
      { key: 'age', label: 'Years', placeholder: '28', suffix: '' },
    ]),
    height: () => obNumber('How tall are you?', 'The single biggest input into what you burn all day.', [
      { key: 'ft', label: 'Feet', placeholder: '5', suffix: 'ft' },
      { key: 'inch', label: 'Inches', placeholder: '11', suffix: 'in' },
    ]),
    weight: () => obNumber('What do you weigh right now?', "No judgement — it's just the starting line.", [
      { key: 'lb', label: 'Pounds', placeholder: '187', suffix: 'lb' },
    ]),
    goal: () => obGoalWeight(o),
    pace: () => obChoice('How fast do you want this?', "Faster isn't better. Faster is just faster.",
      'pace', Object.values(Z.GoalPace).filter(g => g.name !== 'GAIN_SLOW')
        .map(g => [g.name, g.label, g.blurb]), o.pace),
    activity: () => obChoice('Before any workouts — how much do you move?',
      "Just your normal day. Zephyr adds training on top from what you log.",
      'activity', Object.values(Z.ActivityLevel).map(a => [a.name, a.label, '']), o.activity),
    reveal: obReveal,
  }[step]();

  const label = step === 'intro' ? 'Get started' : step === 'reveal' ? "Let's go" : 'Continue';

  return `
    <div class="ob-progress"><div class="ob-progress-fill" style="width:${(progress * 100).toFixed(0)}%"></div></div>
    <div class="ob" key="${step}">
      <div class="ob-screen">${body}</div>
      <div class="grow"></div>
      <div class="ob-actions">
        ${index > 0 ? `<button class="icon-btn" data-act="ob-back" aria-label="Back">${icon('back')}</button>` : '<span></span>'}
        <button class="btn accent grow" data-act="ob-next" ${obReady() ? '' : 'disabled'}>${label}</button>
      </div>
    </div>`;
}

function obIntro() {
  return `<div class="ob-stagger">
    <div class="ob-brand">Zephyr</div>
    <div class="ob-h">Look the way<br>you want to look.</div>
    <p class="ob-p">Eating, running, hiking, lifting — one number a day that tells you
    whether today counted. No spreadsheets. No guilt. Just the number.</p>
    <div class="ob-promise">
      <div class="ob-promise-row"><span class="dot-v"></span><span>It learns what <b>your</b> body burns, not a formula's guess</span></div>
      <div class="ob-promise-row"><span class="dot-o"></span><span>Protein set so what you lose is fat, <b>not muscle</b></span></div>
      <div class="ob-promise-row"><span class="dot-p"></span><span>Chases you when it matters, <b>shuts up when it doesn't</b></span></div>
    </div>
    <p class="ob-fine">General fitness guidance, not medical advice. Talk to a doctor
    before big changes if you have a health condition.</p>
  </div>`;
}

function obChoice(title, sub, key, options, current) {
  return `<div class="ob-stagger">
    <div class="ob-h">${esc(title)}</div>
    <p class="ob-p">${esc(sub)}</p>
    <div class="choices">
      ${options.map(([value, label, blurb]) => `
        <button class="choice ${current === value ? 'on' : ''}" data-act="ob-set" data-key="${key}" data-value="${value}">
          <span class="choice-text">
            <span class="choice-label">${esc(label)}</span>
            ${blurb ? `<span class="choice-blurb">${esc(blurb)}</span>` : ''}
          </span>
          <span class="choice-tick">${icon('check')}</span>
        </button>`).join('')}
    </div>
  </div>`;
}

function obNumber(title, sub, fields) {
  const o = obDraft();
  return `<div class="ob-stagger">
    <div class="ob-h">${esc(title)}</div>
    <p class="ob-p">${esc(sub)}</p>
    <div class="field-row big-fields">
      ${fields.map(f => `
        <div class="field">
          <label>${esc(f.label)}</label>
          <input type="number" inputmode="numeric" data-bind-ob="${f.key}"
            value="${esc(o[f.key] ?? '')}" placeholder="${esc(f.placeholder)}" />
        </div>`).join('')}
    </div>
  </div>`;
}

function obGoalWeight(o) {
  const now = +o.lb || 0;
  const goal = +o.goalLb || 0;
  const diff = now && goal ? now - goal : 0;

  return `<div class="ob-stagger">
    <div class="ob-h">Where do you want to be?</div>
    <p class="ob-p">Pick the number you actually want. Zephyr will tell you honestly if it's too fast.</p>
    <div class="field big-fields">
      <label>Goal weight</label>
      <input type="number" inputmode="numeric" data-bind-ob="goalLb" value="${esc(o.goalLb ?? '')}" placeholder="${now ? Math.round(now - 15) : '170'}" />
    </div>
    ${diff > 0 ? `<div class="callout good"><b>${diff.toFixed(0)} lb to go.</b> That's the whole job — and it's a smaller number than it feels like right now.</div>`
      : diff < 0 ? `<div class="callout good"><b>${Math.abs(diff).toFixed(0)} lb to put on.</b> Zephyr will make sure it's the good kind.</div>` : ''}
  </div>`;
}

/**
 * The payoff screen. Everything before this was earning the right to show a number, so it gets the
 * full-width treatment and counts up rather than simply appearing.
 */
function obReveal() {
  const plan = obPlan();
  if (!plan) return `<div class="ob-h">Fill in the earlier steps and your plan appears here.</div>`;

  const { target, macros, profile } = plan;
  const lbPerWeek = Math.abs(Z.kgToLb(target.effectiveKgPerWeek));
  const weeks = Math.abs(Z.kgToLb(profile.weightKg - profile.goalWeightKg)) / (lbPerWeek || 1);

  return `<div class="ob-stagger">
    <div class="kicker">Your daily number</div>
    <div class="reveal-number" data-countup="${target.targetKcal}">0</div>
    <div class="reveal-unit">calories a day</div>

    <div class="reveal-grid">
      <div class="tile t-green"><div class="v">${macros.proteinG}g</div><div class="l">Protein — the muscle-saver</div></div>
      <div class="tile t-orange"><div class="v">${macros.carbsG}g</div><div class="l">Carbs — your fuel</div></div>
      <div class="tile t-sky"><div class="v">${macros.fatG}g</div><div class="l">Fat — keeps you sane</div></div>
      <div class="tile t-violet"><div class="v">${Z.fmt(target.maintenanceKcal)}</div><div class="l">What you burn doing nothing</div></div>
    </div>

    ${target.adjustment !== Z.TargetAdjustment.NONE
      ? `<div class="callout">${esc(clampCopy(target))}</div>`
      : weeks > 0 && isFinite(weeks) && weeks < 200
        ? `<div class="callout good">Hit this most days and you're there in about
           <b>${Math.round(weeks)} weeks</b> — losing ${lbPerWeek.toFixed(1)} lb a week.
           You'll notice it in the mirror before the scale catches up.</div>` : ''}
  </div>`;
}

function clampCopy(target) {
  if (target.adjustment === Z.TargetAdjustment.CAPPED_TO_PERCENTAGE) {
    return `That pace needed a bigger cut than is safe, so Zephyr eased it to ${Math.abs(Z.kgToLb(target.effectiveKgPerWeek)).toFixed(1)} lb a week. Going faster costs muscle — which is the opposite of looking better.`;
  }
  return 'Zephyr raised your target to keep it above what your body burns at rest. Eating under that slows your metabolism and eats muscle.';
}

/** Counts the hero number up on the reveal screen. A number that lands feels earned. */
function runCountUps() {
  app.querySelectorAll('[data-countup]').forEach(node => {
    const end = +node.dataset.countup;
    if (!end) return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      node.textContent = Z.fmt(end);
      return;
    }
    const started = performance.now();
    const duration = 900;
    const tick = t => {
      const p = Math.min((t - started) / duration, 1);
      // Ease-out cubic: fast at first, settling into the final figure.
      node.textContent = Z.fmt(Math.round(end * (1 - Math.pow(1 - p, 3))));
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
}

// ---- Sheets --------------------------------------------------------------

function sheetView(d) {
  const inner = {
    food: foodSheet, weigh: weighSheet, coach: coachSheet, settings: settingsSheet,
    sim: simSheet, manual: manualSheet, lift: liftSheet, day: daySheet,
  }[sheet.kind];
  return `<div class="scrim" data-act="close-scrim"><div class="sheet">${inner(d)}</div></div>`;
}

function sheetHead(title) {
  return `<div class="grabber"></div>
    <div class="sheet-head"><h2>${esc(title)}</h2>
    <button class="icon-btn" data-act="close" aria-label="Close">${icon('x')}</button></div>`;
}

function foodSheet(d) {
  const q = (sheet.query ?? '').toLowerCase();
  const matches = q ? FOODS.filter(f => f[0].toLowerCase().includes(q)).slice(0, 8) : [];
  const recents = [...new Map(S.food.map(f => [f.name, f])).values()].slice(-6).reverse();

  return `${sheetHead('Add food')}
    <div class="pills">${['Breakfast', 'Lunch', 'Dinner', 'Snack'].map(s =>
      `<button class="pill ${(sheet.slot ?? autoSlot()) === s ? 'on' : ''}" data-act="set-slot" data-slot="${s}">${s}</button>`).join('')}</div>

    <div class="field"><label>SEARCH</label>
      <input type="text" data-bind-sheet="query" value="${esc(sheet.query ?? '')}" placeholder="chicken, oats, banana…" autocomplete="off"></div>

    ${matches.length ? `<div class="list">${matches.map(f => `
      <button class="item tap" style="width:100%;border:0;font-family:inherit;text-align:left;color:inherit"
        data-act="pick-food" data-name="${esc(f[0])}" data-k="${f[1]}" data-p="${f[2]}" data-c="${f[3]}" data-f="${f[4]}">
        <div class="col"><span class="name">${esc(f[0])}</span><span class="meta">per 100g · ${f[1]} kcal · ${f[2]}P</span></div>
        <span class="val">+</span></button>`).join('')}</div>` : ''}

    ${!q && recents.length ? `<div><div class="kicker" style="margin-bottom:8px">RECENT</div>
      <div class="list">${recents.map(f => `
        <button class="item tap" style="width:100%;border:0;font-family:inherit;text-align:left;color:inherit"
          data-act="repeat-food" data-id="${f.id}">
          <div class="col"><span class="name">${esc(f.name)}</span><span class="meta">${Z.fmt(f.kcal)} kcal</span></div>
          <span class="val">+</span></button>`).join('')}</div></div>` : ''}

    <div class="divider"></div>
    <div class="kicker">QUICK ADD</div>
    <div class="field-row">
      <div class="field"><label>NAME</label><input type="text" data-bind-sheet="name" value="${esc(sheet.name ?? '')}" placeholder="Lunch out"></div>
      <div class="field"><label>KCAL</label><input type="number" inputmode="numeric" data-bind-sheet="kcal" value="${esc(sheet.kcal ?? '')}" placeholder="650"></div>
    </div>
    <div class="field"><label>PROTEIN (G) — OPTIONAL</label><input type="number" inputmode="numeric" data-bind-sheet="protein" value="${esc(sheet.protein ?? '')}" placeholder="35"></div>
    <button class="btn wide" data-act="quick-add" ${sheet.kcal ? '' : 'disabled'}>Add</button>`;
}

function autoSlot() {
  const h = nowHour();
  return h < 11 ? 'Breakfast' : h < 15 ? 'Lunch' : h < 21 ? 'Dinner' : 'Snack';
}

function weighSheet(d) {
  const lastKg = S.weights.length ? S.weights[S.weights.length - 1].weightKg : +S.profile.weightKg;
  return `${sheetHead('Log weight')}
    <p class="tiny">Same time every day — first thing, after the bathroom, before you eat. The daily number bounces around on water alone; Zephyr smooths it, but being consistent makes the trend honest sooner.</p>
    <div class="field big-fields"><label>Weight (lb)</label>
      <input type="number" inputmode="decimal" step="0.1" data-bind-sheet="weight" value="${esc(sheet.weight ?? Z.kgToLb(lastKg).toFixed(1))}"></div>
    <button class="btn accent wide" data-act="save-weight">Save</button>`;
}

function coachSheet(d) {
  const pending = pendingNudges(d);
  const timeline = dayTimeline(d);

  return `${sheetHead('Coach')}
    <p class="tiny">On your phone these arrive as notifications. Here you can see exactly what Zephyr
    would say and when — the whole day at once, so its voice can be judged without waiting for it.</p>

    <div class="kicker">RIGHT NOW · ${clockLabel()}</div>
    ${pending.length ? pending.map(n => notifCard(n)).join('')
      : `<div class="empty">Nothing worth saying right now.<br>That's the app working, not failing.</div>`}

    <div class="divider"></div>
    <div class="kicker">EVERYTHING IT WOULD SEND TODAY · ${timeline.filter(n => !n.isCelebration).length} of ${S.coach.maxPerDay} allowed</div>
    ${timeline.length ? timeline.map(n => `
      <div style="display:flex;gap:10px;align-items:flex-start">
        <span class="tiny" style="width:42px;flex:none;padding-top:12px;font-variant-numeric:tabular-nums">${n.at}</span>
        <div class="grow">${notifCard(n)}</div>
      </div>`).join('')
      : `<div class="empty">Silent all day at this tone and state.</div>`}`;
}

function notifCard(n) {
  const colour = { PLAN: 'var(--violet)', SESSION: 'var(--sky)', MOVEMENT: 'var(--sky)', FOOD: 'var(--green)', STREAK: 'var(--pink)', CELEBRATION: 'var(--green)' }[n.category.id];
  return `<div class="notif">
    <div class="top"><span class="dot" style="background:${colour}"></span><span>ZEPHYR · ${esc(n.category.channelName.toUpperCase())}</span></div>
    <div class="t">${esc(n.title)}</div>
    <div class="b">${esc(n.body)}</div>
    ${n.actions.length ? `<div class="acts">${n.actions.map(a => `<button data-act="nudge-action" data-a="${esc(a)}">${esc(a)}</button>`).join('')}</div>` : ''}
  </div>`;
}

function settingsSheet(d) {
  const c = S.coach;
  return `${sheetHead('Settings')}
    <div class="kicker">COACH TONE</div>
    <div class="pills" style="flex-direction:column">
      ${Object.values(Z.CoachTone).map(t => `
        <button class="pill block ${c.tone === t.id ? 'on' : ''}" data-act="set-tone" data-tone="${t.id}">
          <b>${esc(t.label)}</b> — ${esc(t.description)}</button>`).join('')}
    </div>

    <div class="field-row">
      <div class="field"><label>QUIET FROM</label><input type="time" data-bind-quiet="start" value="${c.quietHours.start}"></div>
      <div class="field"><label>QUIET UNTIL</label><input type="time" data-bind-quiet="end" value="${c.quietHours.end}"></div>
    </div>

    <div class="kicker">NOTIFICATION TYPES</div>
    <div class="pills" style="flex-direction:column">
      ${Object.values(Z.NudgeCategory).map(cat => `
        <button class="pill block ${c.enabledCategories.includes(cat.id) ? 'on' : ''}" data-act="toggle-cat" data-cat="${cat.id}">
          <b>${esc(cat.channelName)}</b> — ${esc(cat.description)}</button>`).join('')}
    </div>

    <div class="divider"></div>
    <div class="kicker">STEP GOAL</div>
    <div class="field"><label>DAILY STEPS</label>
      <input type="number" inputmode="numeric" step="250" data-bind-step value="${S.stepGoal}"></div>
    ${(() => {
      const suggestion = Z.suggestStepGoal(Object.entries(S.steps).map(([date, steps]) => ({ date, steps })), S.stepGoal);
      return suggestion.baselineMedian ? `<p class="tiny">${esc(suggestion.reason)}${suggestion.goal !== S.stepGoal
        ? ` <button class="notif-act" style="background:none;border:0;color:var(--green);font-weight:650;cursor:pointer;font-family:inherit;font-size:11px" data-act="accept-goal" data-goal="${suggestion.goal}">Use ${Z.fmt(suggestion.goal)}</button>` : ''}</p>` : '';
    })()}

    <div class="divider"></div>
    <div class="kicker">GOAL</div>
    <div class="pills" style="flex-direction:column">
      ${Object.values(Z.GoalPace).map(g =>
        `<button class="pill block ${S.profile.goalPace === g.name ? 'on' : ''}" data-act="set" data-key="goalPace" data-value="${g.name}">${esc(g.label)}</button>`).join('')}
    </div>

    <div class="divider"></div>
    <button class="btn warn wide" data-act="reset">Erase everything and start over</button>

    <div class="center" style="margin-top:6px">
      <div class="kicker">Build</div>
      <div class="tiny" style="font-variant-numeric:tabular-nums">${esc(typeof BUILD_STAMP === 'string' ? BUILD_STAMP : 'dev')}</div>
      <div class="tiny" style="margin-top:6px">Seeing something old? Re-download, then hard-refresh with ⌘⇧R.</div>
    </div>`;
}

function simSheet(d) {
  return `${sheetHead('Simulator')}
    <p class="tiny">A browser can't read your phone's pedometer or GPS, and waiting a fortnight to see
    the adaptive maintenance switch on is no way to evaluate it. These controls stand in for time and
    hardware. Everything they feed goes through the same real logic.</p>

    <div class="kicker">TIME · currently ${clockLabel()}</div>
    <div class="pills">
      ${[['-60', '−1h'], ['-15', '−15m'], ['15', '+15m'], ['60', '+1h'], ['180', '+3h']].map(([v, l]) =>
        `<button class="pill" data-act="shift-time" data-min="${v}">${l}</button>`).join('')}
      <button class="pill" data-act="reset-time">Now</button>
    </div>

    <div class="kicker">STEPS TODAY · ${Z.fmt(S.steps[d.date] ?? 0)}</div>
    <div class="pills">
      ${[500, 1500, 3000].map(n => `<button class="pill" data-act="add-steps" data-n="${n}">+${Z.fmt(n)}</button>`).join('')}
      <button class="pill" data-act="add-steps" data-n="-100000">Clear</button>
      <button class="pill" data-act="typical-steps">Typical day so far</button>
    </div>

    <div class="divider"></div>
    <div class="kicker">HISTORY</div>
    <p class="tiny">Adaptive maintenance needs ${Z.ADAPTIVE_MIN_DAYS}+ days of food logs and weigh-ins before it will
    say anything. This fabricates a plausible past so you can see it working today.</p>
    <button class="btn ghost wide" data-act="seed">Fill in the last 30 days</button>
    <button class="btn ghost wide" data-act="clear-history">Clear history, keep my profile</button>`;
}

function manualSheet(d) {
  return `${sheetHead('Add a session')}
    <div class="pills">${['RUN', 'HIKE', 'WALK', 'CYCLE'].map(t =>
      `<button class="pill ${(sheet.type ?? 'RUN') === t ? 'on' : ''}" data-act="set-type" data-type="${t}">${Z.ACTIVITY_LABEL[t]}</button>`).join('')}</div>
    <div class="field-row">
      <div class="field"><label>Distance (mi)</label><input type="number" inputmode="decimal" step="0.1" data-bind-sheet="km" value="${esc(sheet.km ?? '')}" placeholder="5"></div>
      <div class="field"><label>Minutes</label><input type="number" inputmode="numeric" data-bind-sheet="min" value="${esc(sheet.min ?? '')}" placeholder="45"></div>
    </div>
    <div class="field"><label>Climb (ft) — optional</label><input type="number" inputmode="numeric" data-bind-sheet="elev" value="${esc(sheet.elev ?? '')}" placeholder="400"></div>
    ${sheet.km && sheet.min ? (() => {
      const burn = Z.activityBurn(sheet.type ?? 'RUN', +sheet.min * 60, +sheet.km * Z.M_PER_MILE, +(sheet.elev || 0) / Z.FT_PER_M, +S.profile.weightKg);
      return `<div class="callout mint">${burn.netKcal} kcal added to today's budget.
      That's net of the ${burn.restingKcal} kcal you'd have burned resting anyway — counting the gross
      figure would hand back calories you never earned.</div>`;
    })() : ''}
    <button class="btn wide" data-act="save-manual" ${sheet.km && sheet.min ? '' : 'disabled'}>Save</button>`;
}

function liftSheet(d) {
  const exercises = ['Back Squat', 'Deadlift', 'Bench Press', 'Overhead Press', 'Barbell Row', 'Pull-up', 'Romanian Deadlift', 'Lunge'];
  const chosen = sheet.exercise ?? exercises[0];
  const history = S.strength.filter(w => w.exercise === chosen);
  const last = history[history.length - 1];
  const next = last ? Z.nextStrengthPrescription(last.sets, [8, 12], 0, /squat|deadlift|lunge|leg/i.test(chosen)) : null;

  return `${sheetHead('Log a lift')}
    <div class="field"><label>EXERCISE</label>
      <select data-bind-sheet="exercise">${exercises.map(e => `<option ${e === chosen ? 'selected' : ''}>${esc(e)}</option>`).join('')}</select></div>
    ${next ? `<div class="callout mint"><b>${next.weightKg} kg × ${next.targetReps}</b> — ${esc(next.note)}</div>`
      : `<p class="tiny">First time logging this. Enter what you did and Zephyr will tell you what to do next time.</p>`}
    <div class="field-row">
      <div class="field"><label>WEIGHT (KG)</label><input type="number" inputmode="decimal" step="1.25" data-bind-sheet="weight" value="${esc(sheet.weight ?? next?.weightKg ?? '')}"></div>
      <div class="field"><label>REPS</label><input type="number" inputmode="numeric" data-bind-sheet="reps" value="${esc(sheet.reps ?? next?.targetReps ?? '')}"></div>
      <div class="field"><label>SETS</label><input type="number" inputmode="numeric" data-bind-sheet="sets" value="${esc(sheet.sets ?? 3)}"></div>
    </div>
    <button class="btn wide" data-act="save-lift" ${sheet.weight && sheet.reps ? '' : 'disabled'}>Save</button>`;
}

function daySheet(d) {
  const dow = sheet.dow;
  const current = S.plan.find(s => s.dayOfWeek === dow && s.enabled);
  const types = ['RUN', 'HIKE', 'WALK', 'STRENGTH'];
  return `${sheetHead(DAY_NAMES[dow - 1])}
    <div class="pills" style="flex-direction:column">
      <button class="pill block ${!current ? 'on' : ''}" data-act="set-day-type" data-type="">Rest day</button>
      ${types.map(t => `<button class="pill block ${current?.type === t ? 'on' : ''}" data-act="set-day-type" data-type="${t}">${Z.ACTIVITY_LABEL[t]}</button>`).join('')}
    </div>
    ${current ? `<div class="field"><label>TIME</label><input type="time" data-bind-day value="${current.timeOfDay}"></div>` : ''}
    <p class="tiny">Zephyr decides the distance and load; you decide the days. A schedule imposed by an app collides with real life and gets abandoned.</p>`;
}

// ---- Small view helpers --------------------------------------------------

/**
 * The hero ring, drawn white-on-colour since it now sits on the violet card.
 *
 * Progress stops at full rather than lapping: a ring that starts a second lap makes overeating look
 * like an achievement. The inner ring is steps, so one glance covers both.
 */
function ring(calorieFraction, stepFraction, over, centerHtml) {
  const R1 = 98, R2 = 79;
  const c1 = 2 * Math.PI * R1, c2 = 2 * Math.PI * R2;
  const f1 = Z.clamp(calorieFraction, 0, 1), f2 = Z.clamp(stepFraction, 0, 1);

  return `<div class="ring-wrap"><svg viewBox="0 0 218 218" aria-hidden="true">
      <circle cx="109" cy="109" r="${R1}" fill="none" stroke="#fff" stroke-opacity=".22" stroke-width="17"/>
      <circle cx="109" cy="109" r="${R1}" fill="none" stroke="#fff" stroke-width="17"
        stroke-linecap="round" stroke-dasharray="${(c1 * f1).toFixed(1)} ${c1.toFixed(1)}" transform="rotate(-90 109 109)"/>
      <circle cx="109" cy="109" r="${R2}" fill="none" stroke="#fff" stroke-opacity=".16" stroke-width="7"/>
      <circle cx="109" cy="109" r="${R2}" fill="none" stroke="${over ? '#FFE0AE' : '#B9FFE6'}" stroke-width="7"
        stroke-linecap="round" stroke-dasharray="${(c2 * f2).toFixed(1)} ${c2.toFixed(1)}" transform="rotate(-90 109 109)"/>
    </svg><div class="ring-center">${centerHtml}</div></div>`;
}

function bar(name, value, fraction, colour) {
  return `<div><div class="bar-head"><span class="bar-name">${esc(name)}</span><span class="bar-val">${esc(value)}</span></div>
    <div class="track"><div class="fill" style="width:${(Z.clamp(fraction, 0, 1) * 100).toFixed(1)}%;background:${colour}"></div></div></div>`;
}

/**
 * A colour-blocked stat tile. The hue carries the meaning — sky is always steps, green always
 * protein — so after a day or two the screen can be read without reading any labels.
 */
function tile(tone, ic, value, label, fraction = null) {
  return `<div class="tile ${tone}">
    <div class="tile-top">
      <span class="tile-icon">${icon(ic)}</span>
      ${fraction != null ? `<span style="font-size:12px;font-weight:750;opacity:.7">${Math.round(Z.clamp(fraction, 0, 1) * 100)}%</span>` : ''}
    </div>
    <div class="v">${esc(value)}</div>
    <div class="l">${esc(label)}</div>
  </div>`;
}

function hms(totalSeconds) {
  const s = Math.max(0, Math.round(totalSeconds));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${m}:${String(sec).padStart(2, '0')}`;
}

function prettyDate(iso) {
  const d = new Date(iso + 'T00:00:00Z');
  return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' });
}

const ICONS = {
  ring: '<circle cx="12" cy="12" r="8"/><path d="M12 4a8 8 0 018 8"/>',
  fork: '<path d="M7 3v7a2 2 0 004 0V3M9 10v11M17 3c-1.5 1-2 3-2 5s.5 3 2 3v10"/>',
  run: '<circle cx="14" cy="4.5" r="1.6"/><path d="M8 21l2.5-5 3-2-1-5-3 2-2 3M14 10l3 2 3-1"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
  chart: '<path d="M4 19V5M4 19h16M7 15l4-5 3 3 5-7"/>',
  bell: '<path d="M18 8a6 6 0 10-12 0c0 6-3 7-3 7h18s-3-1-3-7M13.7 21a2 2 0 01-3.4 0"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-2.9 1.2 2 2 0 11-4 0 1.7 1.7 0 00-2.9-1.2l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.7 1.7 0 004 15a2 2 0 110-4 1.7 1.7 0 001.2-2.9l-.1-.1a2 2 0 112.8-2.8l.1.1A1.7 1.7 0 0011 4a2 2 0 114 0 1.7 1.7 0 002.9 1.2l.1-.1a2 2 0 112.8 2.8l-.1.1A1.7 1.7 0 0020 11a2 2 0 110 4z"/>',
  sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  x: '<path d="M18 6L6 18M6 6l12 12"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  flame: '<path d="M12 2s5 5 5 9a5 5 0 01-10 0c0-2 1-3.5 1-3.5S9 11 11 11s1-9 1-9z"/>',
  bolt: '<path d="M13 2L4 14h7l-1 8 9-12h-7l1-8z"/>',
  leaf: '<path d="M11 20A7 7 0 019 13c0-5 4-9 11-9 0 8-3 12-9 12z"/><path d="M5 21c0-4 2-7 5-8"/>',
};

function icon(name, colour = 'currentColor') {
  return `<svg viewBox="0 0 24 24" fill="none" stroke="${colour}" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] ?? ''}</svg>`;
}

// ---------------------------------------------------------------------------
// Interaction
// ---------------------------------------------------------------------------

function wire(d) {
  app.querySelectorAll('[data-tab]').forEach(b => b.onclick = () => { S.tab = b.dataset.tab; save(); render(); });

  app.querySelectorAll('[data-bind-ob]').forEach(input => {
    input.oninput = () => {
      obDraft()[input.dataset.bindOb] = input.value;
      save();
      const next = app.querySelector('[data-act="ob-next"]');
      if (next) next.disabled = !obReady();
    };
    // Enter should advance, the way every other one-question-per-screen flow behaves.
    input.onkeydown = e => {
      if (e.key === 'Enter' && obReady()) { e.preventDefault(); act({ act: 'ob-next' }, null); }
    };
  });
  app.querySelectorAll('[data-bind-sheet]').forEach(input => {
    input.oninput = () => {
      sheet[input.dataset.bindSheet] = input.value;
      // Re-render for the panels that show live derived feedback as you type.
      if (['query', 'km', 'min', 'elev', 'exercise'].includes(input.dataset.bindSheet)) {
        const pos = input.selectionStart; render();
        const again = app.querySelector(`[data-bind-sheet="${input.dataset.bindSheet}"]`);
        if (again) { again.focus(); try { again.setSelectionRange(pos, pos); } catch {} }
      } else {
        const btn = app.querySelector('.sheet button.btn.wide');
        if (btn) btn.disabled = !(sheet.kcal || sheet.weight || (sheet.km && sheet.min));
      }
    };
  });
  app.querySelectorAll('[data-bind-quiet]').forEach(i => {
    i.onchange = () => { S.coach.quietHours[i.dataset.bindQuiet] = i.value; save(); render(); };
  });
  const stepInput = app.querySelector('[data-bind-step]');
  if (stepInput) stepInput.onchange = () => {
    S.stepGoal = Z.clamp(+stepInput.value || Z.STEP_GOAL_DEFAULT, 500, 50000);
    S.stepGoalManual = true; save(); render();
  };
  const dayTime = app.querySelector('[data-bind-day]');
  if (dayTime) dayTime.onchange = () => {
    const slot = S.plan.find(s => s.dayOfWeek === sheet.dow && s.enabled);
    if (slot) { slot.timeOfDay = dayTime.value; save(); render(); }
  };

  app.querySelectorAll('[data-act]').forEach(node => {
    node.onclick = e => { e.stopPropagation(); act(node.dataset, d, node); };
  });
}

/** No longer needed: the onboarding button state is refreshed inline as you type. */

function act(data, d, node) {
  const a = data.act;

  switch (a) {
    case 'ob-next':
      if (obStepName() === 'reveal') { finishOnboarding(); return; }
      S.onboardStep = Math.min(S.onboardStep + 1, OB_STEPS.length - 1);
      break;
    case 'ob-back': S.onboardStep = Math.max(0, S.onboardStep - 1); break;
    case 'ob-set': {
      obDraft()[data.key] = data.value;
      // Picking an option is an answer, so move on rather than making them reach for Continue.
      if (obReady() && obStepName() !== 'reveal') {
        save(); render();
        setTimeout(() => { S.onboardStep = Math.min(S.onboardStep + 1, OB_STEPS.length - 1); save(); render(); runCountUps(); }, 260);
        return;
      }
      break;
    }
    case 'set': S.profile[data.key] = data.value; break;
    case 'pick-date': S.selectedDate = data.date === todayISO() ? null : data.date; break;
    case 'tab-food': S.tab = 'food'; break;

    case 'tab-plan': S.tab = 'plan'; break;
    case 'add-food': sheet = { kind: 'food', slot: data.slot ?? autoSlot() }; break;
    case 'weigh': sheet = { kind: 'weigh' }; break;
    case 'coach': sheet = { kind: 'coach' }; break;
    case 'settings': sheet = { kind: 'settings' }; break;
    case 'sim': sheet = { kind: 'sim' }; break;
    case 'manual-session': sheet = { kind: 'manual', type: 'RUN' }; break;
    case 'add-lift': sheet = { kind: 'lift' }; break;
    case 'edit-day': sheet = { kind: 'day', dow: +data.dow }; break;
    case 'close': case 'close-scrim': sheet = null; break;

    case 'set-slot': sheet.slot = data.slot; break;
    case 'set-type': sheet.type = data.type; break;

    case 'pick-food': {
      // Library values are per 100g; one serving is assumed to be 100g here.
      addFood(data.name, +data.k, +data.p, +data.c, +data.f);
      sheet = null; break;
    }
    case 'repeat-food': {
      const src = S.food.find(f => f.id === data.id);
      if (src) addFood(src.name, src.kcal, src.proteinG, src.carbsG, src.fatG);
      sheet = null; break;
    }
    case 'quick-add':
      addFood(sheet.name || 'Quick add', +sheet.kcal || 0, +(sheet.protein || 0), 0, 0);
      sheet = null; break;
    case 'del-food': S.food = S.food.filter(f => f.id !== data.id); break;

    case 'save-weight': {
      const kg = Z.lbToKg(+sheet.weight);
      if (kg >= 30 && kg <= 300) {
        S.weights = S.weights.filter(w => w.date !== d.date).concat({ date: d.date, weightKg: kg }).sort((x, y) => x.date.localeCompare(y.date));
        // Targets derive from body weight, so the profile has to move with it.
        S.profile.weightKg = String(kg);
      }
      sheet = null; break;
    }

    case 'start': startSession(data.type); break;
    case 'live-pause': live.paused = !live.paused; break;
    case 'live-finish': finishSession(); break;
    case 'save-manual': {
      const miles = +sheet.km, min = +sheet.min;
      const metres = miles * Z.M_PER_MILE;
      const elev = +(sheet.elev || 0) / Z.FT_PER_M;
      const type = sheet.type ?? 'RUN';
      const burn = Z.activityBurn(type, min * 60, metres, elev, +S.profile.weightKg);
      S.sessions.push({
        id: uid(), date: d.date, type, durationSeconds: min * 60,
        distanceMetres: metres, elevationGainMetres: elev, kcal: burn.kcal, netKcal: burn.netKcal,
      });
      addStepsFor(d.date, Math.round(metres / 0.75));
      sheet = null; break;
    }
    case 'del-session': S.sessions = S.sessions.filter(s => s.id !== data.id); break;

    case 'save-lift': {
      const sets = Array.from({ length: +sheet.sets || 3 }, () => ({ reps: +sheet.reps, weightKg: +sheet.weight }));
      S.strength.push({ id: uid(), date: d.date, exercise: sheet.exercise ?? 'Back Squat', sets });
      const burn = Z.activityBurn('STRENGTH', 45 * 60, 0, 0, +S.profile.weightKg);
      S.sessions.push({
        id: uid(), date: d.date, type: 'STRENGTH', durationSeconds: 45 * 60,
        distanceMetres: 0, elevationGainMetres: 0, kcal: burn.kcal, netKcal: burn.netKcal,
      });
      sheet = null; break;
    }

    case 'set-day-type': {
      S.plan = S.plan.filter(s => s.dayOfWeek !== sheet.dow);
      if (data.type) {
        S.plan.push({
          id: Date.now() % 100000, dayOfWeek: sheet.dow, type: data.type,
          timeOfDay: data.type === 'RUN' ? '07:00' : data.type === 'HIKE' ? '09:00' : '18:00',
          enabled: true,
        });
      }
      break;
    }

    case 'set-tone':
      S.coach.tone = data.tone;
      S.coach.maxPerDay = Z.CoachTone[data.tone].maxPerDay;
      break;
    case 'toggle-cat': {
      const set = new Set(S.coach.enabledCategories);
      set.has(data.cat) ? set.delete(data.cat) : set.add(data.cat);
      S.coach.enabledCategories = [...set];
      break;
    }
    case 'accept-goal': S.stepGoal = +data.goal; S.stepGoalManual = false; break;

    case 'shift-time': S.clockOffsetMinutes += +data.min; break;
    case 'reset-time': S.clockOffsetMinutes = 0; break;
    case 'add-steps': addStepsFor(d.date, +data.n); break;
    case 'typical-steps': {
      const expected = Math.round(S.stepGoal * Z.expectedStepFraction(nowHour(), nowMinute()));
      S.steps[d.date] = Math.max(0, expected - 300 + Math.round(Math.random() * 600));
      break;
    }
    case 'seed': seedHistory(d.date); break;
    case 'clear-history':
      S.food = []; S.weights = []; S.sessions = []; S.strength = []; S.steps = {}; S.nudgeLog = [];
      break;

    case 'nudge-action': {
      const label = data.a;
      sheet = label === 'Log it' ? { kind: 'food', slot: autoSlot() }
        : label === 'Log weight' ? { kind: 'weigh' }
          : null;
      if (label === 'Start walk') { sheet = null; S.tab = 'move'; startSession('WALK'); }
      else if (label === 'Start') { sheet = null; S.tab = 'move'; }
      else if (label === 'View plan') { sheet = null; S.tab = 'plan'; }
      else if (label === 'Open') { sheet = null; S.tab = 'today'; }
      break;
    }

    case 'reset':
      if (confirm('Erase all Zephyr data on this device and start over?')) {
        localStorage.removeItem(STORAGE_KEY); S = blank(); sheet = null;
      }
      break;

    default: return;
  }

  save();
  render();
}

function addFood(name, kcal, proteinG, carbsG, fatG) {
  S.food.push({
    // Lands on the day being viewed, so last night's dinner can be added this morning.
    id: uid(), date: viewDate(), slot: sheet?.slot ?? autoSlot(),
    name, kcal: Math.round(kcal), proteinG, carbsG, fatG,
  });
}

function addStepsFor(date, n) {
  S.steps[date] = Math.max(0, (S.steps[date] ?? 0) + n);
}

function finishOnboarding() {
  const p = obProfile();
  if (!p) return;

  // The draft is imperial because that's how it was asked; the profile is metric because that's
  // what every formula expects. This is the one place the two meet.
  S.profile = {
    sex: p.sex,
    birthYear: String(now().getFullYear() - p.ageYears),
    heightCm: p.heightCm,
    weightKg: p.weightKg,
    goalWeightKg: p.goalWeightKg,
    activityLevel: p.activityLevel,
    goalPace: p.goalPace,
  };
  S.onboarded = true;
  S.weights = [{ date: todayISO(), weightKg: p.weightKg }];
  S.tab = 'today';
  sheet = null;
  save();
  render();
}

// ---- Simulated session ---------------------------------------------------

const SIM_PACE = { RUN: 5.6, WALK: 1.35, HIKE: 1.1, CYCLE: 7.0 }; // metres per second

function startSession(type) {
  live = { type, startedAt: Date.now(), elapsedSeconds: 0, distanceMetres: 0, elevationGainMetres: 0, paused: false };
  S.tab = 'move';
  sheet = null;
  clearInterval(liveTimer);
  liveTimer = setInterval(tickSession, 1000);
  save();
  render();
}

function tickSession() {
  if (!live || live.paused) return;
  live.elapsedSeconds += 1;
  // A little jitter so pace doesn't read as suspiciously perfect.
  const speed = SIM_PACE[live.type] * (0.94 + Math.random() * 0.12);
  live.distanceMetres += speed;
  if (live.type === 'HIKE') live.elevationGainMetres += 0.12;
  if (S.tab === 'move' && !sheet) render();
}

function finishSession() {
  clearInterval(liveTimer);
  liveTimer = null;
  if (!live) return;

  const burn = Z.activityBurn(live.type, live.elapsedSeconds, live.distanceMetres, live.elevationGainMetres, +S.profile.weightKg);
  if (live.elapsedSeconds >= 10) {
    S.sessions.push({
      id: uid(), date: todayISO(), type: live.type,
      durationSeconds: Math.round(live.elapsedSeconds),
      distanceMetres: Math.round(live.distanceMetres),
      elevationGainMetres: Math.round(live.elevationGainMetres),
      kcal: burn.kcal, netKcal: burn.netKcal,
    });
    addStepsFor(todayISO(), Math.round(live.distanceMetres / 0.75));
  }
  live = null;
  save();
  render();
}

// ---- Seeded history ------------------------------------------------------

/**
 * Fabricates a plausible month so the adaptive maintenance, trend line and streak can be seen
 * working immediately. The person simulated has a real maintenance a little above what the formula
 * predicts, which is exactly the case the adaptive model exists to catch.
 */
function seedHistory(today) {
  const kg = +S.profile.weightKg;
  const age = Z.ageOn(+S.profile.birthYear, today);
  const formula = Z.formulaTdee({ sex: S.profile.sex, heightCm: +S.profile.heightCm, weightKg: kg, ageYears: age, activityLevel: S.profile.activityLevel });

  // The simulated person's real non-exercise baseline runs 7% above what the equation predicts.
  // That gap is the entire reason adaptive calibration exists, so the seed has to contain one.
  const trueBaseline = formula * 1.07;
  const DEFICIT = 500;
  const lossPerDay = DEFICIT / Z.KCAL_PER_KG;

  S.food = []; S.weights = []; S.sessions = []; S.steps = {}; S.strength = []; S.nudgeLog = [];

  const DAYS = 30;
  for (let i = DAYS; i >= 1; i--) {
    const date = Z.addDays(today, -i);
    const dow = Z.isoDayOfWeek(date);
    let netBurn = 0;

    const addSession = (type, durationSeconds, distanceMetres, elevationGainMetres) => {
      const b = Z.activityBurn(type, durationSeconds, distanceMetres, elevationGainMetres, kg);
      S.sessions.push({
        id: uid(), date, type,
        durationSeconds: Math.round(durationSeconds), distanceMetres: Math.round(distanceMetres),
        elevationGainMetres: Math.round(elevationGainMetres), kcal: b.kcal, netKcal: b.netKcal,
      });
      netBurn += b.netKcal;
    };

    if (dow === 2 || dow === 5) {
      const km = 6 + Math.random() * 3;
      addSession('RUN', km * 5.9 * 60, km * 1000, 30);
    }
    if (dow === 6) addSession('HIKE', 150 * 60, 11000, 520);
    if (dow === 1 || dow === 4) addSession('STRENGTH', 45 * 60, 0, 0);

    // Intake follows from the physics: baseline + what was burned, less the intended deficit.
    // Anything else and the trend line would contradict the food log.
    const intake = Math.round(trueBaseline + netBurn - DEFICIT + (Math.random() * 260 - 130));
    S.food.push({ id: uid(), date, slot: 'Breakfast', name: 'Oats & berries', kcal: Math.round(intake * 0.22), proteinG: 18, carbsG: 55, fatG: 8 });
    S.food.push({ id: uid(), date, slot: 'Lunch', name: 'Chicken & rice bowl', kcal: Math.round(intake * 0.33), proteinG: 48, carbsG: 62, fatG: 14 });
    S.food.push({ id: uid(), date, slot: 'Dinner', name: 'Salmon & potatoes', kcal: Math.round(intake * 0.35), proteinG: 44, carbsG: 48, fatG: 22 });
    S.food.push({ id: uid(), date, slot: 'Snack', name: 'Greek yogurt', kcal: Math.round(intake * 0.10), proteinG: 16, carbsG: 9, fatG: 2 });

    // Scale noise dwarfs the real trend — the whole reason the app smooths it.
    const trueWeight = kg + i * lossPerDay;
    S.weights.push({ date, weightKg: +(trueWeight + (Math.random() * 1.5 - 0.75)).toFixed(1) });

    S.steps[date] = Math.round(S.stepGoal * (0.72 + Math.random() * 0.5));
  }

  S.weights.push({ date: today, weightKg: kg });
  S.steps[today] = S.steps[today] ?? Math.round(S.stepGoal * 0.55);
  sheet = null;
}

// ---------------------------------------------------------------------------

// The published page's <head> is owned by the host, so the viewport is set here instead. Without it
// a phone renders at an assumed 980px and the whole app arrives zoomed out to nothing.
if (!document.querySelector('meta[name="viewport"]')) {
  const meta = document.createElement('meta');
  meta.name = 'viewport';
  meta.content = 'width=device-width, initial-scale=1, viewport-fit=cover';
  document.head.appendChild(meta);
}

render();
// Keeps the clock and any time-sensitive copy current without fighting an open sheet or a live run.
setInterval(() => { if (!sheet && !live) render(); }, 30000);
