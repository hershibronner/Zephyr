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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Spa
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
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.domain.TodayState
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.EnergyRing
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.components.StatTile
import app.zephyr.fitness.ui.components.animatedCount
import app.zephyr.fitness.ui.components.entrance
import app.zephyr.fitness.ui.components.ZCard
import app.zephyr.fitness.ui.components.heroGlow
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.plan.Prescription
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.abs

/**
 * The screen that answers "how am I doing" in under two seconds.
 *
 * Priority is strict: the one number that matters is enormous and first, the next physical action is
 * second, everything else is detail below the fold. A dashboard treating twelve metrics as equally
 * important pushes the interpreting back onto the user, which is the job the app is supposed to be
 * doing for them.
 */
@Composable
fun TodayScreen(
    state: TodayState,
    selectedDate: LocalDate,
    today: LocalDate,
    loggedDates: Set<LocalDate>,
    /** Today's prescribed session, if the weekly plan has one. Kept out of TodayState because the
     *  plan is a separate concern from the daily ledger, and the ledger's combine is already full. */
    prescription: Prescription?,
    sessionDone: Boolean,
    /** True when the step permission is missing, so the counter cannot see anything at all. */
    stepsBlocked: Boolean,
    onEnableSteps: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onLogWeight: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenFood: () -> Unit,
    onDeleteFood: (FoodLogEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val balance = state.balance
    val over = balance.remainingKcal < 0
    val next = prescription

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Box(Modifier.entrance(0)) {
                DateStrip(selectedDate, today, loggedDates, onSelectDate)
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .entrance(1)
                    .clip(RoundedCornerShape(Z.CardRadius))
                    .heroGlow(over)
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (selectedDate == today) "TODAY" else selectedDate
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM", Locale.US)).uppercase(),
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
                )

                EnergyRing(
                    calorieFraction = balance.fractionConsumed,
                    stepFraction = state.stepStatus.fractionOfGoal,
                    over = over,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val counted = animatedCount(abs(balance.remainingKcal))
                        val text = Units.number(counted)
                        Text(
                            text,
                            color = Color.White,
                            // Step the size down as digits are added so a four-figure budget still
                            // sits inside the ring instead of touching both edges.
                            fontSize = if (text.length >= 6) 42.sp else if (text.length >= 5) 50.sp else 58.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-2.5).sp,
                        )
                        Text(
                            if (over) "kcal over" else "kcal left",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    HeroStat(Units.number(balance.consumedKcal), "EATEN")
                    HeroStat(Units.number(balance.exerciseKcal), "BURNED")
                    HeroStat(Units.number(balance.targetKcal), "TARGET")
                }
            }
        }

        item {
            Column(
                modifier = Modifier.entrance(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // A step tile stuck on zero looks like a broken app. When the permission is the
                    // reason, the tile says so and fixes it on tap rather than quietly reading nothing.
                    if (stepsBlocked) {
                        StatTile(
                            value = "Turn on",
                            label = "Tap to count your steps",
                            background = Z.WarnSoft, foreground = Z.WarnInk,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onEnableSteps),
                            icon = { Icon(Icons.Filled.DirectionsRun, null, tint = Z.WarnInk, modifier = Modifier.size(17.dp)) },
                        )
                    } else {
                        StatTile(
                            value = Units.number(state.stepStatus.steps),
                            label = "of ${Units.number(state.stepStatus.goal)} steps",
                            background = Z.SkySoft, foreground = Z.SkyInk,
                            modifier = Modifier.weight(1f),
                            fraction = state.stepStatus.fractionOfGoal,
                            icon = { Icon(Icons.Filled.DirectionsRun, null, tint = Z.SkyInk, modifier = Modifier.size(17.dp)) },
                        )
                    }
                    StatTile(
                        value = "${balance.macros.proteinG}g",
                        label = "of ${balance.proteinTargetG}g protein",
                        background = Z.GreenSoft, foreground = Z.GreenInk,
                        modifier = Modifier.weight(1f),
                        fraction = balance.proteinFraction,
                        icon = { Icon(Icons.Filled.Spa, null, tint = Z.GreenInk, modifier = Modifier.size(17.dp)) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        value = Units.number(balance.exerciseKcal),
                        label = if (state.sessionsToday == 1) "1 session" else "${state.sessionsToday} sessions",
                        background = Z.OrangeSoft, foreground = Z.OrangeInk,
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Filled.LocalFireDepartment, null, tint = Z.OrangeInk, modifier = Modifier.size(17.dp)) },
                    )
                    StatTile(
                        value = state.streak.current.toString(),
                        label = "day streak",
                        background = Z.PinkSoft, foreground = Z.PinkInk,
                        modifier = Modifier.weight(1f),
                        icon = { Icon(Icons.Filled.Bolt, null, tint = Z.PinkInk, modifier = Modifier.size(17.dp)) },
                    )
                }
            }
        }

        if (next != null) {
            item { SectionHeader("Your plan", "See week", onOpenPlan) }
            item { PlanCard(next, sessionDone) }
        }

        item { SectionHeader("Weight", "Log", onLogWeight) }
        item { WeightCard(state, onLogWeight) }

        if (state.entries.isNotEmpty()) {
            item {
                SectionHeader(
                    if (selectedDate == today) "Today's food" else "Food",
                    "All", onOpenFood,
                )
            }
            items(state.entries, key = { it.id }) { entry ->
                FoodRow(entry) { onDeleteFood(entry) }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.72f), fontSize = 10.5.sp, letterSpacing = 0.8.sp)
    }
}

/** Seven days ending today, so a forgotten meal can be added the next morning. */
@Composable
private fun DateStrip(
    selected: LocalDate,
    today: LocalDate,
    logged: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0..6).map { today.minusDays((6 - it).toLong()) }) { date ->
            val isOn = date == selected
            Column(
                modifier = Modifier
                    .width(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isOn) Z.Nav else Color.Transparent)
                    .clickable { onSelect(date) }
                    .padding(vertical = 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.US),
                    color = if (isOn) Color.White.copy(alpha = 0.62f) else Z.Muted,
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    date.dayOfMonth.toString(),
                    color = if (isOn) Color.White else Z.Ink,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                date !in logged -> Color.Transparent
                                isOn -> Z.Green
                                else -> Z.Violet
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun PlanCard(prescription: Prescription, done: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(if (done) Z.GreenSoft else Z.VioletSoft)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (done) Z.GreenInk else Z.VioletInk
        Text(
            text = if (done) "Done" else prescription.slot.timeOfDay.toString(),
            color = ink,
            fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.6f))
                .padding(horizontal = 11.dp, vertical = 5.dp),
        )
        Text(
            (if (done) "✓ " else "") + prescription.headline,
            color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
        )
        Text(prescription.detail, color = ink.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun WeightCard(state: TodayState, onLogWeight: () -> Unit) {
    val trend = state.trend.currentTrendKg
    ZCard(modifier = Modifier.fillMaxWidth(), onClick = onLogWeight) {
        if (trend == null) {
            Kicker("Weigh in")
            Text("Log today's weight", color = Z.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(
                "Zephyr needs a couple of weeks of weigh-ins before it can measure what you actually burn.",
                color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
        } else {
            Kicker("Trend weight")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    String.format(Locale.US, "%.1f", Units.kgToLb(trend)),
                    color = Z.Ink, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.8).sp,
                )
                Text(" lb", color = Z.Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            val rate = state.trend.weeklyRateKg
            Text(
                when {
                    rate == null -> "Keep weighing in — a few more days and the trend becomes reliable."
                    abs(rate) < 0.05 -> "Holding steady."
                    rate < 0 -> "Down ${String.format(Locale.US, "%.1f", abs(Units.kgToLb(rate)))} lb a week — right on it."
                    else -> "Up ${String.format(Locale.US, "%.1f", Units.kgToLb(rate))} lb a week."
                },
                color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
            )
            val adaptive = state.adaptiveTdee
            adaptive?.measuredTdeeKcal?.let { measured ->
                Text(
                    "Zephyr now measures your maintenance at ${Units.number(measured)} kcal, from " +
                        "${adaptive.daysOfData} days of your own data — not a formula.",
                    color = Z.GreenInk, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Z.GreenSoft)
                        .padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun FoodRow(entry: FoodLogEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(Z.Card)
            .padding(horizontal = 17.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(entry.slot.label, color = Z.Faint, fontSize = 11.5.sp)
        }
        Text(Units.number(entry.kcal), color = Z.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "×",
            color = Z.Faint, fontSize = 20.sp,
            modifier = Modifier
                .padding(start = 12.dp)
                .clickable(onClick = onDelete),
        )
    }
}
