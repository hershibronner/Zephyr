package dev.zephyr.core.activity

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitsTest {

    /** 2 * PI * 6_371_000 / 360 — the same sphere Geo measures on. */
    private val METRES_PER_DEGREE = 111_194.92664455873

    /**
     * Builds a straight northward track at a steady pace.
     *
     * The metres-per-degree figure must match the earth radius [Geo] uses, not the WGS84 value.
     * They differ by about 0.1%, which sounds negligible and is not: it is enough for a track built
     * as "exactly three miles" to measure a few metres short and never close its third split.
     */
    private fun track(
        metres: Double,
        secondsPerMile: Double,
        stepMetres: Double = 20.0,
        startMillis: Long = 0,
    ): List<GeoPoint> {
        val secondsPerMetre = secondsPerMile / SPLIT_UNIT_METRES

        fun at(travelled: Double) = GeoPoint(
            latitude = 51.5 + travelled / METRES_PER_DEGREE,
            longitude = -0.12,
            altitudeMetres = 10.0,
            timestampMillis = startMillis + (travelled * secondsPerMetre * 1000).toLong(),
            accuracyMetres = 5f,
        )

        val points = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled < metres) {
            points += at(travelled)
            travelled += stepMetres
        }
        // Land exactly on the requested distance. Without this the track stops just short — a
        // "3 mile" run would be 4820 m, and the third split would never close.
        points += at(metres)
        return points
    }

    @Test
    fun `a three mile run produces three complete splits`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 3, secondsPerMile = 540.0))

        assertEquals(3, splits.count { it.isComplete })
        assertEquals(listOf(1, 2, 3), splits.filter { it.isComplete }.map { it.index })
    }

    @Test
    fun `each split reports the pace that was actually run`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 2, secondsPerMile = 540.0))

        splits.filter { it.isComplete }.forEach { split ->
            val pace = assertNotNull(split.paceSecondsPerUnit)
            assertTrue(abs(pace - 540.0) < 15.0, "expected ~9:00/mi, got $pace seconds")
        }
    }

    @Test
    fun `the leftover distance is reported but never counted as a full mile`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 1.5, secondsPerMile = 540.0))

        assertEquals(1, splits.count { it.isComplete })
        val tail = splits.last()
        assertTrue(!tail.isComplete)
        assertTrue(tail.distanceMetres < SPLIT_UNIT_METRES)
    }

    @Test
    fun `a run under a mile is all tail and no complete split`() {
        val splits = Splits.perMile(track(metres = 800.0, secondsPerMile = 600.0))

        assertEquals(0, splits.count { it.isComplete })
        assertEquals(1, splits.size)
    }

    @Test
    fun `split distances sum to the distance actually covered`() {
        val distance = SPLIT_UNIT_METRES * 2.4
        val splits = Splits.perMile(track(metres = distance, secondsPerMile = 500.0))
        val summed = splits.sumOf { it.distanceMetres }

        // Within one sampling step, since the track is built in 20 m increments.
        assertTrue(abs(summed - distance) < 40.0, "splits summed to $summed, track was $distance")
    }

    @Test
    fun `split durations sum to the elapsed time`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 3, secondsPerMile = 480.0))
        val summed = splits.sumOf { it.durationSeconds }

        assertTrue(abs(summed - 3 * 480) <= 5, "durations summed to $summed, expected ~1440")
    }

    @Test
    fun `boundaries are interpolated rather than snapped to the nearest fix`() {
        // Coarse sampling: 300 m between fixes means snapping would misplace a boundary badly.
        val splits = Splits.perMile(
            track(metres = SPLIT_UNIT_METRES * 2, secondsPerMile = 600.0, stepMetres = 300.0),
        )

        splits.filter { it.isComplete }.forEach { split ->
            assertEquals(SPLIT_UNIT_METRES, split.distanceMetres, 0.001)
        }
    }

    @Test
    fun `a track too short to measure yields no splits`() {
        assertTrue(Splits.perMile(emptyList()).isEmpty())
        assertTrue(Splits.perMile(listOf(GeoPoint(51.5, -0.12))).isEmpty())
    }

    @Test
    fun `the fastest mile is found among complete splits only`() {
        val slow = track(metres = SPLIT_UNIT_METRES, secondsPerMile = 600.0)
        val fastStart = slow.last().timestampMillis
        val fast = track(metres = SPLIT_UNIT_METRES, secondsPerMile = 480.0, startMillis = fastStart)
            .map { it.copy(latitude = it.latitude + SPLIT_UNIT_METRES / METRES_PER_DEGREE) }

        val splits = Splits.perMile(slow + fast)
        val fastest = assertNotNull(Splits.fastest(splits))

        assertTrue(fastest.isComplete)
        assertTrue(fastest.paceSecondsPerUnit!! < 560.0)
    }

    @Test
    fun `a steady run is not called a negative split`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 4, secondsPerMile = 540.0))
        assertTrue(!Splits.isNegativeSplit(splits))
    }

    @Test
    fun `finishing faster than starting is recognised as a negative split`() {
        val first = track(metres = SPLIT_UNIT_METRES * 2, secondsPerMile = 600.0)
        val second = track(
            metres = SPLIT_UNIT_METRES * 2,
            secondsPerMile = 480.0,
            startMillis = first.last().timestampMillis,
        ).map { it.copy(latitude = it.latitude + 2 * SPLIT_UNIT_METRES / METRES_PER_DEGREE) }

        assertTrue(Splits.isNegativeSplit(Splits.perMile(first + second)))
    }

    @Test
    fun `an even run scores high on consistency`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 4, secondsPerMile = 540.0))
        val steady = assertNotNull(Splits.consistency(splits))

        assertTrue(steady > 0.9f, "steady run scored $steady")
    }

    @Test
    fun `consistency needs more than one mile to mean anything`() {
        assertNull(Splits.consistency(Splits.perMile(track(metres = 900.0, secondsPerMile = 540.0))))
    }

    @Test
    fun `consistency stays within its stated range for a wildly uneven run`() {
        val a = track(metres = SPLIT_UNIT_METRES, secondsPerMile = 400.0)
        val b = track(
            metres = SPLIT_UNIT_METRES,
            secondsPerMile = 1200.0,
            startMillis = a.last().timestampMillis,
        ).map { it.copy(latitude = it.latitude + SPLIT_UNIT_METRES / METRES_PER_DEGREE) }

        val steady = assertNotNull(Splits.consistency(Splits.perMile(a + b)))
        assertTrue(steady in 0f..1f, "consistency escaped its range: $steady")
    }

    @Test
    fun `low accuracy fixes are excluded so a bad signal cannot invent distance`() {
        val clean = track(metres = SPLIT_UNIT_METRES, secondsPerMile = 540.0)
        val noisy = clean + GeoPoint(52.5, -0.12, 10.0, clean.last().timestampMillis + 1000, 200f)

        assertEquals(
            Splits.perMile(clean).sumOf { it.distanceMetres },
            Splits.perMile(noisy).sumOf { it.distanceMetres },
            1.0,
        )
    }

    @Test
    fun `the verdict describes a real run without shaming a short one`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 3, secondsPerMile = 540.0))
        val verdict = SessionVerdict.describe(
            type = ActivityType.RUN,
            distanceMetres = SPLIT_UNIT_METRES * 3,
            durationSeconds = 1620,
            splits = splits,
            longestRecentMetres = null,
        )

        assertTrue(verdict.isNotBlank())
        listOf("fail", "bad", "slow", "should have").forEach {
            assertTrue(it !in verdict.lowercase(), "verdict shames the user: $verdict")
        }
    }

    @Test
    fun `a new distance record is called out`() {
        val splits = Splits.perMile(track(metres = SPLIT_UNIT_METRES * 4, secondsPerMile = 540.0))
        val verdict = SessionVerdict.describe(
            type = ActivityType.RUN,
            distanceMetres = SPLIT_UNIT_METRES * 4,
            durationSeconds = 2160,
            splits = splits,
            longestRecentMetres = SPLIT_UNIT_METRES * 3,
        )

        assertTrue("longest" in verdict.lowercase(), "expected a record callout, got: $verdict")
    }

    @Test
    fun `a very short session still gets a kind verdict`() {
        val verdict = SessionVerdict.describe(ActivityType.WALK, 120.0, 40, emptyList(), null)
        assertTrue(verdict.isNotBlank())
        assertTrue("counts" in verdict.lowercase())
    }
}
