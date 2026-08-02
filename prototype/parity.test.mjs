/**
 * Replays every fixture the Kotlin test suite emitted through the JavaScript port.
 *
 * The web prototype exists so the design can be used and judged before it is committed to Compose.
 * That is only worth anything if the prototype's numbers are the app's numbers — a prototype that
 * quietly disagrees produces confident decisions about figures the user will never see.
 *
 * Run: node prototype/parity.test.mjs
 * Regenerate fixtures: gradle -p core test
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import * as Z from './zephyr-core.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixtures = JSON.parse(readFileSync(join(here, 'parity-fixtures.json'), 'utf8'));

const EPSILON = 1e-6;
let passed = 0;
const failures = [];

const near = (a, b) => typeof a === 'number' && typeof b === 'number'
  ? Math.abs(a - b) <= EPSILON + Math.abs(b) * 1e-9
  : a === b;

function check(label, actual, expected, path = '') {
  if (expected === null || expected === undefined) {
    if (actual !== null && actual !== undefined) {
      failures.push(`${label}${path}: expected null, got ${actual}`);
    }
    return;
  }
  if (typeof expected === 'object' && !Array.isArray(expected)) {
    for (const k of Object.keys(expected)) check(label, actual?.[k], expected[k], `${path}.${k}`);
    return;
  }
  if (Array.isArray(expected)) {
    if (!Array.isArray(actual) || actual.length !== expected.length) {
      failures.push(`${label}${path}: length ${actual?.length} != ${expected.length}`);
      return;
    }
    expected.forEach((e, i) => check(label, actual[i], e, `${path}[${i}]`));
    return;
  }
  if (!near(actual, expected)) {
    failures.push(`${label}${path}: got ${JSON.stringify(actual)}, expected ${JSON.stringify(expected)}`);
  }
}

for (const c of fixtures.cases) {
  const label = `${c.fn}${c.name ? `(${c.name})` : ''}`;
  const a = c.args;
  let actual;

  switch (c.fn) {
    case 'bmr':
      actual = Z.bmr(a[0], a[1], a[2], a[3]);
      break;

    case 'calorieTarget':
      actual = Z.calorieTarget(a[0], a[1], a[2], a[3]);
      break;

    case 'macros':
      actual = Z.macros(a[0], a[1], a[2]);
      break;

    case 'fittedChangeKg':
      actual = Z.fittedChangeKg(a[0]);
      break;

    case 'weightTrend': {
      const r = Z.weightTrend(a[0]);
      actual = { currentTrendKg: r.currentTrendKg, weeklyRateKg: r.weeklyRateKg, points: r.points.length };
      break;
    }

    case 'adaptiveTdee': {
      const r = Z.adaptiveTdee(a[0], a[1], a[3] - a[2]);
      actual = {
        tdeeKcal: r.tdeeKcal, measuredTdeeKcal: r.measuredTdeeKcal,
        confidence: r.confidence, daysOfData: r.daysOfData,
      };
      break;
    }

    case 'activityBurn': {
      const r = Z.activityBurn(a[0], a[1], a[2], a[3], a[4]);
      actual = { kcal: r.kcal, restingKcal: r.restingKcal, netKcal: r.netKcal };
      break;
    }

    case 'suggestStepGoal': {
      const r = Z.suggestStepGoal(a[0], a[1]);
      actual = { goal: r.goal, baselineMedian: r.baselineMedian };
      break;
    }

    case 'stepStatus': {
      const r = Z.stepStatus(a[0], a[1], a[2], a[3]);
      actual = {
        expectedByNow: r.expectedByNow, deficit: r.deficit, onTrack: r.onTrack,
        projectedEndOfDay: r.projectedEndOfDay, remaining: r.remaining,
        fractionOfGoal: r.fractionOfGoal,
      };
      break;
    }

    case 'calculateStreak': {
      const r = Z.calculateStreak(a[0], a[1]);
      actual = { current: r.current, longest: r.longest, atRisk: r.atRisk };
      break;
    }

    case 'prescriptionsForWeek': {
      const p = Z.prescriptionsForWeek(Z.defaultTemplate(), a[0], a[1], { longestRunMetres: a[2], longestHikeMinutes: null });
      actual = p.map(x => ({
        slotId: x.slot.id, date: x.date,
        distance: x.targetDistanceMetres ?? null,
        minutes: x.targetDurationMinutes ?? null,
        isDeload: x.isDeload, headline: x.headline,
      }));
      break;
    }

    case 'weeklySummary': {
      const week = Array.from({ length: 7 }, (_, i) => Z.energyBalance({
        date: Z.addDays('2026-01-01', i),
        targetKcal: a[2], consumedKcal: a[0], exerciseKcal: a[1],
        proteinTargetG: 150, macros: { proteinG: 140, carbsG: 0, fatG: 0 },
        maintenanceKcal: a[3],
      }));
      const s = Z.weeklySummary(week);
      actual = {
        daysLogged: s.daysLogged, averageIntakeKcal: s.averageIntakeKcal,
        averageDeltaKcal: s.averageDeltaKcal, adherencePercent: s.adherencePercent,
        projectedWeeklyKg: s.projectedWeeklyKg,
      };
      break;
    }

    default:
      failures.push(`${label}: no handler for this fixture type`);
      continue;
  }

  const before = failures.length;
  check(label, actual, c.expect);
  if (failures.length === before) passed++;
}

const total = fixtures.cases.length;
if (failures.length) {
  console.error(`\n✗ Kotlin/JavaScript parity FAILED — ${passed}/${total} cases agree\n`);
  failures.slice(0, 40).forEach(f => console.error('  ' + f));
  if (failures.length > 40) console.error(`  … and ${failures.length - 40} more`);
  console.error('\nThe prototype and the Android app disagree. Fix the JS port, or regenerate');
  console.error('fixtures with `gradle -p core test` if the Kotlin was changed deliberately.\n');
  process.exit(1);
}

console.log(`✓ Kotlin/JavaScript parity: ${passed}/${total} cases agree exactly`);
