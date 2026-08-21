package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.components.animatedCount
import app.zephyr.fitness.ui.components.entrance
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.energy.AdaptiveTdeeResult
import dev.zephyr.core.ledger.WeeklySummary
import dev.zephyr.core.streak.StreakResult
import dev.zephyr.core.trend.TrendPoint
import dev.zephyr.core.trend.WeightTrendResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The screen that answers the only question that really matters: is this working?
 *
 * Everything here exists to separate signal from noise. Daily scale readings are mostly water, so
 * the trend line is the headline and the raw dots are shown behind it as evidence rather than as
 * the story.
 */
@Composable
fun ProgressScreen(
    trend: WeightTrendResult,
    goalWeightKg: Double?,
    projectedGoalDate: LocalDate?,
    week: WeeklySummary,
    adaptive: AdaptiveTdeeResult?,
    streak: StreakResult,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Box(Modifier.entrance(0)) { TrendCard(trend, goalWeightKg, projectedGoalDate) } }

        if (trend.points.size >= 2) {
            item {
                Box(
                    Modifier
                        .entrance(1)
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(Z.CardRadius))
                        .background(Z.Card)
                        .padding(16.dp),
                ) {
                    WeightChart(trend.points, goalWeightKg)
                }
            }
        }

        item { Box(Modifier.entrance(2)) { WeekCard(week) } }

        adaptive?.measuredTdeeKcal?.let { measured ->
            item { Box(Modifier.entrance(3)) { AdaptiveCard(measured, adaptive.daysOfData, adaptive.formulaTdeeKcal) } }
        }

        item { Box(Modifier.entrance(4)) { StreakCard(streak) } }
    }
}

@Composable
private fun TrendCard(trend: WeightTrendResult, goalKg: Double?, goalDate: LocalDate?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(Z.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Kicker("Trend weight")

        val current = trend.currentTrendKg
        if (current == null) {
            Text("No weigh-ins yet", color = Z.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Two a week is all it takes. Without them Zephyr is guessing at your maintenance " +
                    "instead of measuring it.",
                color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.US, "%.1f", Units.kgToLb(current)),
                color = Z.Ink, fontSize = 46.sp, fontWeight = FontWeight.Black, letterSpacing = (-2.2).sp,
            )
            Text(
                " lb",
                color = Z.Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }

        val rate = trend.weeklyRateKg
        Text(
            when {
                rate == null -> "A few more weigh-ins and the rate becomes readable."
                abs(rate) < 0.05 -> "Holding steady."
                else -> "${Units.rateLb(rate)} a week"
            },
            color = if (rate != null && rate < -0.05) Z.GreenInk else Z.Muted,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )

        trend.totalChangeKg?.let { total ->
            if (abs(total) >= 0.2) {
                Text(
                    "${Units.rateLb(total)} since you started.",
                    color = Z.Muted, fontSize = 13.sp,
                )
            }
        }

        if (goalKg != null && goalDate != null) {
            Text(
                "On this pace you hit ${String.format(Locale.US, "%.0f", Units.kgToLb(goalKg))} lb " +
                    "around ${goalDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))}.",
                color = Z.VioletInk, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Z.VioletSoft)
                    .padding(14.dp),
            )
        }
    }
}

/**
 * Raw weigh-ins as dots, the smoothed trend as a line.
 *
 * Both are drawn because they say different things: the dots show the noise is real and expected,
 * the line shows it does not matter. Showing only the trend would look like the app was hiding the
 * days the scale went up.
 */
@Composable
private fun WeightChart(points: List<TrendPoint>, goalKg: Double?) {
    Canvas(Modifier.fillMaxSize()) {
        val values = points.map { it.trendKg } + points.mapNotNull { it.rawKg } + listOfNotNull(goalKg)
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0.2 } ?: 1.0

        // A little headroom so the line never touches the edges of the card.
        val padded = span * 1.15
        val centre = (max + min) / 2
        val top = centre + padded / 2
        val bottom = centre - padded / 2

        fun y(value: Double) = (size.height * (top - value) / (top - bottom)).toFloat()
        fun x(index: Int) =
            if (points.size <= 1) 0f else size.width * index / (points.size - 1).toFloat()

        goalKg?.let { goal ->
            val goalY = y(goal)
            var startX = 0f
            while (startX < size.width) {
                drawLine(
                    color = Z.Line,
                    start = Offset(startX, goalY),
                    end = Offset((startX + 8f).coerceAtMost(size.width), goalY),
                    strokeWidth = 2f,
                )
                startX += 16f
            }
        }

        val path = Path().apply {
            points.forEachIndexed { index, point ->
                val px = x(index)
                val py = y(point.trendKg)
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
        }
        drawPath(
            path = path,
            color = Z.Violet,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        points.forEachIndexed { index, point ->
            point.rawKg?.let { raw ->
                drawCircle(
                    color = Z.Violet.copy(alpha = 0.28f),
                    radius = 3.dp.toPx(),
                    center = Offset(x(index), y(raw)),
                )
            }
        }
    }
}

@Composable
private fun WeekCard(week: WeeklySummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(Z.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kicker("Last seven days")

        if (week.isEmpty) {
            Text("Nothing logged yet", color = Z.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Log a few days and this fills in with what you actually ate versus what you aimed for.",
                color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            MiniStat(Units.number(week.averageIntakeKcal), "AVG EATEN")
            MiniStat(Units.number(week.averageTargetKcal), "AVG TARGET")
            MiniStat("${week.daysLogged}", "DAYS LOGGED")
        }

        Text(
            // The projection is the honest read: what this week's behaviour, repeated, produces.
            when {
                abs(week.projectedWeeklyKg) < 0.05 -> "At this rate your weight holds where it is."
                week.projectedWeeklyKg < 0 ->
                    "Keep this up and it's ${Units.rateLb(week.projectedWeeklyKg)} a week."
                else ->
                    "This week's eating points upward: ${Units.rateLb(week.projectedWeeklyKg)} a week."
            },
            color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun AdaptiveCard(measured: Int, days: Int, formula: Int) {
    val counted = animatedCount(measured)
    val difference = measured - formula

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(Z.GreenSoft)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Kicker("Measured maintenance", colour = Z.GreenInk.copy(alpha = 0.55f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Units.number(counted),
                color = Z.GreenInk, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.6).sp,
            )
            Text(
                " kcal a day",
                color = Z.GreenInk.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Text(
            "From $days days of your own data, not a formula. " +
                when {
                    abs(difference) < 50 -> "The textbook estimate had you about right."
                    difference > 0 -> "You burn about ${Units.number(difference)} more than the " +
                        "equation predicted — that's why a generic calculator would have stalled you."
                    else -> "You burn about ${Units.number(abs(difference))} less than the equation " +
                        "predicted, which is exactly the kind of thing that makes plateaus look mysterious."
                },
            color = Z.GreenInk.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun StreakCard(streak: StreakResult) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Streak")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Z.CardRadius))
                .background(Z.Card)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            MiniStat("${animatedCount(streak.current)}", "CURRENT")
            MiniStat("${streak.longest}", "BEST EVER")
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, color = Z.Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
        Text(label, color = Z.Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
    }
}
