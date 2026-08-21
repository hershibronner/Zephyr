package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.ProgressBar
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.components.animatedCount
import app.zephyr.fitness.ui.components.entrance
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.plan.AdherenceResult
import dev.zephyr.core.plan.Prescription
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * The week ahead, and how the last one actually went.
 *
 * The hybrid the plan was designed around: the user owns the shape of the week, and the app decides
 * how hard each session is. So this screen shows the prescription rather than asking for input —
 * what to do today, what is coming, and honestly what was missed.
 */
@Composable
fun PlanScreen(
    weekStart: LocalDate,
    today: LocalDate,
    prescriptions: List<Prescription>,
    completedDates: Set<LocalDate>,
    adherence: AdherenceResult?,
    isDeloadWeek: Boolean,
    onEditSkeleton: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.entrance(0),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Kicker(
                    "Week of " + weekStart.format(DateTimeFormatter.ofPattern("d MMM", Locale.US)),
                )
                Text(
                    if (isDeloadWeek) "Deload week" else "This week",
                    color = Z.Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.1).sp,
                )
                if (isDeloadWeek) {
                    Text(
                        "Load is cut on purpose. Recovery is when the adaptation actually happens — " +
                            "skipping the easy week is how people end up injured.",
                        color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
            }
        }

        if (adherence != null && adherence.planned > 0) {
            item { Box(Modifier.entrance(1)) { AdherenceCard(adherence) } }
        }

        if (prescriptions.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .entrance(2)
                        .clip(RoundedCornerShape(Z.CardRadius))
                        .background(Z.Card)
                        .clickable(onClick = onEditSkeleton)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("No plan yet", color = Z.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Pick which days you'll train and Zephyr handles how hard each session is, " +
                            "week after week.",
                        color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }
        } else {
            item { SectionHeader("Sessions", "Edit days", onEditSkeleton) }

            itemsIndexed(
                items = prescriptions,
                key = { index, item -> "plan-${item.date}-${item.slot.id}-$index" },
            ) { index, prescription ->
                DayRow(
                    prescription = prescription,
                    today = today,
                    done = prescription.date in completedDates,
                    index = index,
                )
            }
        }
    }
}

@Composable
private fun AdherenceCard(adherence: AdherenceResult) {
    val percent = animatedCount(adherence.percent)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(Z.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kicker("Stuck to it")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$percent%",
                color = Z.Ink, fontSize = 42.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp,
            )
            Text(
                "  ${adherence.completed} of ${adherence.planned} sessions",
                color = Z.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        ProgressBar(
            fraction = adherence.percent / 100f,
            colour = if (adherence.percent >= 70) Z.Green else Z.Violet,
        )
        Text(
            // Adherence, not perfection. A week at 70% is a week that worked.
            when {
                adherence.percent >= 90 -> "Near enough everything. This is what progress is made of."
                adherence.percent >= 70 -> "Solid week. Consistency beats any single hard session."
                adherence.completed > 0 -> "Some is infinitely better than none. Next week starts fresh."
                else -> "Nothing yet this week. One session changes that."
            },
            color = Z.Muted, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun DayRow(prescription: Prescription, today: LocalDate, done: Boolean, index: Int) {
    val isToday = prescription.date == today
    val isPast = prescription.date.isBefore(today)
    val missed = isPast && !done

    val background = when {
        done -> Z.GreenSoft
        isToday -> Z.VioletSoft
        missed -> Z.Page
        else -> Z.Card
    }
    val ink = when {
        done -> Z.GreenInk
        isToday -> Z.VioletInk
        missed -> Z.Faint
        else -> Z.Ink
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .entrance(index + 2)
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(background)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (done) Z.Green else Color.White.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    iconFor(prescription.slot.type),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prescription.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.US)
                        .uppercase(),
                    color = ink.copy(alpha = 0.65f), fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                )
                if (isToday) {
                    Text(
                        "TODAY",
                        color = Z.Violet, fontSize = 9.5.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                }
                if (prescription.isDeload) {
                    Text(
                        "EASY",
                        color = Z.SkyInk, fontSize = 9.5.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                    )
                }
            }
            Text(
                prescription.headline,
                color = ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                prescription.detail,
                color = ink.copy(alpha = 0.72f), fontSize = 12.5.sp, lineHeight = 17.sp,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                prescription.slot.timeOfDay.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
                color = ink.copy(alpha = 0.7f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            )
            prescription.targetDistanceMetres?.let { metres ->
                Text(
                    Units.miles(metres),
                    color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun iconFor(type: ActivityType) = when (type) {
    ActivityType.RUN -> Icons.Filled.DirectionsRun
    ActivityType.HIKE -> Icons.Filled.Terrain
    ActivityType.STRENGTH -> Icons.Filled.FitnessCenter
    else -> Icons.Filled.DirectionsWalk
}
