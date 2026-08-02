package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.zephyr.fitness.ui.theme.ZephyrColors
import dev.zephyr.core.energy.CalorieTargetResult
import dev.zephyr.core.energy.MacroTargets
import dev.zephyr.core.energy.TargetAdjustment
import dev.zephyr.core.model.ActivityLevel
import dev.zephyr.core.model.GoalPace
import dev.zephyr.core.model.Sex

/** Draft profile being assembled during onboarding, before anything is committed. */
data class OnboardingDraft(
    val step: Int = 0,
    val sex: Sex = Sex.UNSPECIFIED,
    val birthYear: String = "",
    val heightCm: String = "",
    val weightKg: String = "",
    val goalWeightKg: String = "",
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    val pace: GoalPace = GoalPace.LOSE_STEADY,
) {
    val heightValid: Boolean get() = heightCm.toDoubleOrNull()?.let { it in 100.0..250.0 } == true
    val weightValid: Boolean get() = weightKg.toDoubleOrNull()?.let { it in 30.0..300.0 } == true
    val goalValid: Boolean get() = goalWeightKg.toDoubleOrNull()?.let { it in 30.0..300.0 } == true
    val yearValid: Boolean get() = birthYear.toIntOrNull()?.let { it in 1920..2015 } == true

    val detailsComplete: Boolean get() = heightValid && weightValid && yearValid
    val goalComplete: Boolean get() = goalValid
}

@Composable
fun OnboardingScreen(
    draft: OnboardingDraft,
    preview: Pair<CalorieTargetResult, MacroTargets>?,
    onDraftChange: (OnboardingDraft) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        when (draft.step) {
            0 -> WelcomeStep()
            1 -> DetailsStep(draft, onDraftChange)
            2 -> GoalStep(draft, onDraftChange)
            else -> PlanStep(preview)
        }

        // A weighted spacer would be the natural way to pin these buttons to the bottom, but this
        // Column scrolls, so its max height is unbounded and weight() fails at measure time.
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.step > 0) {
                TextButton(onClick = { onDraftChange(draft.copy(step = draft.step - 1)) }) {
                    Text("Back", color = ZephyrColors.TextSecondary)
                }
            } else {
                Spacer(Modifier)
            }

            Button(
                onClick = {
                    if (draft.step >= 3) onFinish() else onDraftChange(draft.copy(step = draft.step + 1))
                },
                enabled = when (draft.step) {
                    1 -> draft.detailsComplete
                    2 -> draft.goalComplete
                    else -> true
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZephyrColors.Mint,
                    contentColor = ZephyrColors.Ink,
                ),
            ) {
                Text(if (draft.step >= 3) "Start" else "Continue", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Zephyr",
            style = MaterialTheme.typography.displayMedium,
            color = ZephyrColors.Mint,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "One number a day.",
            style = MaterialTheme.typography.headlineSmall,
            color = ZephyrColors.TextPrimary,
        )
        Text(
            "Everything you eat subtracts from it. Every run, hike, lift and step adds back. " +
                "That single number is the whole game, and Zephyr keeps it honest — it learns what " +
                "your body actually burns instead of trusting a formula.",
            style = MaterialTheme.typography.bodyLarge,
            color = ZephyrColors.TextSecondary,
        )
        Text(
            "Zephyr gives general fitness and nutrition guidance. It isn't medical advice — " +
                "if you have a health condition, talk to a doctor before making big changes.",
            style = MaterialTheme.typography.bodySmall,
            color = ZephyrColors.TextTertiary,
        )
    }
}

@Composable
private fun DetailsStep(draft: OnboardingDraft, onChange: (OnboardingDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle("About you", "These set your baseline. Zephyr corrects itself from real data later.")

        Text("Sex", style = MaterialTheme.typography.labelMedium, color = ZephyrColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sex.entries.forEach { sex ->
                ChoiceChip(
                    selected = draft.sex == sex,
                    label = when (sex) {
                        Sex.MALE -> "Male"
                        Sex.FEMALE -> "Female"
                        Sex.UNSPECIFIED -> "Rather not say"
                    },
                    onClick = { onChange(draft.copy(sex = sex)) },
                )
            }
        }

        NumberField(draft.birthYear, "Birth year", "e.g. 1990") { onChange(draft.copy(birthYear = it)) }
        NumberField(draft.heightCm, "Height (cm)", "e.g. 178") { onChange(draft.copy(heightCm = it)) }
        NumberField(draft.weightKg, "Weight (kg)", "e.g. 84.5") { onChange(draft.copy(weightKg = it)) }
    }
}

@Composable
private fun GoalStep(draft: OnboardingDraft, onChange: (OnboardingDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle("Your goal", "Pick a pace you can live with. Zephyr won't let you set an unsafe one.")

        NumberField(draft.goalWeightKg, "Goal weight (kg)", "e.g. 76") {
            onChange(draft.copy(goalWeightKg = it))
        }

        Text("Pace", style = MaterialTheme.typography.labelMedium, color = ZephyrColors.TextSecondary)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalPace.entries.forEach { pace ->
                ChoiceChip(
                    selected = draft.pace == pace,
                    label = pace.label,
                    onClick = { onChange(draft.copy(pace = pace)) },
                    fullWidth = true,
                )
            }
        }

        Text(
            "Day-to-day activity",
            style = MaterialTheme.typography.labelMedium,
            color = ZephyrColors.TextSecondary,
        )
        Text(
            "Not counting workouts — Zephyr adds those from what you actually log.",
            style = MaterialTheme.typography.bodySmall,
            color = ZephyrColors.TextTertiary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityLevel.entries.forEach { level ->
                ChoiceChip(
                    selected = draft.activity == level,
                    label = level.label,
                    onClick = { onChange(draft.copy(activity = level)) },
                    fullWidth = true,
                )
            }
        }
    }
}

/**
 * Shows the arithmetic instead of just the answer.
 *
 * A target someone understands is a target they keep hitting; a number that appeared from nowhere
 * gets abandoned the first week it feels inconvenient. This screen is also where a safety clamp
 * gets explained honestly, rather than silently overriding what the user asked for.
 */
@Composable
private fun PlanStep(preview: Pair<CalorieTargetResult, MacroTargets>?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepTitle("Your plan", "Here's how Zephyr got to your daily number.")

        if (preview == null) {
            Text("Fill in the previous steps to see your plan.", color = ZephyrColors.TextSecondary)
            return@Column
        }

        val (target, macros) = preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = ZephyrColors.Surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlanRow("Maintenance", "${target.maintenanceKcal} kcal", "what you burn on an average day")
                PlanRow(
                    "Daily target",
                    "${target.targetKcal} kcal",
                    if (target.dailyDeltaKcal < 0) {
                        "${-target.dailyDeltaKcal} kcal below maintenance"
                    } else if (target.dailyDeltaKcal > 0) {
                        "${target.dailyDeltaKcal} kcal above maintenance"
                    } else {
                        "eating at maintenance"
                    },
                )
                PlanRow("Protein", "${macros.proteinG} g", "protects muscle while you lose fat")
                PlanRow("Carbs / fat", "${macros.carbsG} g / ${macros.fatG} g", "fuel for your training")
            }
        }

        if (target.adjustment != TargetAdjustment.NONE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ZephyrColors.SurfaceElevated),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = when (target.adjustment) {
                        TargetAdjustment.CAPPED_TO_PERCENTAGE ->
                            "That pace needed a steeper deficit than is safe, so Zephyr eased it to " +
                                "%.2f kg per week. Faster than this costs muscle, which is the opposite of the goal."
                                    .format(kotlin.math.abs(target.effectiveKgPerWeek))
                        TargetAdjustment.RAISED_TO_FLOOR, TargetAdjustment.RAISED_ABOVE_BMR ->
                            "Zephyr raised your target to keep it above what your body needs at rest. " +
                                "Eating below that slows your metabolism and costs muscle."
                        TargetAdjustment.NONE -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ZephyrColors.Amber,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PlanRow(label: String, value: String, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = ZephyrColors.TextPrimary)
            Text(note, style = MaterialTheme.typography.bodySmall, color = ZephyrColors.TextTertiary)
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = ZephyrColors.Mint,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = ZephyrColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ZephyrColors.TextSecondary)
    }
}

@Composable
private fun ChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    fullWidth: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = ZephyrColors.Mint,
            selectedLabelColor = ZephyrColors.Ink,
            labelColor = ZephyrColors.TextSecondary,
        ),
    )
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
