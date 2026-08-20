package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.data.db.SessionEntity
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.activity.GeoPoint
import dev.zephyr.core.activity.Split
import dev.zephyr.core.activity.Splits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The report for a finished session.
 *
 * Built around splits, because a finish-line average hides the whole story — whether you went out
 * too fast and paid for it, or paced it properly. "Was that any good?" is the question people
 * actually have afterwards, and one average number can never answer it.
 */
@Composable
fun SessionReportScreen(
    session: SessionEntity,
    points: List<GeoPoint>,
    verdict: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val splits = rememberSplits(points)
    val fastest = Splits.fastest(splits)
    val consistency = Splits.consistency(splits)
    val negative = Splits.isNegativeSplit(splits)

    val started = Instant.ofEpochMilli(session.startEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale.US))

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Kicker(runCatching { ActivityType.valueOf(session.type).label }.getOrDefault("Session"))
                    Text(started, color = Z.Muted, fontSize = 13.sp)
                }
                Text(
                    "Close",
                    color = Z.Violet, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onClose),
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Z.CardRadius))
                    .background(Z.Card)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        Units.milesValue(session.distanceMetres),
                        color = Z.Ink, fontSize = 52.sp, fontWeight = FontWeight.Black,
                        letterSpacing = (-2.5).sp,
                    )
                    Text(
                        " mi",
                        color = Z.Muted, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    ReportStat(Units.duration(session.durationSeconds), "TIME")
                    ReportStat(
                        Units.pace(
                            Units.paceSecondsPerMile(session.distanceMetres, session.durationSeconds),
                        ),
                        "AVG /MI",
                    )
                    ReportStat(Units.number(session.netKcal), "KCAL")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    ReportStat(Units.feetValue(session.elevationGainMetres), "FEET UP")
                    fastest?.let { best ->
                        ReportStat(Units.pace(best.paceSecondsPerUnit), "BEST MI")
                    }
                }
            }
        }

        item {
            Text(
                verdict,
                color = Z.GreenInk, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Z.GreenSoft)
                    .padding(16.dp),
            )
        }

        if (points.size >= 2) {
            item {
                RouteCanvas(
                    points = points,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(Z.CardRadius))
                        .background(Z.Card),
                )
            }
        }

        if (splits.isNotEmpty()) {
            item { SectionHeader("Splits") }

            item {
                // Bars are scaled against the slowest mile so the shape of the run is visible at a
                // glance. Absolute pace is in the number beside it; the bar is for comparison.
                val slowest = splits.mapNotNull { it.paceSecondsPerUnit }.maxOrNull() ?: 1.0
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    splits.forEach { split ->
                        SplitRow(split, slowest, isFastest = split.index == fastest?.index)
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Z.Page)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (negative) {
                        Text(
                            "Negative split",
                            color = Z.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Second half quicker than the first. That's the way to run one.",
                            color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                    consistency?.let { steady ->
                        Text(
                            "Pace evenness: ${(steady * 100).toInt()}%",
                            color = Z.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                steady >= 0.9f -> "Metronomic. Hard to do and worth being pleased about."
                                steady >= 0.75f -> "Reasonably even. The middle miles are where it slips."
                                else -> "Uneven — usually means starting quicker than you can hold."
                            },
                            color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
            }
        } else if (session.distanceMetres <= 0) {
            item {
                Text(
                    "No route was recorded for this one, so there are no splits — the time and " +
                        "calories still count.",
                    color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
            }
        }
    }
}

/** Splitting a long track is real work; do it once per session rather than on every recomposition. */
@Composable
private fun rememberSplits(points: List<GeoPoint>): List<Split> =
    remember(points) { Splits.perMile(points) }

@Composable
private fun ReportStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, color = Z.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp)
        Text(label, color = Z.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    }
}

@Composable
private fun SplitRow(split: Split, slowestPace: Double, isFastest: Boolean) {
    val pace = split.paceSecondsPerUnit
    val fraction = if (pace != null && slowestPace > 0) (pace / slowestPace).toFloat() else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (split.isComplete) "${split.index}" else "·",
            color = Z.Faint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.size(width = 16.dp, height = 16.dp),
        )

        Box(
            Modifier
                .weight(1f)
                .height(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Z.Page),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.06f, 1f))
                    .height(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isFastest) Z.Green else Z.Violet.copy(alpha = 0.75f)),
            )
        }

        Text(
            Units.pace(pace),
            color = Z.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        )

        if (!split.isComplete) {
            Text(
                Units.milesValue(split.distanceMetres) + "mi",
                color = Z.Faint, fontSize = 10.5.sp,
            )
        } else if (isFastest) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Z.Green),
            )
        } else {
            Box(Modifier.size(7.dp).background(Color.Transparent))
        }
    }
}
