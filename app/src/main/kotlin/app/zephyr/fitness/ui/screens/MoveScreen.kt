package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.data.db.SessionEntity
import app.zephyr.fitness.tracking.TrackingPhase
import app.zephyr.fitness.tracking.TrackingState
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.components.ZCard
import app.zephyr.fitness.ui.components.entrance
import app.zephyr.fitness.ui.components.popIn
import app.zephyr.fitness.ui.components.pulsing
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.activity.GeoPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Record a run, walk or hike, and see what you've already done.
 *
 * While a session is live the screen is almost entirely one number — elapsed time — because that is
 * what someone glances at mid-stride, at arm's length, out of breath. Everything else is secondary
 * and sized accordingly.
 */
@Composable
fun MoveScreen(
    tracking: TrackingState,
    history: List<SessionEntity>,
    selected: ActivityType,
    hasLocationPermission: Boolean,
    onSelect: (ActivityType) -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracking.isActive) {
        LiveSession(tracking, onPause, onResume, onFinish, onDiscard, modifier)
    } else {
        Idle(
            history = history,
            selected = selected,
            hasLocationPermission = hasLocationPermission,
            onSelect = onSelect,
            onRequestPermission = onRequestPermission,
            onStart = onStart,
            onOpenSession = onOpenSession,
            modifier = modifier,
        )
    }
}

@Composable
private fun Idle(
    history: List<SessionEntity>,
    selected: ActivityType,
    hasLocationPermission: Boolean,
    onSelect: (ActivityType) -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onOpenSession: (SessionEntity) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(
                modifier = Modifier.entrance(0),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Kicker("Go move")
                Text(
                    "What are we doing?",
                    color = Z.Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.1).sp,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.entrance(1),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TypeTile(ActivityType.RUN, selected, Z.VioletSoft, Z.VioletInk, Modifier.weight(1f), onSelect)
                TypeTile(ActivityType.WALK, selected, Z.SkySoft, Z.SkyInk, Modifier.weight(1f), onSelect)
                TypeTile(ActivityType.HIKE, selected, Z.GreenSoft, Z.GreenInk, Modifier.weight(1f), onSelect)
            }
        }

        item {
            ControlButton(
                label = "Start " + selected.label.lowercase(),
                background = Z.Nav,
                foreground = Color.White,
                modifier = Modifier.fillMaxWidth().entrance(2),
                onClick = onStart,
            )
        }

        if (!hasLocationPermission) {
            item {
                // Said plainly and before the system dialog, because a permission prompt with no
                // stated reason is the one people reflexively deny — and a denial here is sticky.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Z.WarnSoft)
                        .clickable(onClick = onRequestPermission)
                        .padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        "Zephyr needs location to track a route",
                        color = Z.WarnInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Only while a session is running, and only to measure distance, pace and " +
                            "climb. Nothing leaves your phone. Tap to allow.",
                        color = Z.WarnInk.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
            }
        }

        if (history.isNotEmpty()) {
            item { SectionHeader("Recent") }
            items(history, key = { it.id }) { session ->
                HistoryRow(session) { onOpenSession(session) }
            }
        } else {
            item {
                ZCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Nothing recorded yet",
                        color = Z.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Pick one above and walk out the door. Even fifteen minutes counts — the " +
                            "first one is the only hard one.",
                        color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

/**
 * Picks the activity. Selecting is not starting — an accidental brush of a tile used to open a GPS
 * session and a foreground notification with no way to say "I didn't mean that".
 */
@Composable
private fun TypeTile(
    type: ActivityType,
    selected: ActivityType,
    background: Color,
    foreground: Color,
    modifier: Modifier,
    onSelect: (ActivityType) -> Unit,
) {
    val on = type == selected
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(if (on) background else Z.Card)
            .border(
                width = if (on) 2.dp else 1.dp,
                color = if (on) foreground.copy(alpha = 0.55f) else Z.Line,
                shape = RoundedCornerShape(Z.TileRadius),
            )
            .clickable { onSelect(type) }
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (on) Color.White.copy(alpha = 0.6f) else Z.Page),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when (type) {
                    ActivityType.RUN -> Icons.Filled.DirectionsRun
                    ActivityType.HIKE -> Icons.Filled.Terrain
                    else -> Icons.Filled.DirectionsWalk
                },
                contentDescription = null,
                tint = if (on) foreground else Z.Faint,
                modifier = Modifier.size(21.dp),
            )
        }
        Text(
            type.label,
            color = if (on) foreground else Z.Muted,
            fontSize = 14.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LiveSession(
    state: TrackingState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier,
) {
    val paused = state.phase == TrackingPhase.PAUSED

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp)
            .padding(bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kicker(if (paused) "${state.type.label} · paused" else state.type.label)
            GpsPill(state.gpsAccuracyMetres)
        }

        Text(
            Units.duration(state.elapsedSeconds),
            color = Z.Ink, fontSize = 70.sp, fontWeight = FontWeight.Black,
            letterSpacing = (-3.5).sp, lineHeight = 74.sp,
            modifier = Modifier.popIn(state.elapsedSeconds / 60),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            LiveStat(Units.milesValue(state.distanceMetres), "MILES")
            LiveStat(Units.pace(pacePerMile(state.averagePaceSecondsPerKm)), "AVG /MI")
            LiveStat(Units.number(state.netKcal), "KCAL")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            LiveStat(Units.pace(pacePerMile(state.currentPaceSecondsPerKm)), "NOW /MI")
            LiveStat(Units.feetValue(state.elevationGainMetres), "FEET UP")
        }

        RouteCanvas(
            points = state.points,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(Z.CardRadius))
                .background(Z.Card),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ControlButton(
                label = if (paused) "Resume" else "Pause",
                background = if (paused) Z.Green else Z.Card,
                foreground = if (paused) Color.White else Z.Ink,
                modifier = Modifier.weight(1f),
                onClick = if (paused) onResume else onPause,
            )
            ControlButton(
                label = "Finish",
                background = Z.Nav,
                foreground = Color.White,
                modifier = Modifier.weight(1f),
                onClick = onFinish,
            )
        }

        // Only offered while paused, so a mis-tap mid-run cannot throw away an hour's work.
        if (paused) {
            Text(
                "Discard this one",
                color = Z.Faint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDiscard)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun GpsPill(accuracyMetres: Float?) {
    val (label, colour) = when {
        accuracyMetres == null -> "Finding GPS…" to Z.Faint
        accuracyMetres <= 10f -> "GPS strong" to Z.Green
        accuracyMetres <= 25f -> "GPS ok" to Z.Warn
        else -> "GPS weak" to Z.Warn
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .pulsing()
                .clip(CircleShape)
                .background(colour),
        )
        Text(label, color = Z.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LiveStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, color = Z.Ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.9).sp)
        Text(label, color = Z.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    }
}

@Composable
private fun ControlButton(
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The route, drawn to fit whatever space it's given.
 *
 * A real map needs a Google Maps API key on a billing account; this needs nothing and answers the
 * question people actually ask of the map mid-run — "where have I been, and am I looping back?"
 * Latitude is flipped because screen Y grows downward while latitude grows north, and longitude is
 * scaled by cos(latitude) so a route doesn't look stretched sideways away from the equator.
 */
@Composable
internal fun RouteCanvas(points: List<GeoPoint>, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (points.size < 2) {
            Text(
                "Your route appears here",
                color = Z.Faint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize().padding(20.dp)) {
            val lats = points.map { it.latitude }
            val lons = points.map { it.longitude }
            val midLat = (lats.min() + lats.max()) / 2
            val lonScale = kotlin.math.cos(Math.toRadians(midLat))

            val xs = lons.map { it * lonScale }
            val minX = xs.min()
            val maxX = xs.max()
            val minY = lats.min()
            val maxY = lats.max()

            val spanX = (maxX - minX).takeIf { it > 1e-9 } ?: 1e-9
            val spanY = (maxY - minY).takeIf { it > 1e-9 } ?: 1e-9

            // One scale for both axes keeps the shape honest instead of stretching it to the box.
            val scale = minOf(size.width / spanX, size.height / spanY)
            val offsetX = (size.width - spanX * scale) / 2
            val offsetY = (size.height - spanY * scale) / 2

            fun project(index: Int) = Offset(
                x = ((xs[index] - minX) * scale + offsetX).toFloat(),
                y = (size.height - ((lats[index] - minY) * scale + offsetY)).toFloat(),
            )

            val path = Path().apply {
                val first = project(0)
                moveTo(first.x, first.y)
                for (i in 1 until points.size) {
                    val p = project(i)
                    lineTo(p.x, p.y)
                }
            }

            drawPath(
                path = path,
                color = Z.Violet,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(Z.Green, radius = 6.dp.toPx(), center = project(0))
            drawCircle(Z.Violet, radius = 6.dp.toPx(), center = project(points.lastIndex))
        }
    }
}

@Composable
private fun HistoryRow(session: SessionEntity, onClick: () -> Unit) {
    val when_ = Instant.ofEpochMilli(session.startEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.US))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(Z.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                runCatching { ActivityType.valueOf(session.type).label }.getOrDefault(session.type),
                color = Z.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
            )
            Text(when_, color = Z.Faint, fontSize = 11.5.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                Units.miles(session.distanceMetres),
                color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                "${Units.duration(session.durationSeconds)} · ${Units.number(session.netKcal)} kcal",
                color = Z.Muted, fontSize = 11.5.sp,
            )
        }
    }
}

/** Core computes pace per kilometre; the whole app speaks miles. */
private fun pacePerMile(secondsPerKm: Double?): Double? = secondsPerKm?.times(1.609344)
