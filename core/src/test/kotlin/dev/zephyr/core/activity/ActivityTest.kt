package dev.zephyr.core.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityCaloriesTest {

    @Test
    fun `a 10k run in an hour lands in the expected calorie range`() {
        val burn = ActivityCalories.estimate(
            type = ActivityType.RUN,
            durationSeconds = 3600,
            distanceMetres = 10_000.0,
            elevationGainMetres = 0.0,
            weightKg = 80.0,
        )
        // ~10 MET for a 6:00/km pace at 80kg gives roughly 800-1000 kcal gross.
        assertTrue(burn.kcal in 700..1050, "got ${burn.kcal} kcal, outside a plausible range")
    }

    @Test
    fun `net calories exclude the resting metabolism already counted in TDEE`() {
        val burn = ActivityCalories.estimate(
            ActivityType.RUN, 3600, 10_000.0, 0.0, 80.0,
        )
        assertTrue(burn.restingKcal > 0)
        assertEquals(burn.kcal - burn.restingKcal, burn.netKcal)
        assertTrue(
            burn.netKcal < burn.kcal,
            "net burn must be lower than gross or the daily budget double-counts",
        )
    }

    @Test
    fun `climbing meaningfully increases the burn for an identical hike`() {
        val flat = ActivityCalories.estimate(ActivityType.HIKE, 7200, 8000.0, 0.0, 80.0)
        val steep = ActivityCalories.estimate(ActivityType.HIKE, 7200, 8000.0, 800.0, 80.0)

        assertTrue(
            steep.kcal > flat.kcal + 300,
            "800m of climbing only added ${steep.kcal - flat.kcal} kcal",
        )
    }

    @Test
    fun `faster running has a higher MET than walking`() {
        val running = ActivityCalories.metFor(ActivityType.RUN, 12.0)
        val walking = ActivityCalories.metFor(ActivityType.WALK, 5.0)
        assertTrue(running > walking)
    }

    @Test
    fun `MET increases monotonically with speed`() {
        var previous = 0.0
        for (speed in 7..18) {
            val met = ActivityCalories.metFor(ActivityType.RUN, speed.toDouble())
            assertTrue(met >= previous, "MET dropped between speeds at $speed km/h")
            previous = met
        }
    }

    @Test
    fun `speeds beyond the table clamp instead of extrapolating`() {
        val absurd = ActivityCalories.metFor(ActivityType.RUN, 200.0)
        assertTrue(absurd < 25.0, "extrapolated to an impossible MET of $absurd")
    }

    @Test
    fun `zero duration burns nothing`() {
        val burn = ActivityCalories.estimate(ActivityType.RUN, 0, 0.0, 0.0, 80.0)
        assertEquals(0, burn.kcal)
    }

    @Test
    fun `heavier people burn more for identical work`() {
        val light = ActivityCalories.estimate(ActivityType.RUN, 1800, 5000.0, 0.0, 60.0)
        val heavy = ActivityCalories.estimate(ActivityType.RUN, 1800, 5000.0, 0.0, 100.0)
        assertTrue(heavy.kcal > light.kcal)
    }
}

class GeoTest {

    @Test
    fun `haversine matches a known distance`() {
        // Greenwich Observatory to the Louvre is roughly 340 km.
        val greenwich = GeoPoint(51.4769, -0.0005)
        val louvre = GeoPoint(48.8606, 2.3376)
        val distance = Geo.distanceMetres(greenwich, louvre)

        assertTrue(distance in 330_000.0..350_000.0, "got ${distance / 1000} km")
    }

    @Test
    fun `distance between identical points is zero`() {
        val point = GeoPoint(40.0, -74.0)
        assertEquals(0.0, Geo.distanceMetres(point, point), 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(1.0, 0.0)
        assertEquals(111_195.0, Geo.distanceMetres(a, b), 500.0)
    }

    @Test
    fun `stationary jitter accumulates neither distance nor climb`() {
        // A phone sitting on a table, wandering a metre or two and bouncing 2m of altitude.
        val jitter = (0 until 200).map { i ->
            GeoPoint(
                latitude = 40.0 + (i % 3) * 0.000005,
                longitude = -74.0 + (i % 2) * 0.000005,
                altitudeMetres = 100.0 + (i % 4) * 0.5,
                accuracyMetres = 8f,
            )
        }
        val stats = Geo.summarise(jitter)

        assertTrue(stats.distanceMetres < 50.0, "jitter produced ${stats.distanceMetres} m of travel")
        assertEquals(0.0, stats.elevationGainMetres, 0.001)
    }

    @Test
    fun `a real climb is recorded`() {
        val climb = (0 until 50).map { i ->
            GeoPoint(
                latitude = 40.0 + i * 0.0005,
                longitude = -74.0,
                altitudeMetres = 100.0 + i * 10.0,
                accuracyMetres = 5f,
            )
        }
        val stats = Geo.summarise(climb)

        assertTrue(stats.elevationGainMetres > 480.0, "got ${stats.elevationGainMetres} m")
        assertEquals(0.0, stats.elevationLossMetres, 0.001)
    }

    @Test
    fun `descent is tracked separately from ascent`() {
        val downhill = (0 until 30).map { i ->
            GeoPoint(40.0 + i * 0.0005, -74.0, altitudeMetres = 500.0 - i * 10.0, accuracyMetres = 5f)
        }
        val stats = Geo.summarise(downhill)

        assertEquals(0.0, stats.elevationGainMetres, 0.001)
        assertTrue(stats.elevationLossMetres > 250.0)
    }

    @Test
    fun `low accuracy fixes are discarded`() {
        val points = listOf(
            GeoPoint(40.0, -74.0, accuracyMetres = 5f),
            GeoPoint(41.0, -74.0, accuracyMetres = 500f),
            GeoPoint(40.001, -74.0, accuracyMetres = 5f),
        )
        val stats = Geo.summarise(points)

        // The 500m-accuracy point would otherwise add ~200km of phantom distance.
        assertTrue(stats.distanceMetres < 1000.0, "bad fix leaked in: ${stats.distanceMetres} m")
    }

    @Test
    fun `a track shorter than two points reports nothing`() {
        assertEquals(0.0, Geo.summarise(listOf(GeoPoint(40.0, -74.0))).distanceMetres)
        assertEquals(0.0, Geo.summarise(emptyList()).distanceMetres)
    }
}

class PaceTest {

    @Test
    fun `five kilometres in twenty five minutes is five minutes per kilometre`() {
        val pace = Pace.secondsPerKm(5000.0, 1500)
        assertEquals(300.0, pace!!, 0.01)
        assertEquals("5:00", Pace.format(pace))
    }

    @Test
    fun `pace formats with a padded seconds field`() {
        assertEquals("4:05", Pace.format(245.0))
        assertEquals("--:--", Pace.format(null))
    }

    @Test
    fun `zero distance yields no pace rather than infinity`() {
        assertNull(Pace.secondsPerKm(0.0, 600))
    }

    @Test
    fun `speed conversion is consistent with pace`() {
        assertEquals(12.0, Pace.speedKmh(10_000.0, 3000), 0.01)
    }
}
