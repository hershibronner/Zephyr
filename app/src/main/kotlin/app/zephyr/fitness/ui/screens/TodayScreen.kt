package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.zephyr.fitness.domain.TodayState
import app.zephyr.fitness.ui.components.InnerRing
import app.zephyr.fitness.ui.components.MetricBar
import app.zephyr.fitness.ui.components.ProgressRing
import app.zephyr.fitness.ui.theme.ZephyrColors
import dev.zephyr.core.ledger.BalanceState
import kotlin.math.abs

/**
 * The screen that answers "how am I doing" in under two seconds.
 *
 * Layout priority is strict and deliberate: the one number that matters is enormous and first, the
 * next physical action is second, and everything else is detail below the fold. A dashboard that
 * treats twelve metrics as equally important forces the user to do the interpreting, which is the
 * job the app is supposed to be doing for them.
 */
@Composable
fun TodayScreen(
    state: TodayState,
    onLogWeight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Greeting(state) }
        item { CalorieHero(state) }
        item { MacroSection(state) }
        item { StatChips(state) }
        // Weight is what feeds the trend line and the adaptive calibration, so there is always a
        // way in: the card when there's history, an invitation when there isn't.
        item {
            if (state.trendWeightKg != null) TrendCard(state, onLogWeight) else WeighInPrompt(onLogWeight)
        }
        if (state.entries.isNotEmpty()) {
            item {
                Text(
                    text = "TODAY'S FOOD",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZephyrColors.TextTertiary,
                )
            }
            items(state.entries, key = { it.id }) { entry ->
                FoodRow(name = entry.name, kcal = entry.kcal, slot = entry.slot.label)
            }
        }
    }
}

@Composable
private fun Greeting(state: TodayState) {
    Column {
        Text(
            text = headline(state),
            style = MaterialTheme.typography.headlineSmall,
            color = ZephyrColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subhead(state),
            style = MaterialTheme.typography.bodyMedium,
            color = ZephyrColors.TextSecondary,
        )
    }
}

/**
 * Copy is chosen from state rather than being static.
 *
 * Nothing here says "you failed" or "you're over" in a scolding register. Going past the budget
 * gets a neutral fact and a way forward, because the user who has just eaten a big dinner is
 * precisely the user most likely to delete the app if it makes them feel judged.
 */
private fun headline(state: TodayState): String = when {
    !state.ready -> "Welcome to Zephyr"
    state.streak.current >= 3 -> "${state.streak.current} day streak"
    state.balance.consumedKcal == 0 -> "Fresh start"
    else -> "Today"
}

private fun subhead(state: TodayState): String {
    if (!state.ready) return "Let's set up your plan."
    val balance = state.balance
    return when (balance.state) {
        BalanceState.OVER -> "${abs(balance.remainingKcal)} over — a walk trims it back."
        BalanceState.NEARLY_THERE -> "Right on target."
        BalanceState.UNDER_FUELLED -> "Plenty left to eat. Don't run on empty."
        BalanceState.ON_TRACK ->
            if (balance.consumedKcal == 0) "Log your first meal when you're ready." else "On track."
    }
}

@Composable
private fun CalorieHero(state: TodayState) {
    val balance = state.balance
    val progress = if (balance.adjustedTargetKcal > 0) balance.fractionConsumed else 0f

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProgressRing(progress = progress, size = 240.dp) {
            InnerRing(progress = state.stepStatus.fractionOfGoal, size = 192.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = abs(balance.remainingKcal).toString(),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (balance.remainingKcal < 0) ZephyrColors.Amber else ZephyrColors.TextPrimary,
                )
                Text(
                    text = if (balance.remainingKcal < 0) "kcal over" else "kcal left",
                    style = MaterialTheme.typography.labelLarge,
                    color = ZephyrColors.TextSecondary,
                )
                Text(
                    text = "${balance.consumedKcal} eaten · ${balance.exerciseKcal} burned",
                    style = MaterialTheme.typography.labelSmall,
                    color = ZephyrColors.TextTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun MacroSection(state: TodayState) {
    val balance = state.balance
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZephyrColors.Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MetricBar(
                label = "PROTEIN",
                value = "${balance.macros.proteinG} / ${balance.proteinTargetG} g",
                progress = balance.proteinFraction,
                color = ZephyrColors.Mint,
            )
            MetricBar(
                label = "STEPS",
                value = "${state.stepStatus.steps} / ${state.stepStatus.goal}",
                progress = state.stepStatus.fractionOfGoal,
                color = ZephyrColors.Violet,
            )
            if (balance.proteinRemainingG > 0 && balance.consumedKcal > 0) {
                Text(
                    // Protein is the lever that decides whether a deficit costs fat or muscle, so
                    // it earns an explicit callout rather than sitting silently as a bar.
                    text = "${balance.proteinRemainingG}g of protein left — that's what keeps the weight you lose from being muscle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZephyrColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun StatChips(state: TodayState) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatChip(
            icon = { Icon(Icons.Filled.DirectionsRun, null, tint = ZephyrColors.Sky, modifier = Modifier.size(18.dp)) },
            value = state.sessionsToday.toString(),
            label = if (state.sessionsToday == 1) "session" else "sessions",
            modifier = Modifier.weight(1f),
        )
        StatChip(
            icon = { Icon(Icons.Filled.LocalFireDepartment, null, tint = ZephyrColors.Ember, modifier = Modifier.size(18.dp)) },
            value = state.balance.exerciseKcal.toString(),
            label = "kcal burned",
            modifier = Modifier.weight(1f),
        )
        StatChip(
            icon = { Icon(Icons.Filled.Whatshot, null, tint = ZephyrColors.Mint, modifier = Modifier.size(18.dp)) },
            value = state.streak.current.toString(),
            label = "day streak",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatChip(
    icon: @Composable () -> Unit,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = ZephyrColors.Surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            icon()
            Text(value, style = MaterialTheme.typography.titleLarge, color = ZephyrColors.TextPrimary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = ZephyrColors.TextTertiary)
        }
    }
}

@Composable
private fun WeighInPrompt(onLogWeight: () -> Unit) {
    Card(
        onClick = onLogWeight,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZephyrColors.Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("WEIGH IN", style = MaterialTheme.typography.labelSmall, color = ZephyrColors.TextTertiary)
            Text(
                "Log today's weight",
                style = MaterialTheme.typography.titleMedium,
                color = ZephyrColors.TextPrimary,
            )
            Text(
                "Zephyr needs a couple of weeks of weigh-ins before it can measure what you actually burn.",
                style = MaterialTheme.typography.bodySmall,
                color = ZephyrColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun TrendCard(state: TodayState, onLogWeight: () -> Unit) {
    val trend = state.trendWeightKg ?: return
    val rate = state.weeklyRateKg

    Card(
        onClick = onLogWeight,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ZephyrColors.Surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TREND WEIGHT", style = MaterialTheme.typography.labelSmall, color = ZephyrColors.TextTertiary)
            Text(
                text = "%.1f kg".format(trend),
                style = MaterialTheme.typography.headlineMedium,
                color = ZephyrColors.TextPrimary,
            )
            Text(
                text = when {
                    rate == null -> "Keep weighing in — a few more days and the trend becomes reliable."
                    abs(rate) < 0.05 -> "Holding steady."
                    rate < 0 -> "Down %.2f kg per week.".format(abs(rate))
                    else -> "Up %.2f kg per week.".format(rate)
                },
                style = MaterialTheme.typography.bodySmall,
                color = ZephyrColors.TextSecondary,
            )
            state.adaptiveTdee?.measuredTdeeKcal?.let { measured ->
                Text(
                    text = "Zephyr now measures your maintenance at $measured kcal, from ${state.adaptiveTdee.daysOfData} days of your own data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ZephyrColors.Mint,
                )
            }
        }
    }
}

@Composable
private fun FoodRow(name: String, kcal: Int, slot: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ZephyrColors.Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = ZephyrColors.TextPrimary)
            Text(slot, style = MaterialTheme.typography.labelSmall, color = ZephyrColors.TextTertiary)
        }
        Text("$kcal", style = MaterialTheme.typography.titleMedium, color = ZephyrColors.TextSecondary)
    }
}
