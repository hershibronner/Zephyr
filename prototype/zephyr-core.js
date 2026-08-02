/**
 * Zephyr domain logic — JavaScript port of `core/src/main/kotlin/dev/zephyr/core`.
 *
 * This exists so the web prototype computes the *same numbers* the Android app does, letting the
 * design be used and judged for real before it is committed to Compose.
 *
 * Two implementations of the same rules is normally a liability — they drift, and the prototype
 * quietly starts lying about what the app will do. That is defended against directly: the Kotlin
 * test suite writes `prototype/parity-fixtures.json`, and `parity.test.mjs` replays every fixture
 * through this file and fails on any disagreement. Change one side without the other and the check
 * goes red.
 *
 * Kotlin's `roundToInt` is `floor(x + 0.5)`, which is exactly JavaScript's `Math.round`, so the two
 * agree on halfway cases including negatives. Integer division in Kotlin truncates toward zero;
 * where that matters below it is written explicitly rather than left to `/`.
 */

export const KCAL_PER_KG = 7700;

export const Sex = { MALE: 'MALE', FEMALE: 'FEMALE', UNSPECIFIED: 'UNSPECIFIED' };

export const ActivityLevel = {
  SEDENTARY: { name: 'SEDENTARY', multiplier: 1.20, label: 'Desk job, little walking' },
  LIGHT: { name: 'LIGHT', multiplier: 1.30, label: 'On your feet some of the day' },
  MODERATE: { name: 'MODERATE', multiplier: 1.38, label: 'Mostly on your feet' },
  HIGH: { name: 'HIGH', multiplier: 1.45, label: 'Physical job, constant movement' },
};

/** Pounds in a kilogram. Zephyr computes in metric and speaks in imperial. */
export const LB_PER_KG = 2.2046226218;

/**
 * The rates are whole and half pounds because that's how the people using this think — nobody sets
 * out to lose 0.45 kg a week. Stored metric because every formula here is, but chosen so the
 * imperial labels come out exact instead of awkwardly converted.
 */
export const GoalPace = {
  GAIN_SLOW: { name: 'GAIN_SLOW', kgPerWeek: 0.5 / LB_PER_KG, label: 'Gain ½ lb a week', blurb: 'Build, slowly and cleanly' },
  MAINTAIN: { name: 'MAINTAIN', kgPerWeek: 0.0, label: 'Stay where I am', blurb: 'Hold this weight, get fitter' },
  LOSE_EASY: { name: 'LOSE_EASY', kgPerWeek: -0.5 / LB_PER_KG, label: 'Lose ½ lb a week', blurb: 'Barely notice it' },
  LOSE_STEADY: { name: 'LOSE_STEADY', kgPerWeek: -1.0 / LB_PER_KG, label: 'Lose 1 lb a week', blurb: 'The sweet spot' },
  LOSE_FAST: { name: 'LOSE_FAST', kgPerWeek: -1.5 / LB_PER_KG, label: 'Lose 1½ lb a week', blurb: 'You will feel this one' },
  LOSE_AGGRESSIVE: { name: 'LOSE_AGGRESSIVE', kgPerWeek: -2.0 / LB_PER_KG, label: 'Lose 2 lb a week', blurb: 'Only if you have weight to spare' },
};

// ---------------------------------------------------------------------------
// Energy
// ---------------------------------------------------------------------------

/** Mifflin-St Jeor. UNSPECIFIED takes the midpoint of the two sex constants. */
export function bmr(weightKg, heightCm, ageYears, sex) {
  const base = 10 * weightKg + 6.25 * heightCm - 5 * ageYears;
  const offset = sex === Sex.MALE ? 5 : sex === Sex.FEMALE ? -161 : -78;
  return Math.max(base + offset, 0);
}

/** Maintenance before logged exercise, which the ledger adds on top. */
export function formulaTdee(profile) {
  return bmr(profile.weightKg, profile.heightCm, profile.ageYears, profile.sex) *
    ActivityLevel[profile.activityLevel].multiplier;
}

export const TargetAdjustment = {
  NONE: 'NONE',
  CAPPED_TO_PERCENTAGE: 'CAPPED_TO_PERCENTAGE',
  RAISED_TO_FLOOR: 'RAISED_TO_FLOOR',
  RAISED_ABOVE_BMR: 'RAISED_ABOVE_BMR',
};

export const MAX_DEFICIT_FRACTION = 0.25;
export const BMR_SAFETY_MARGIN = 1.0;
export const FLOOR_MALE = 1500;
export const FLOOR_FEMALE = 1200;

/**
 * Goal pace to daily calorie target, with the guard rails.
 *
 * The rails are what stop an ambitious slider becoming a crash diet: no more than a quarter off
 * maintenance, never under resting metabolic rate, never under an absolute floor.
 */
export function calorieTarget(maintenanceKcal, bmrKcal, kgPerWeek, sex) {
  const requested = kgPerWeek * KCAL_PER_KG / 7;
  let adjustment = TargetAdjustment.NONE;
  let delta = requested;

  if (delta < 0) {
    const maxDeficit = maintenanceKcal * MAX_DEFICIT_FRACTION;
    if (Math.abs(delta) > maxDeficit) {
      delta = -maxDeficit;
      adjustment = TargetAdjustment.CAPPED_TO_PERCENTAGE;
    }
  }

  let target = maintenanceKcal + delta;

  const bmrFloor = bmrKcal * BMR_SAFETY_MARGIN;
  const hardFloor = sex === Sex.FEMALE ? FLOOR_FEMALE : FLOOR_MALE;
  const safetyFloor = Math.max(bmrFloor, hardFloor);

  if (target < safetyFloor) {
    target = safetyFloor;
    adjustment = hardFloor >= bmrFloor
      ? TargetAdjustment.RAISED_TO_FLOOR
      : TargetAdjustment.RAISED_ABOVE_BMR;
  }

  // No safe deficit exists for this person; maintenance is the honest answer.
  if (kgPerWeek < 0 && target > maintenanceKcal) target = maintenanceKcal;

  const effectiveDelta = target - maintenanceKcal;
  return {
    maintenanceKcal: Math.round(maintenanceKcal),
    targetKcal: Math.round(target),
    dailyDeltaKcal: Math.round(effectiveDelta),
    adjustment,
    effectiveKgPerWeek: effectiveDelta * 7 / KCAL_PER_KG,
  };
}

export const PROTEIN_G_PER_KG_DEFICIT = 2.0;
export const PROTEIN_G_PER_KG_MAINTENANCE = 1.8;
export const FAT_G_PER_KG = 0.8;

/**
 * Protein is anchored to *goal* weight — in a deficit it decides whether the weight lost is fat or
 * muscle, which is what actually changes how someone looks.
 */
export function macros(targetKcal, goalWeightKg, inDeficit) {
  const proteinPerKg = inDeficit ? PROTEIN_G_PER_KG_DEFICIT : PROTEIN_G_PER_KG_MAINTENANCE;
  const proteinG = Math.round(goalWeightKg * proteinPerKg);
  const fatG = Math.round(goalWeightKg * FAT_G_PER_KG);
  const remaining = Math.max(targetKcal - proteinG * 4 - fatG * 9, 0);
  return { proteinG, fatG, carbsG: Math.trunc(remaining / 4), kcal: targetKcal };
}

export const TdeeConfidence = { NONE: 'NONE', LOW: 'LOW', MEDIUM: 'MEDIUM', HIGH: 'HIGH' };
export const ADAPTIVE_MIN_DAYS = 14;
export const ADAPTIVE_FULL_DAYS = 28;
const MAX_MEASURED_WEIGHT = 0.8;

/**
 * Learns real maintenance from observed reality.
 *
 * Energy conservation gives total expenditure as `meanIntake - imbalance`, but the ledger adds each
 * day's logged burn on top of the target, so exercise is subtracted back out to leave the
 * comparable non-exercise baseline:
 *
 *   measuredBaseline = meanIntake - meanBurn - (trendWeightChange * 7700 / days)
 *
 * Adding that term instead of subtracting inflates the estimate by twice the exercise burn, and
 * only for people who train — handing back the entire deficit of the users doing the most work.
 * Blended toward the measurement as evidence accumulates, capped so one bad week can't swing it.
 */
export function adaptiveTdee(formula, records, weightChangeKg) {
  const days = records.length;
  if (days < ADAPTIVE_MIN_DAYS || weightChangeKg == null) {
    return {
      tdeeKcal: Math.round(formula),
      formulaTdeeKcal: Math.round(formula),
      measuredTdeeKcal: null,
      confidence: days === 0 ? TdeeConfidence.NONE : TdeeConfidence.LOW,
      daysOfData: days,
    };
  }

  const meanIntake = records.reduce((s, r) => s + r.intakeKcal, 0) / days;
  const meanExercise = records.reduce((s, r) => s + r.exerciseKcal, 0) / days;
  const dailyImbalance = weightChangeKg * KCAL_PER_KG / days;
  const measured = meanIntake - meanExercise - dailyImbalance;

  // Implausible almost always means under-logged food, not a broken metabolism.
  if (measured < 1000 || measured > 8000) {
    return {
      tdeeKcal: Math.round(formula),
      formulaTdeeKcal: Math.round(formula),
      measuredTdeeKcal: null,
      confidence: TdeeConfidence.LOW,
      daysOfData: days,
    };
  }

  const progress = (days - ADAPTIVE_MIN_DAYS) / (ADAPTIVE_FULL_DAYS - ADAPTIVE_MIN_DAYS);
  const weight = Math.min(clamp(progress, 0, 1) * MAX_MEASURED_WEIGHT + 0.2, MAX_MEASURED_WEIGHT);
  const blended = formula * (1 - weight) + measured * weight;

  return {
    tdeeKcal: Math.round(blended),
    formulaTdeeKcal: Math.round(formula),
    measuredTdeeKcal: Math.round(measured),
    confidence: days >= ADAPTIVE_FULL_DAYS ? TdeeConfidence.HIGH
      : days >= 21 ? TdeeConfidence.MEDIUM : TdeeConfidence.LOW,
    daysOfData: days,
  };
}

// ---------------------------------------------------------------------------
// Weight trend
// ---------------------------------------------------------------------------

export const TREND_SMOOTHING = 0.10;

/**
 * Exponentially smoothed body weight. Daily scale readings swing kilograms on water and gut
 * contents — far more than the ~70g/day of real fat loss — so the smoothed series is the only one
 * worth showing a target against.
 *
 * @param entries [{date: 'YYYY-MM-DD', weightKg}]
 */
export function weightTrend(entries, smoothing = TREND_SMOOTHING) {
  if (!entries.length) return { points: [], currentTrendKg: null, weeklyRateKg: null, totalChangeKg: null };

  const byDate = new Map();
  [...entries].sort((a, b) => a.date.localeCompare(b.date)).forEach(e => byDate.set(e.date, e.weightKg));

  const dates = [...byDate.keys()];
  const start = dates[0];
  const end = dates[dates.length - 1];

  const points = [];
  let trend = byDate.get(start);
  let day = start;
  while (day <= end) {
    const raw = byDate.has(day) ? byDate.get(day) : null;
    if (raw != null) trend += (raw - trend) * smoothing;
    points.push({ date: day, rawKg: raw, trendKg: trend });
    day = addDays(day, 1);
  }

  return {
    points,
    currentTrendKg: points[points.length - 1].trendKg,
    weeklyRateKg: weeklyRate(points),
    totalChangeKg: points[points.length - 1].trendKg - points[0].trendKg,
  };
}

/** Needs a week of span before it will claim a rate — below that the smoothing hasn't caught up. */
export function weeklyRate(points, windowDays = 14) {
  if (points.length < 2) return null;
  const last = points[points.length - 1];
  const cutoff = addDays(last.date, -windowDays);
  const window = points.filter(p => p.date >= cutoff);
  if (window.length < 2) return null;

  const first = window[0];
  const days = daysBetween(first.date, last.date);
  if (days < 7) return null;
  return (last.trendKg - first.trendKg) / days * 7;
}

/**
 * Weight change across a window, from a least-squares fit of the raw weigh-ins.
 *
 * The exponential trend is the right thing to *show* — it kills the water-weight noise that makes
 * people abandon working plans. It is the wrong thing to measure a *rate* with: it is seeded at the
 * first weigh-in and takes weeks to catch up, so differencing its endpoints during that warm-up
 * understates real loss by about a third. Fed to `adaptiveTdee` that reads as a maintenance 150-250
 * kcal too low, prescribing a deeper deficit than the user asked for — during exactly the first six
 * weeks they are deciding whether to trust the app. A straight-line fit has no such lag.
 */
export function fittedChangeKg(entries) {
  if (entries.length < 2) return null;

  const origin = Math.min(...entries.map(e => Date.parse(e.date + 'T00:00:00Z')));
  const xs = entries.map(e => (Date.parse(e.date + 'T00:00:00Z') - origin) / 86400000);
  const ys = entries.map(e => e.weightKg);
  const meanX = xs.reduce((a, b) => a + b, 0) / xs.length;
  const meanY = ys.reduce((a, b) => a + b, 0) / ys.length;

  let numerator = 0, denominator = 0;
  for (let i = 0; i < xs.length; i++) {
    numerator += (xs[i] - meanX) * (ys[i] - meanY);
    denominator += (xs[i] - meanX) ** 2;
  }
  if (denominator === 0) return null;

  return numerator / denominator * (Math.max(...xs) - Math.min(...xs));
}

/** Null rather than a fantasy date when flat or moving the wrong way. */
export function projectGoalDate(result, goalKg, from) {
  const current = result.currentTrendKg;
  const rate = result.weeklyRateKg;
  if (current == null || rate == null) return null;
  const remaining = goalKg - current;
  if (Math.abs(remaining) < 0.1) return from;
  if (Math.abs(rate) < 0.02) return null;
  if ((remaining > 0) !== (rate > 0)) return null;
  const weeks = remaining / rate;
  if (weeks <= 0 || weeks > 260) return null;
  return addDays(from, Math.round(weeks * 7));
}

// ---------------------------------------------------------------------------
// Activity
// ---------------------------------------------------------------------------

export const ActivityType = {
  RUN: 'RUN', HIKE: 'HIKE', WALK: 'WALK', CYCLE: 'CYCLE', STRENGTH: 'STRENGTH', OTHER: 'OTHER',
};

export const ACTIVITY_LABEL = {
  RUN: 'Run', HIKE: 'Hike', WALK: 'Walk', CYCLE: 'Cycle', STRENGTH: 'Strength', OTHER: 'Other',
};

const RUN_METS = [[6.4, 6.0], [8.0, 8.3], [9.7, 9.8], [11.3, 11.0], [12.9, 11.8], [14.5, 12.8], [16.1, 14.5], [17.7, 16.0], [19.3, 19.0]];
const WALK_METS = [[3.2, 2.0], [4.0, 2.8], [4.8, 3.5], [5.6, 4.3], [6.4, 5.0], [7.2, 7.0]];
const HIKE_METS = [[3.0, 4.5], [4.0, 5.3], [5.0, 6.0], [6.0, 6.8]];
const CYCLE_METS = [[16.0, 4.0], [19.0, 6.8], [22.5, 8.0], [25.5, 10.0], [30.0, 12.0]];

const KCAL_PER_KG_PER_METRE_CLIMB = 0.0091;

export function metFor(type, speedKmh) {
  switch (type) {
    case ActivityType.RUN: return interpolate(RUN_METS, speedKmh);
    case ActivityType.WALK: return interpolate(WALK_METS, speedKmh);
    case ActivityType.HIKE: return interpolate(HIKE_METS, speedKmh);
    case ActivityType.CYCLE: return interpolate(CYCLE_METS, speedKmh);
    case ActivityType.STRENGTH: return 5.0;
    default: return 4.0;
  }
}

/**
 * `kcal/min = MET * 3.5 * kg / 200`, plus vertical work, minus the resting metabolism already
 * counted in TDEE. Handing back gross calories would quietly overfeed the deficit.
 */
export function activityBurn(type, durationSeconds, distanceMetres, elevationGainMetres, weightKg) {
  const minutes = durationSeconds / 60;
  if (minutes <= 0) return { kcal: 0, met: 0, restingKcal: 0, netKcal: 0 };

  const speedKmh = distanceMetres > 0 ? distanceMetres / 1000 / (minutes / 60) : 0;
  const met = metFor(type, speedKmh);

  const baseKcal = met * 3.5 * weightKg / 200 * minutes;
  const climbKcal = Math.max(elevationGainMetres, 0) * weightKg * KCAL_PER_KG_PER_METRE_CLIMB;
  const restingKcal = 1.0 * 3.5 * weightKg / 200 * minutes;

  const kcal = Math.round(baseKcal + climbKcal);
  const resting = Math.round(restingKcal);
  return { kcal, met, restingKcal: resting, netKcal: Math.max(kcal - resting, 0) };
}

export function paceSecondsPerKm(distanceMetres, durationSeconds) {
  if (distanceMetres <= 0 || durationSeconds <= 0) return null;
  return durationSeconds / (distanceMetres / 1000);
}

export function formatPace(secondsPerKm) {
  if (secondsPerKm == null || !isFinite(secondsPerKm)) return '--:--';
  const total = Math.round(secondsPerKm);
  return `${Math.trunc(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

export const STEP_GOAL_DEFAULT = 8000;
export const STEP_GOAL_MIN = 3000;
export const STEP_GOAL_MAX = 20000;
const STEP_PROGRESSION = 0.08;
const STEP_MIN_DAYS_FOR_BASELINE = 5;

/**
 * A goal from the user's own median, not the 10,000 figure that came from a 1960s pedometer advert.
 * A goal that gets missed every day stops motivating and becomes noise.
 */
export function suggestStepGoal(history, currentGoal = null) {
  const recent = [...history].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 14);
  if (recent.length < STEP_MIN_DAYS_FOR_BASELINE) {
    return {
      goal: currentGoal ?? STEP_GOAL_DEFAULT,
      baselineMedian: 0,
      reason: 'Not enough history yet — starting from a standard goal.',
    };
  }

  const med = median(recent.map(d => d.steps));
  const raised = Math.round(med * (1 + STEP_PROGRESSION));
  const goal = clamp(Math.trunc((raised + 125) / 250) * 250, STEP_GOAL_MIN, STEP_GOAL_MAX);

  const reason = currentGoal != null && goal > currentGoal
    ? `You've been averaging ${med} steps — nudging the goal up.`
    : currentGoal != null && goal < currentGoal
      ? "Your goal was out of reach lately; easing it to something you'll hit."
      : `Based on your recent median of ${med} steps.`;
  return { goal, baselineMedian: med, reason };
}

/** Cumulative share of a day's steps normally done by the end of each hour. */
const CUMULATIVE_BY_HOUR = [
  0.00, 0.00, 0.00, 0.00, 0.00, 0.01,
  0.03, 0.08, 0.16, 0.23, 0.29, 0.35,
  0.43, 0.51, 0.57, 0.63, 0.69, 0.76,
  0.84, 0.90, 0.95, 0.98, 1.00, 1.00,
];

/**
 * Progress judged against the shape of a real day, not a straight line — otherwise the app declares
 * you hopelessly behind at 9am daily, and an alert that cries wolf gets muted.
 */
export function expectedStepFraction(hour, minute) {
  const startOfHour = hour === 0 ? 0 : CUMULATIVE_BY_HOUR[hour - 1];
  const endOfHour = CUMULATIVE_BY_HOUR[hour];
  return startOfHour + (endOfHour - startOfHour) * (minute / 60);
}

export function stepStatus(steps, goal, hour, minute) {
  const fraction = expectedStepFraction(hour, minute);
  const expected = Math.round(goal * fraction);
  const projected = fraction > 0.05 ? Math.min(Math.round(steps / fraction), 100000) : goal;
  return {
    steps, goal,
    expectedByNow: expected,
    deficit: Math.max(expected - steps, 0),
    onTrack: steps >= expected,
    projectedEndOfDay: projected,
    fractionOfGoal: goal <= 0 ? 0 : clamp(steps / goal, 0, 1),
    remaining: Math.max(goal - steps, 0),
  };
}

export function minutesToWalk(steps, stepsPerMinute = 110) {
  return steps <= 0 ? 0 : Math.max(1, Math.round(steps / stepsPerMinute));
}

// ---------------------------------------------------------------------------
// Ledger
// ---------------------------------------------------------------------------

export const BalanceState = {
  ON_TRACK: 'ON_TRACK', NEARLY_THERE: 'NEARLY_THERE', OVER: 'OVER', UNDER_FUELLED: 'UNDER_FUELLED',
};

export function energyBalance({ date, targetKcal, consumedKcal, exerciseKcal, proteinTargetG, macros: totals, maintenanceKcal }) {
  const adjustedTargetKcal = targetKcal + exerciseKcal;
  const remainingKcal = adjustedTargetKcal - consumedKcal;
  const fractionConsumed = adjustedTargetKcal <= 0 ? 0 : consumedKcal / adjustedTargetKcal;

  let state;
  if (consumedKcal === 0) state = BalanceState.ON_TRACK;
  else if (remainingKcal < -50) state = BalanceState.OVER;
  else if (remainingKcal <= 100) state = BalanceState.NEARLY_THERE;
  else if (fractionConsumed < 0.6) state = BalanceState.UNDER_FUELLED;
  else state = BalanceState.ON_TRACK;

  return {
    date, targetKcal, consumedKcal, exerciseKcal, proteinTargetG,
    macros: totals, maintenanceKcal: maintenanceKcal ?? targetKcal,
    adjustedTargetKcal, remainingKcal, fractionConsumed, state,
    proteinFraction: proteinTargetG <= 0 ? 0 : clamp(totals.proteinG / proteinTargetG, 0, 1),
    proteinRemainingG: Math.max(proteinTargetG - totals.proteinG, 0),
  };
}

/**
 * The window a weekly summary covers: the last `count` *complete* days, ending yesterday.
 *
 * Today is deliberately excluded. A day in progress has only part of its food logged, so including
 * it drags average intake down and reports a far deeper deficit than the user is actually running —
 * at breakfast it would claim they're losing three times their target rate.
 */
export function lastCompleteDays(todayISO, count = 7) {
  return Array.from({ length: count }, (_, i) => addDays(todayISO, -(count - i)));
}

export function weeklySummary(balances) {
  const logged = balances.filter(b => b.consumedKcal > 0);
  if (!logged.length) {
    return { daysLogged: 0, averageIntakeKcal: 0, averageBurnKcal: 0, averageTargetKcal: 0, averageDeltaKcal: 0, adherencePercent: 0, projectedWeeklyKg: 0, isEmpty: true };
  }
  const avg = f => logged.reduce((s, b) => s + f(b), 0) / logged.length;
  const adherent = logged.filter(isAdherent).length;
  const avgImbalance = avg(b => b.consumedKcal - b.maintenanceKcal - b.exerciseKcal);

  return {
    daysLogged: logged.length,
    averageIntakeKcal: Math.round(avg(b => b.consumedKcal)),
    averageBurnKcal: Math.round(avg(b => b.exerciseKcal)),
    averageTargetKcal: Math.round(avg(b => b.targetKcal)),
    averageDeltaKcal: Math.round(avg(b => b.consumedKcal - b.adjustedTargetKcal)),
    adherencePercent: Math.round(adherent * 100 / logged.length),
    projectedWeeklyKg: avgImbalance * 7 / KCAL_PER_KG,
    isEmpty: false,
  };
}

export function isAdherent(balance) {
  if (balance.consumedKcal <= 0) return false;
  const tolerance = balance.adjustedTargetKcal * 0.10;
  return Math.abs(balance.consumedKcal - balance.adjustedTargetKcal) <= tolerance ||
    balance.consumedKcal < balance.adjustedTargetKcal;
}

// ---------------------------------------------------------------------------
// Streak
// ---------------------------------------------------------------------------

/**
 * Adherence, not perfection. A streak that snaps the first time you eat cake teaches all-or-nothing
 * thinking, which is exactly what ends diets. Showing up counts.
 */
export function qualifies(day) {
  return day.loggedFood && (day.withinCalorieTarget || day.hitStepGoal || day.completedSession);
}

export function calculateStreak(outcomes, today) {
  if (!outcomes.length) return { current: 0, longest: 0, atRisk: false, lastQualifyingDate: null };

  const qualifying = [...new Set(outcomes.filter(qualifies).map(o => o.date))].sort();
  if (!qualifying.length) return { current: 0, longest: 0, atRisk: false, lastQualifyingDate: null };

  const last = qualifying[qualifying.length - 1];
  const daysSince = daysBetween(last, today);

  // Today still in progress must not break the chain — it should raise atRisk while it can be saved.
  const current = (daysSince === 0 || daysSince === 1) ? runEndingAt(qualifying, last) : 0;

  let longest = 1, run = 1;
  for (let i = 1; i < qualifying.length; i++) {
    run = daysBetween(qualifying[i - 1], qualifying[i]) === 1 ? run + 1 : 1;
    if (run > longest) longest = run;
  }

  return { current, longest, atRisk: current > 0 && daysSince === 1, lastQualifyingDate: last };
}

function runEndingAt(sortedDates, end) {
  let count = 0;
  let expected = end;
  for (let i = sortedDates.length - 1; i >= 0; i--) {
    const d = sortedDates[i];
    if (d === expected) { count++; expected = addDays(expected, -1); }
    else if (d < expected) break;
  }
  return count;
}

export const STREAK_MILESTONES = [3, 7, 14, 30, 60, 100, 200, 365];
export const milestoneReached = streak => STREAK_MILESTONES.find(m => m === streak) ?? null;

// ---------------------------------------------------------------------------
// Training plan
// ---------------------------------------------------------------------------

export const WEEKLY_INCREASE = 0.10;
export const DELOAD_EVERY = 4;
export const DELOAD_FACTOR = 0.70;
export const STARTING_RUN_METRES = 2000;
export const MAX_RUN_METRES = 30000;
export const STARTING_HIKE_MINUTES = 60;

export function defaultTemplate() {
  return [
    { id: 1, dayOfWeek: 1, type: ActivityType.STRENGTH, timeOfDay: '18:00', enabled: true },
    { id: 2, dayOfWeek: 2, type: ActivityType.RUN, timeOfDay: '07:00', enabled: true },
    { id: 3, dayOfWeek: 4, type: ActivityType.STRENGTH, timeOfDay: '18:00', enabled: true },
    { id: 4, dayOfWeek: 5, type: ActivityType.RUN, timeOfDay: '07:00', enabled: true },
    { id: 5, dayOfWeek: 6, type: ActivityType.HIKE, timeOfDay: '09:00', enabled: true },
  ];
}

/**
 * The user owns the shape of the week; the engine owns the load inside it. Volume rises by at most
 * 10% and every fourth week deloads — the classic beginner failure is a fortnight of enthusiastic
 * ramping followed by a month off injured.
 */
export function prescriptionsForWeek(template, weekStartISO, weekIndex, baseline) {
  const isDeload = weekIndex > 0 && (weekIndex + 1) % DELOAD_EVERY === 0;
  const enabled = template.filter(s => s.enabled).sort((a, b) => a.dayOfWeek - b.dayOfWeek);
  const runSlots = enabled.filter(s => s.type === ActivityType.RUN);
  const weekStartDow = isoDayOfWeek(weekStartISO);

  return enabled.map(slot => {
    let offset = slot.dayOfWeek - weekStartDow;
    if (offset < 0) offset += 7;
    const date = addDays(weekStartISO, offset);

    if (slot.type === ActivityType.RUN) {
      const start = Math.max(baseline.longestRunMetres ?? STARTING_RUN_METRES, STARTING_RUN_METRES);
      const effectiveWeeks = weekIndex - Math.trunc(weekIndex / DELOAD_EVERY);
      let target = start * Math.pow(1 + WEEKLY_INCREASE, effectiveWeeks);
      const isLong = runSlots.length > 0 && runSlots[runSlots.length - 1].dayOfWeek === slot.dayOfWeek;
      if (!isLong) target *= 0.65;
      if (isDeload) target *= DELOAD_FACTOR;
      target = Math.min(target, MAX_RUN_METRES);
      const rounded = Math.round(target / 250) * 250;

      return {
        slot, date, targetDistanceMetres: rounded, isDeload,
        headline: isDeload ? `Easy run — ${formatKm(rounded)}`
          : isLong ? `Long run — ${formatKm(rounded)}` : `Run — ${formatKm(rounded)}`,
        detail: isDeload ? 'Deload week. Keep it comfortable — this is what lets next week go up.'
          : isLong ? 'Steady effort. You should be able to speak in short sentences.'
            : "Conversational pace. If you're gasping, slow down.",
      };
    }

    if (slot.type === ActivityType.HIKE) {
      const start = Math.max(baseline.longestHikeMinutes ?? STARTING_HIKE_MINUTES, STARTING_HIKE_MINUTES);
      const effectiveWeeks = weekIndex - Math.trunc(weekIndex / DELOAD_EVERY);
      let minutes = start * Math.pow(1 + WEEKLY_INCREASE, effectiveWeeks);
      if (isDeload) minutes *= DELOAD_FACTOR;
      const capped = Math.round(Math.min(minutes, 300));
      return {
        slot, date, targetDurationMinutes: capped, targetElevationMetres: capped * 3, isDeload,
        headline: `Hike — ${capped} min`,
        detail: 'Find some climbing. Elevation is where hiking earns its calories.',
      };
    }

    if (slot.type === ActivityType.STRENGTH) {
      return {
        slot, date, isDeload,
        headline: isDeload ? 'Strength — light week' : 'Strength session',
        detail: isDeload ? 'Same movements, drop a set. Recovery is where the adaptation happens.'
          : 'Add weight where you hit the top of your rep range last time.',
      };
    }

    if (slot.type === ActivityType.WALK) {
      return { slot, date, targetDurationMinutes: 30, isDeload: false, headline: 'Easy walk', detail: '30 minutes at a conversational pace.' };
    }

    return { slot, date, isDeload: false, headline: ACTIVITY_LABEL[slot.type], detail: `Logged as ${ACTIVITY_LABEL[slot.type].toLowerCase()}.` };
  });
}

/** Planned versus done. A session counts on the day or the day after — real life slides. */
export function adherence(prescriptions, completed, upToISO) {
  const due = prescriptions.filter(p => p.date <= upToISO);
  if (!due.length) return { planned: 0, completed: 0, missed: [], percent: 100 };

  const remaining = [...completed];
  const missed = [];
  for (const p of due) {
    const i = remaining.findIndex(c => c.type === p.slot.type && [0, 1].includes(daysBetween(p.date, c.date)));
    if (i >= 0) remaining.splice(i, 1); else missed.push(p);
  }
  const done = due.length - missed.length;
  return { planned: due.length, completed: done, missed, percent: Math.round(done * 100 / due.length) };
}

/** Double progression: earn the rep range, then earn the weight. */
export function nextStrengthPrescription(lastSets, repRange, consecutiveFailures = 0, isLowerBody = false) {
  if (!lastSets.length) return null;
  const workingWeight = Math.max(...lastSets.map(s => s.weightKg));
  const topSets = lastSets.filter(s => s.weightKg === workingWeight);
  const minReps = Math.min(...topSets.map(s => s.reps));
  const increment = isLowerBody ? 5.0 : 2.5;

  if (consecutiveFailures >= 2) {
    return {
      weightKg: Math.max(Math.round(workingWeight * 0.9 / 1.25) * 1.25, 1.25),
      targetReps: repRange[0], isDeload: true,
      note: 'Stalled twice. Dropping 10% to rebuild momentum — this is normal.',
    };
  }
  if (minReps >= repRange[1]) {
    return { weightKg: workingWeight + increment, targetReps: repRange[0], isDeload: false, note: `You owned every set. Add ${increment}kg.` };
  }
  return { weightKg: workingWeight, targetReps: Math.min(minReps + 1, repRange[1]), isDeload: false, note: 'Same weight — add a rep to each set.' };
}

// ---------------------------------------------------------------------------
// The coach
// ---------------------------------------------------------------------------

export const NudgeCategory = {
  PLAN: { id: 'PLAN', channelId: 'plan', channelName: 'Daily plan', description: 'Your morning briefing and weekly review' },
  SESSION: { id: 'SESSION', channelId: 'session', channelName: 'Workout reminders', description: "Reminders for sessions you've scheduled" },
  MOVEMENT: { id: 'MOVEMENT', channelId: 'movement', channelName: 'Move reminders', description: "Nudges when you've been still too long or are behind on steps" },
  FOOD: { id: 'FOOD', channelId: 'food', channelName: 'Meal logging', description: "Reminders to log what you've eaten" },
  STREAK: { id: 'STREAK', channelId: 'streak', channelName: 'Streak protection', description: 'Warnings when a streak is about to break' },
  CELEBRATION: { id: 'CELEBRATION', channelId: 'celebration', channelName: 'Wins', description: 'Goals hit, records broken, milestones reached' },
};

export const CoachTone = {
  GENTLE: { id: 'GENTLE', label: 'Gentle', maxPerDay: 2, description: 'A morning plan and a weekly review. Encouragement only.' },
  BALANCED: { id: 'BALANCED', label: 'Balanced', maxPerDay: 5, description: "Plan, reminders, and a push when you're falling behind." },
  RELENTLESS: { id: 'RELENTLESS', label: 'Relentless', maxPerDay: 12, description: 'Everything, including hourly move reminders. No hiding.' },
};

export const NudgePriority = { LOW: 0, DEFAULT: 1, HIGH: 2 };

const INACTIVITY_STEP_THRESHOLD = 250;
const BEHIND_PACE_FRACTION = 0.15;
const SESSION_REMINDER_MINUTES = 30;

function inQuietHours(quiet, minutes) {
  const start = toMinutes(quiet.start), end = toMinutes(quiet.end);
  return start <= end ? (minutes >= start && minutes < end) : (minutes >= start || minutes < end);
}

/**
 * Decides what the app says and when.
 *
 * One belief runs through it: a notification is only worth sending if it arrives while the outcome
 * can still change, and if it names the next physical action. "1,400 steps — about 13 minutes" at
 * 6pm is actionable; the same fact at 11pm is just criticism.
 */
export function evaluateNudges(state, settings) {
  const { hour, minute } = state;
  const minutes = hour * 60 + minute;
  const day = state.date;

  // Mid-session, the user is already doing the thing. Anything said now is a distraction.
  if (state.isTrackingSession) return [];

  const out = [];

  // Celebrations first — they're rewards, not demands, and aren't charged to the budget.
  if (state.stepStatus.goal > 0 && state.stepStatus.steps >= state.stepStatus.goal) {
    out.push({
      key: `goal-steps-${day}`, category: NudgeCategory.CELEBRATION,
      title: 'Step goal smashed 🎯',
      body: `${fmt(state.stepStatus.steps)} steps. That's the goal, done.`,
      priority: NudgePriority.LOW, actions: ['Open'], isCelebration: true,
    });
  }

  if (!inQuietHours(settings.quietHours, minutes)) {
    const chases = settings.tone !== 'GENTLE';
    const inactivityAllowed = settings.tone === 'RELENTLESS';

    // Morning plan
    if (minutes >= 420 && minutes <= 600) {
      const session = state.todaysPrescriptions[0];
      let body = `${fmt(state.stepStatus.goal)} steps · ${fmt(state.balance.targetKcal)} kcal · ${state.balance.proteinTargetG}g protein`;
      if (session) body += `\n${session.headline} at ${formatClock(session.slot.timeOfDay)}`;
      out.push({
        key: `morning-${day}`, category: NudgeCategory.PLAN,
        title: session ? `Today: ${session.headline}` : "Here's today",
        body, priority: NudgePriority.DEFAULT, actions: ['Open'], isCelebration: false,
      });
    }

    // Session reminders
    for (const p of state.todaysPrescriptions) {
      const until = toMinutes(p.slot.timeOfDay) - minutes;
      if (until >= 0 && until <= SESSION_REMINDER_MINUTES && !state.completedSessionToday) {
        out.push({
          key: `session-${p.slot.id}-${day}`, category: NudgeCategory.SESSION,
          title: `${p.headline} in ${until} min`, body: p.detail,
          priority: NudgePriority.HIGH, actions: ['Start', 'Snooze 1h'], isCelebration: false,
        });
      }
    }

    // Meal reminders at natural checkpoints only
    const checkpoint = (minutes >= 810 && minutes <= 870) ? 'lunch'
      : (minutes >= 1170 && minutes <= 1230) ? 'dinner' : null;
    if (checkpoint) {
      const hoursSince = state.hoursSinceFoodLog;
      if (!(state.balance.consumedKcal > 0 && (hoursSince == null || hoursSince < 4))) {
        out.push({
          key: `meal-${checkpoint}-${day}`, category: NudgeCategory.FOOD,
          title: `Log your ${checkpoint}`,
          body: "Takes ten seconds and it's the difference between guessing and knowing.",
          priority: NudgePriority.LOW, actions: ['Log it'], isCelebration: false,
        });
      }
    }

    // Inactivity
    if (inactivityAllowed && hour >= 9 && hour <= 20 &&
        state.stepsInLastHour < INACTIVITY_STEP_THRESHOLD &&
        state.stepStatus.steps < state.stepStatus.goal) {
      out.push({
        key: `inactive-${day}-${hour}`, category: NudgeCategory.MOVEMENT,
        title: "You've been still for an hour",
        body: "Five minutes on your feet. That's all this is asking.",
        priority: NudgePriority.LOW, actions: ['Start walk', 'Snooze 1h'], isCelebration: false,
      });
    }

    // Behind on steps
    if (chases && minutes >= 720 && minutes <= 960 && state.stepStatus.goal > 0 &&
        state.stepStatus.deficit >= state.stepStatus.goal * BEHIND_PACE_FRACTION) {
      out.push({
        key: `behind-steps-${day}`, category: NudgeCategory.MOVEMENT,
        title: `You're ${fmt(state.stepStatus.deficit)} steps behind pace`,
        body: `Still early. A ${minutesToWalk(state.stepStatus.deficit)} minute walk puts you level.`,
        priority: NudgePriority.DEFAULT, actions: ['Start walk'], isCelebration: false,
      });
    }

    // Close the ring
    if (chases && minutes >= 1050 && minutes <= 1230) {
      const remaining = state.stepStatus.remaining;
      // Past a certain gap this stops being an invitation and becomes a reproach.
      if (remaining > 0 && remaining <= state.stepStatus.goal * 0.45) {
        out.push({
          key: `close-ring-${day}`, category: NudgeCategory.MOVEMENT,
          title: `${fmt(remaining)} steps to go`,
          body: `That's about ${minutesToWalk(remaining)} minutes. Close it out.`,
          priority: NudgePriority.HIGH, actions: ['Start walk'], isCelebration: false,
        });
      }
    }

    // Streak at risk
    if (chases && state.streakDays >= 3 && !state.streakSecuredToday && minutes >= 1050 && minutes <= 1230) {
      out.push({
        key: `streak-risk-${day}`, category: NudgeCategory.STREAK,
        title: `${state.streakDays} days on the line`,
        body: 'Log today\'s food or get your steps in and the streak holds.',
        priority: NudgePriority.HIGH, actions: ['Log it', 'Start walk'], isCelebration: false,
      });
    }

    // Missed session
    if (chases && state.missedYesterday.length && minutes >= 420 && minutes <= 600) {
      const missed = state.missedYesterday[0];
      out.push({
        key: `missed-${day}`, category: NudgeCategory.SESSION,
        title: `Yesterday's ${ACTIVITY_LABEL[missed.slot.type].toLowerCase()} didn't happen`,
        body: "One session doesn't undo anything. Want to move it to today?",
        priority: NudgePriority.DEFAULT, actions: ['View plan'], isCelebration: false,
      });
    }

    // Weigh-in
    if (state.daysSinceWeighIn != null && state.daysSinceWeighIn >= 3 && minutes >= 420 && minutes <= 600) {
      out.push({
        key: `weigh-in-${day}`, category: NudgeCategory.PLAN,
        title: 'Time to step on the scale',
        body: `It's been ${state.daysSinceWeighIn} days. The trend line needs data to stay honest.`,
        priority: NudgePriority.LOW, actions: ['Log weight'], isCelebration: false,
      });
    }

    // Weekly review
    if (isoDayOfWeek(day) === 7 && minutes >= 1080 && minutes <= 1200) {
      out.push({
        key: `weekly-review-${day}`, category: NudgeCategory.PLAN,
        title: 'Your week, in one screen',
        body: "See what moved, what didn't, and what next week looks like.",
        priority: NudgePriority.DEFAULT, actions: ['Open'], isCelebration: false,
      });
    }
  }

  const enabled = new Set(settings.enabledCategories);
  const allowed = out.filter(n => enabled.has(n.category.id) && !state.alreadySentKeys.includes(n.key));

  const celebrations = allowed.filter(n => n.isCelebration);
  const demands = allowed.filter(n => !n.isCelebration);
  const budget = Math.max(settings.maxPerDay - state.sentTodayCount, 0);

  // When the budget is tight the most urgent thing survives, not the first one found.
  const ranked = [...demands].sort((a, b) => b.priority - a.priority).slice(0, budget);
  return [...celebrations, ...ranked];
}


// ---------------------------------------------------------------------------
// Imperial display
//
// Every formula above is metric because that's what they're defined in. Conversion happens here,
// at the boundary, so the numbers the app reasons about and the numbers it shows can never drift
// apart the way they do when units are converted early and carried around.
// ---------------------------------------------------------------------------

export const CM_PER_INCH = 2.54;
export const M_PER_MILE = 1609.344;
export const FT_PER_M = 3.280839895;

export const kgToLb = kg => kg * LB_PER_KG;
export const lbToKg = lb => lb / LB_PER_KG;
export const cmToInches = cm => cm / CM_PER_INCH;
export const ftInToCm = (feet, inches) => (feet * 12 + inches) * CM_PER_INCH;

export function cmToFtIn(cm) {
  const total = Math.round(cmToInches(cm));
  return { feet: Math.trunc(total / 12), inches: total % 12 };
}

export const metresToMiles = m => m / M_PER_MILE;
export const metresToFeet = m => m * FT_PER_M;

/** Body weight, e.g. "187.4 lb". */
export const formatWeight = (kg, decimals = 1) => `${kgToLb(kg).toFixed(decimals)} lb`;
export const formatHeight = cm => { const { feet, inches } = cmToFtIn(cm); return `${feet}'${inches}"`; };
export const formatMiles = (m, decimals = 2) => `${metresToMiles(m).toFixed(decimals)} mi`;
export const formatFeet = m => `${fmt(Math.round(metresToFeet(m)))} ft`;

/** Running pace in minutes per mile — the only pace an American runner reads instinctively. */
export function paceSecondsPerMile(distanceMetres, durationSeconds) {
  if (distanceMetres <= 0 || durationSeconds <= 0) return null;
  return durationSeconds / metresToMiles(distanceMetres);
}

/** Weekly rate as pounds, signed, e.g. "-1.1 lb". */
export const formatRateLb = kgPerWeek => `${kgPerWeek >= 0 ? '+' : ''}${kgToLb(kgPerWeek).toFixed(1)} lb`;

// ---------------------------------------------------------------------------
// Date helpers — plain ISO strings, no timezone surprises
// ---------------------------------------------------------------------------

export function addDays(iso, n) {
  const d = new Date(iso + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

export function daysBetween(fromISO, toISO) {
  return Math.round((Date.parse(toISO + 'T00:00:00Z') - Date.parse(fromISO + 'T00:00:00Z')) / 86400000);
}

/** ISO-8601 day of week: Monday = 1 … Sunday = 7. */
export function isoDayOfWeek(iso) {
  const d = new Date(iso + 'T00:00:00Z').getUTCDay();
  return d === 0 ? 7 : d;
}

export function startOfWeek(iso) {
  return addDays(iso, -(isoDayOfWeek(iso) - 1));
}

export const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

function median(values) {
  if (!values.length) return 0;
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.trunc(s.length / 2);
  return s.length % 2 === 0 ? Math.trunc((s[mid - 1] + s[mid]) / 2) : s[mid];
}

function interpolate(table, speed) {
  if (speed <= table[0][0]) return table[0][1];
  if (speed >= table[table.length - 1][0]) return table[table.length - 1][1];
  for (let i = 0; i < table.length - 1; i++) {
    const [s1, m1] = table[i], [s2, m2] = table[i + 1];
    if (speed >= s1 && speed <= s2) return m1 + (speed - s1) / (s2 - s1) * (m2 - m1);
  }
  return table[table.length - 1][1];
}

const toMinutes = hhmm => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));

export function formatClock(hhmm) {
  const h = Number(hhmm.slice(0, 2)), m = Number(hhmm.slice(3, 5));
  const hour12 = h % 12 === 0 ? 12 : h % 12;
  const suffix = h < 12 ? 'am' : 'pm';
  return m === 0 ? `${hour12}${suffix}` : `${hour12}:${String(m).padStart(2, '0')}${suffix}`;
}

export const fmt = n => n >= 1000 ? n.toLocaleString('en-US') : String(n);

function formatKm(metres) {
  const km = metres / 1000;
  return km % 1 === 0 ? `${Math.round(km)} km` : `${km.toFixed(1)} km`;
}

export function ageOn(birthYear, iso) {
  return Number(iso.slice(0, 4)) - birthYear;
}
