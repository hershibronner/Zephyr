package dev.zephyr.core.fasting

import dev.zephyr.core.model.GoalPace
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FastingTest {

    private val dinnerAt8 = LocalDateTime.of(2026, 3, 10, 20, 0)

    @Test
    fun `the user's own example - dinner at eight on 14 hours means ten in the morning`() {
        val status = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, dinnerAt8.plusHours(1))

        assertEquals(LocalDateTime.of(2026, 3, 11, 10, 0), status.opensAt)
        assertTrue(status.isFasting)
    }

    @Test
    fun `counts down to the minute`() {
        val now = LocalDateTime.of(2026, 3, 11, 7, 30)
        val status = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, now)

        // 07:30 to 10:00 is two and a half hours.
        assertEquals(150, status.remainingMinutes)
        assertEquals("2h 30m", status.remainingLabel)
    }

    @Test
    fun `the window opening flips the phase`() {
        val justBefore = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, LocalDateTime.of(2026, 3, 11, 9, 59))
        val justAfter = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, LocalDateTime.of(2026, 3, 11, 10, 1))

        assertEquals(FastingPhase.FASTING, justBefore.phase)
        assertEquals(FastingPhase.EATING, justAfter.phase)
    }

    @Test
    fun `the eating window closes after its own length`() {
        // 14:10 opens at 10:00, so it should close at 20:00.
        val inWindow = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, LocalDateTime.of(2026, 3, 11, 19, 0))
        val afterWindow = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, LocalDateTime.of(2026, 3, 11, 20, 30))

        assertEquals(FastingPhase.EATING, inWindow.phase)
        assertEquals(FastingPhase.WINDOW_CLOSED, afterWindow.phase)
    }

    @Test
    fun `progress runs from nothing to full across the fast`() {
        val quarter = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, dinnerAt8.plusMinutes(210))
        assertEquals(0.25f, quarter.fraction, 0.01f)

        val done = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, dinnerAt8.plusHours(14))
        assertEquals(1f, done.fraction, 0.01f)
    }

    @Test
    fun `progress never exceeds full no matter how long the fast runs`() {
        val status = Fasting.status(FastingPlan.SIXTEEN, dinnerAt8, dinnerAt8.plusHours(40))
        assertTrue(status.fraction <= 1f)
    }

    @Test
    fun `with no meal logged there is no clock to show`() {
        val status = Fasting.status(FastingPlan.SIXTEEN, null, dinnerAt8)

        assertEquals(FastingPhase.OFF, status.phase)
        assertNull(status.opensAt)
    }

    @Test
    fun `turning fasting off reports nothing regardless of history`() {
        val status = Fasting.status(FastingPlan.OFF, dinnerAt8, dinnerAt8.plusHours(3))
        assertEquals(FastingPhase.OFF, status.phase)
    }

    @Test
    fun `every plan splits the day into a fast and a window that add to 24 hours`() {
        FastingPlan.entries.filter { it != FastingPlan.OFF }.forEach { plan ->
            assertEquals(24, plan.fastingHours + plan.eatingHours, "${plan.label} does not fill a day")
        }
    }

    @Test
    fun `recommendations stay in the range a beginner can actually hold`() {
        GoalPace.entries.forEach { pace ->
            val plan = Fasting.recommend(pace)
            assertTrue(
                plan.fastingHours in 12..16,
                "${pace.label} was recommended ${plan.label}, which is too aggressive to start on",
            )
        }
    }

    @Test
    fun `someone not in a deficit is not pushed into a long fast`() {
        assertEquals(FastingPlan.TWELVE, Fasting.recommend(GoalPace.MAINTAIN))
        assertEquals(FastingPlan.TWELVE, Fasting.recommend(GoalPace.GAIN_SLOW))
    }

    @Test
    fun `an aggressive goal gets the longer of the two starting windows`() {
        assertEquals(FastingPlan.SIXTEEN, Fasting.recommend(GoalPace.LOSE_AGGRESSIVE))
        assertEquals(FastingPlan.FOURTEEN, Fasting.recommend(GoalPace.LOSE_EASY))
    }

    @Test
    fun `no message ever shames the user for eating`() {
        val phrases = listOf("fail", "cheat", "bad", "should have", "guilty", "ruined")

        FastingPlan.entries.forEach { plan ->
            listOf(0L, 3L, 10L, 14L, 20L, 30L).forEach { hours ->
                val status = Fasting.status(plan, dinnerAt8, dinnerAt8.plusHours(hours))
                val message = Fasting.message(status).lowercase()
                phrases.forEach { phrase ->
                    assertTrue(phrase !in message, "\"$message\" shames the user")
                }
            }
        }
    }

    @Test
    fun `the message always tells the user something concrete`() {
        val status = Fasting.status(FastingPlan.SIXTEEN, dinnerAt8, dinnerAt8.plusHours(10))
        assertTrue(Fasting.message(status).isNotBlank())
        assertNotNull(status.opensAt)
    }

    @Test
    fun `hours since the last meal is reported for a weigh-in`() {
        assertEquals(12, Fasting.hoursSince(dinnerAt8, dinnerAt8.plusHours(12)))
        assertNull(Fasting.hoursSince(null, dinnerAt8))
    }

    @Test
    fun `a clock that has run backwards does not produce negative time`() {
        val status = Fasting.status(FastingPlan.FOURTEEN, dinnerAt8, dinnerAt8.minusHours(2))
        assertTrue(status.elapsedMinutes >= 0)
        assertTrue(status.remainingMinutes >= 0)
    }
}
