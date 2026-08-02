package dev.zephyr.core.nudge

import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.ledger.MacroTotals
import dev.zephyr.core.plan.PlannedSlot
import dev.zephyr.core.plan.Prescription
import dev.zephyr.core.steps.StepPace
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NudgeEngineTest {

    private val monday = LocalDate.of(2026, 6, 1)

    private fun balance(consumed: Int = 800, target: Int = 2000) = EnergyBalance(
        date = monday,
        targetKcal = target,
        consumedKcal = consumed,
        exerciseKcal = 0,
        proteinTargetG = 150,
        macros = MacroTotals(proteinG = 60),
    )

    private fun state(
        time: LocalTime,
        steps: Int = 3000,
        goal: Int = 8000,
        stepsInLastHour: Int = 500,
        streak: Int = 0,
        secured: Boolean = true,
        prescriptions: List<Prescription> = emptyList(),
        missed: List<Prescription> = emptyList(),
        consumed: Int = 800,
        sentToday: Int = 0,
        alreadySent: Set<String> = emptySet(),
        tracking: Boolean = false,
        date: LocalDate = monday,
        daysSinceWeighIn: Int? = 0,
    ) = CoachState(
        now = LocalDateTime.of(date, time),
        balance = balance(consumed),
        stepStatus = StepPace.status(steps, goal, time),
        stepsInLastHour = stepsInLastHour,
        streakDays = streak,
        streakSecuredToday = secured,
        todaysPrescriptions = prescriptions,
        missedYesterday = missed,
        lastFoodLogAt = LocalDateTime.of(date, time).minusHours(1),
        daysSinceWeighIn = daysSinceWeighIn,
        alreadySentKeys = alreadySent,
        sentTodayCount = sentToday,
        isTrackingSession = tracking,
    )

    private fun prescription(at: LocalTime, type: ActivityType = ActivityType.RUN) = Prescription(
        slot = PlannedSlot(id = 1, dayOfWeek = DayOfWeek.MONDAY, type = type, timeOfDay = at),
        date = monday,
        targetDistanceMetres = 5000.0,
        headline = "Run — 5 km",
        detail = "Conversational pace.",
    )

    private val balanced = NudgeSettings(tone = CoachTone.BALANCED)

    // --- The rule that keeps the app installed: silence when silence is right --------------

    @Test
    fun `says nothing during quiet hours`() {
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(2, 30), steps = 100), balanced)
        assertTrue(nudges.isEmpty(), "notified at 2:30am: $nudges")
    }

    @Test
    fun `says nothing at any hour of the night`() {
        for (hour in listOf(22, 23, 0, 1, 3, 5, 6)) {
            val nudges = NudgeEngine.evaluate(state(LocalTime.of(hour, 15), steps = 200), balanced)
            assertTrue(nudges.isEmpty(), "notified at $hour:15 with $nudges")
        }
    }

    @Test
    fun `stays quiet while a session is being tracked`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(18, 0), steps = 2000, tracking = true),
            NudgeSettings(tone = CoachTone.RELENTLESS),
        )
        assertTrue(nudges.isEmpty(), "interrupted the user mid-run: $nudges")
    }

    @Test
    fun `never repeats something already sent`() {
        val first = NudgeEngine.evaluate(state(LocalTime.of(8, 0)), balanced)
        assertTrue(first.isNotEmpty())

        val second = NudgeEngine.evaluate(
            state(LocalTime.of(8, 30), alreadySent = first.map { it.key }.toSet()),
            balanced,
        )
        assertTrue(second.none { it.key in first.map { n -> n.key } })
    }

    @Test
    fun `respects the daily budget`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), steps = 5000, streak = 10, secured = false, sentToday = 5),
            balanced,
        )
        assertTrue(nudges.none { !it.isCelebration }, "exceeded the daily cap: $nudges")
    }

    @Test
    fun `keeps the most urgent nudge when the budget is nearly spent`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), steps = 6000, streak = 12, secured = false, sentToday = 4),
            balanced,
        )
        val demands = nudges.filter { !it.isCelebration }
        assertEquals(1, demands.size)
        assertEquals(NudgePriority.HIGH, demands.single().priority)
    }

    @Test
    fun `celebrations are not charged against the daily budget`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), steps = 9000, sentToday = 99),
            balanced,
        )
        assertTrue(nudges.any { it.isCelebration }, "a win went unacknowledged because of the cap")
    }

    @Test
    fun `disabled categories are never delivered`() {
        val settings = balanced.copy(enabledCategories = setOf(NudgeCategory.CELEBRATION))
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(8, 0)), settings)
        assertTrue(nudges.all { it.category == NudgeCategory.CELEBRATION })
    }

    // --- Tone -------------------------------------------------------------------------------

    @Test
    fun `gentle tone does not chase`() {
        val gentle = NudgeSettings(tone = CoachTone.GENTLE)
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), steps = 5000, streak = 10, secured = false),
            gentle,
        )
        assertTrue(
            nudges.none { it.category == NudgeCategory.MOVEMENT || it.category == NudgeCategory.STREAK },
            "gentle tone chased the user: $nudges",
        )
    }

    @Test
    fun `only relentless sends inactivity reminders`() {
        val idle = state(LocalTime.of(14, 0), steps = 1000, stepsInLastHour = 10)

        assertTrue(NudgeEngine.evaluate(idle, balanced).none { it.key.startsWith("inactive") })
        assertTrue(
            NudgeEngine.evaluate(idle, NudgeSettings(tone = CoachTone.RELENTLESS))
                .any { it.key.startsWith("inactive") },
        )
    }

    @Test
    fun `relentless still honours quiet hours`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(23, 0), steps = 100, stepsInLastHour = 0),
            NudgeSettings(tone = CoachTone.RELENTLESS),
        )
        assertTrue(nudges.isEmpty())
    }

    // --- Being useful -----------------------------------------------------------------------

    @Test
    fun `sends a morning plan naming today's session`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(8, 0), prescriptions = listOf(prescription(LocalTime.of(18, 30)))),
            balanced,
        )
        val morning = nudges.single { it.key.startsWith("morning") }

        assertTrue(morning.title.contains("Run"), "morning plan didn't name the session: ${morning.title}")
        assertTrue(morning.body.contains("6:30pm"), "morning plan didn't say when: ${morning.body}")
    }

    @Test
    fun `reminds about a session shortly before it starts`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(18, 15), prescriptions = listOf(prescription(LocalTime.of(18, 30)))),
            balanced,
        )
        val reminder = nudges.single { it.category == NudgeCategory.SESSION }

        assertTrue(reminder.title.contains("15 min"))
        assertEquals(NudgePriority.HIGH, reminder.priority)
        assertTrue(NudgeAction.START_SESSION in reminder.actions)
    }

    @Test
    fun `does not remind about a session hours in advance`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(12, 0), prescriptions = listOf(prescription(LocalTime.of(18, 30)))),
            balanced,
        )
        assertTrue(nudges.none { it.category == NudgeCategory.SESSION })
    }

    @Test
    fun `the evening push names the walk length, not the failure`() {
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(19, 0), steps = 6800, goal = 8000), balanced)
        val push = nudges.single { it.key.startsWith("close-ring") }

        assertTrue(push.title.contains("1,200"))
        assertTrue(push.body.contains("minutes"), "didn't translate steps into an action: ${push.body}")
        assertTrue(NudgeAction.START_WALK in push.actions)
    }

    @Test
    fun `does not push to close a hopeless gap`() {
        // Asking someone on 500 steps to walk 7,500 more at 7pm is a reproach, not a nudge.
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(19, 30), steps = 500, goal = 8000), balanced)
        assertTrue(nudges.none { it.key.startsWith("close-ring") })
    }

    @Test
    fun `warns about a streak while it can still be saved`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), streak = 14, secured = false),
            balanced,
        )
        val warning = nudges.single { it.category == NudgeCategory.STREAK }

        assertTrue(warning.title.contains("14"))
        assertEquals(NudgePriority.HIGH, warning.priority)
    }

    @Test
    fun `does not mention a streak that is already safe`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(19, 0), streak = 14, secured = true),
            balanced,
        )
        assertTrue(nudges.none { it.category == NudgeCategory.STREAK })
    }

    @Test
    fun `does not warn about a streak too short to matter`() {
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(19, 0), streak = 1, secured = false), balanced)
        assertTrue(nudges.none { it.category == NudgeCategory.STREAK })
    }

    @Test
    fun `celebrates hitting the step goal`() {
        val nudges = NudgeEngine.evaluate(state(LocalTime.of(17, 0), steps = 8200, goal = 8000), balanced)
        val win = nudges.single { it.isCelebration }
        assertEquals(NudgeCategory.CELEBRATION, win.category)
    }

    @Test
    fun `a missed session is raised without blame`() {
        val nudges = NudgeEngine.evaluate(
            state(LocalTime.of(8, 0), missed = listOf(prescription(LocalTime.of(7, 0)))),
            balanced,
        )
        val callout = nudges.single { it.key.startsWith("missed") }

        assertTrue(
            callout.body.contains("doesn't undo"),
            "missed-session copy should defuse, not scold: ${callout.body}",
        )
    }

    @Test
    fun `weekly review only lands on a Sunday evening`() {
        val sunday = LocalDate.of(2026, 6, 7)
        val onSunday = NudgeEngine.evaluate(state(LocalTime.of(19, 0), date = sunday), balanced)
        assertTrue(onSunday.any { it.key.startsWith("weekly-review") })

        val onMonday = NudgeEngine.evaluate(state(LocalTime.of(19, 0)), balanced)
        assertTrue(onMonday.none { it.key.startsWith("weekly-review") })
    }

    @Test
    fun `asks for a weigh-in only after a real gap`() {
        val recent = NudgeEngine.evaluate(state(LocalTime.of(8, 0), daysSinceWeighIn = 1), balanced)
        assertTrue(recent.none { it.key.startsWith("weigh-in") })

        val stale = NudgeEngine.evaluate(state(LocalTime.of(8, 0), daysSinceWeighIn = 5), balanced)
        assertTrue(stale.any { it.key.startsWith("weigh-in") })
    }

    // --- Copy discipline ---------------------------------------------------------------------

    @Test
    fun `no nudge shames the user`() {
        val shaming = listOf("fail", "failed", "lazy", "guilty", "shame", "bad job", "disappoint")
        val samples = listOf(
            state(LocalTime.of(8, 0), prescriptions = listOf(prescription(LocalTime.of(18, 0)))),
            state(LocalTime.of(14, 0), steps = 500),
            state(LocalTime.of(19, 0), steps = 6500, streak = 9, secured = false),
            state(LocalTime.of(8, 0), missed = listOf(prescription(LocalTime.of(7, 0)))),
            state(LocalTime.of(19, 0), steps = 9000),
        )

        for (sample in samples) {
            for (tone in CoachTone.entries) {
                for (nudge in NudgeEngine.evaluate(sample, NudgeSettings(tone = tone))) {
                    val text = "${nudge.title} ${nudge.body}".lowercase()
                    val offender = shaming.firstOrNull { text.contains(it) }
                    assertTrue(offender == null, "shaming word '$offender' in: ${nudge.title} / ${nudge.body}")
                }
            }
        }
    }

    @Test
    fun `every nudge gives the user something to tap`() {
        val samples = listOf(
            state(LocalTime.of(8, 0), prescriptions = listOf(prescription(LocalTime.of(8, 20)))),
            state(LocalTime.of(14, 0), steps = 500),
            state(LocalTime.of(19, 0), steps = 6500, streak = 9, secured = false),
        )
        for (sample in samples) {
            for (nudge in NudgeEngine.evaluate(sample, NudgeSettings(tone = CoachTone.RELENTLESS))) {
                assertTrue(nudge.actions.isNotEmpty(), "${nudge.key} had no action")
                assertTrue(nudge.title.isNotBlank() && nudge.body.isNotBlank())
            }
        }
    }

    @Test
    fun `nudge keys are unique within a single evaluation`() {
        val nudges = NudgeEngine.evaluate(
            state(
                LocalTime.of(8, 0),
                prescriptions = listOf(prescription(LocalTime.of(8, 20))),
                missed = listOf(prescription(LocalTime.of(7, 0))),
                daysSinceWeighIn = 6,
            ),
            NudgeSettings(tone = CoachTone.RELENTLESS),
        )
        assertEquals(nudges.map { it.key }.distinct().size, nudges.size)
    }

    @Test
    fun `a full day never exceeds the daily budget in aggregate`() {
        // Walk a whole waking day minute-by-minute, carrying sent-state forward the way the app does.
        val sent = mutableSetOf<String>()
        var demandCount = 0

        var time = LocalTime.of(6, 0)
        while (time < LocalTime.of(23, 0)) {
            val nudges = NudgeEngine.evaluate(
                state(
                    time,
                    steps = 400,
                    stepsInLastHour = 5,
                    streak = 12,
                    secured = false,
                    prescriptions = listOf(prescription(LocalTime.of(18, 30))),
                    alreadySent = sent,
                    sentToday = demandCount,
                    daysSinceWeighIn = 9,
                ),
                balanced,
            )
            nudges.forEach { sent += it.key }
            demandCount += nudges.count { !it.isCelebration }
            time = time.plusMinutes(5)
        }

        assertTrue(
            demandCount <= balanced.maxPerDay,
            "sent $demandCount demanding notifications in one day, cap is ${balanced.maxPerDay}",
        )
        assertTrue(demandCount > 0, "the coach said nothing all day")
    }
}

class QuietHoursTest {

    @Test
    fun `a window wrapping past midnight is handled`() {
        val quiet = QuietHours(LocalTime.of(21, 30), LocalTime.of(7, 0))

        assertTrue(quiet.contains(LocalTime.of(23, 0)))
        assertTrue(quiet.contains(LocalTime.of(3, 0)))
        assertTrue(quiet.contains(LocalTime.of(6, 59)))
        assertFalse(quiet.contains(LocalTime.of(7, 0)))
        assertFalse(quiet.contains(LocalTime.of(12, 0)))
        assertFalse(quiet.contains(LocalTime.of(21, 29)))
    }

    @Test
    fun `a same-day window works too`() {
        val quiet = QuietHours(LocalTime.of(9, 0), LocalTime.of(17, 0))

        assertTrue(quiet.contains(LocalTime.of(12, 0)))
        assertFalse(quiet.contains(LocalTime.of(8, 0)))
        assertFalse(quiet.contains(LocalTime.of(18, 0)))
    }
}
