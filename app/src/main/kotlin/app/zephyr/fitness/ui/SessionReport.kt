package app.zephyr.fitness.ui

import app.zephyr.fitness.data.db.SessionEntity
import dev.zephyr.core.activity.GeoPoint

/**
 * A finished session with everything the report screen needs, assembled once rather than re-read
 * from the database on each recomposition.
 */
data class SessionReport(
    val session: SessionEntity,
    val points: List<GeoPoint>,
    val verdict: String,
)
