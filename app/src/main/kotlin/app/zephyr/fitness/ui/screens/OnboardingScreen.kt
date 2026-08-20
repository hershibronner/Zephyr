package app.zephyr.fitness.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.StatTile
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.energy.CalorieTargetResult
import dev.zephyr.core.energy.MacroTargets
import dev.zephyr.core.energy.TargetAdjustment
import dev.zephyr.core.model.ActivityLevel
import dev.zephyr.core.model.GoalPace
import dev.zephyr.core.model.Sex
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The onboarding questions, one per screen.
 *
 * A single long form is faster to build and worse to use: it shows everything being asked of you at
 * once, which reads as a chore. Asking one thing at a time makes each answer feel like progress and
 * gives every step room for a real question and a real reason. The last screen is the payoff — the
 * number, revealed — and everything before it exists to earn that.
 */
enum class ObStep { INTRO, SEX, AGE, HEIGHT, WEIGHT, GOAL, PACE, ACTIVITY, REVEAL }

/** Imperial because that's how it's asked; converted to metric only when the profile is committed. */
data class ObDraft(
    val step: ObStep = ObStep.INTRO,
    val sex: Sex? = null,
    val age: String = "",
    val feet: String = "",
    val inches: String = "",
    val pounds: String = "",
    val goalPounds: String = "",
    val pace: GoalPace? = null,
    val activity: ActivityLevel? = null,
) {
    val ready: Boolean
        get() = when (step) {
            ObStep.SEX -> sex != null
            ObStep.AGE -> age.toIntOrNull()?.let { it in 13..100 } == true
            ObStep.HEIGHT -> feet.toIntOrNull()?.let { it in 3..8 } == true &&
                inches.toIntOrNull()?.let { it in 0..11 } == true
            ObStep.WEIGHT -> pounds.toDoubleOrNull()?.let { it in 70.0..660.0 } == true
            ObStep.GOAL -> goalPounds.toDoubleOrNull()?.let { it in 70.0..660.0 } == true
            ObStep.PACE -> pace != null
            ObStep.ACTIVITY -> activity != null
            else -> true
        }

    val heightCm: Double?
        get() {
            val f = feet.toIntOrNull() ?: return null
            val i = inches.toIntOrNull() ?: return null
            return Units.ftInToCm(f, i)
        }
    val weightKg: Double? get() = pounds.toDoubleOrNull()?.let(Units::lbToKg)
    val goalWeightKg: Double? get() = goalPounds.toDoubleOrNull()?.let(Units::lbToKg)
}

@Composable
fun OnboardingScreen(
    draft: ObDraft,
    preview: Pair<CalorieTargetResult, MacroTargets>?,
    onChange: (ObDraft) -> Unit,
    onFinish: () -> Unit,
) {
    val steps = ObStep.entries
    val index = steps.indexOf(draft.step)
    val progress by animateFloatAsState(
        targetValue = index.toFloat() / (steps.size - 1),
        animationSpec = tween(500, easing = LinearOutSlowInEasing),
        label = "obProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Z.Page)
            .systemBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 18.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Z.Line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Z.Violet),
            )
        }

        // Screens arrive rather than appear, so answering feels like moving forward. Forward and
        // back animate in opposite directions, which keeps the sense of place.
        AnimatedContent(
            targetState = draft.step,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val forward = ObStep.entries.indexOf(targetState) >= ObStep.entries.indexOf(initialState)
                val offset = if (forward) 1 else -1
                (slideInVertically { it / 8 * offset } + fadeIn(tween(280))) togetherWith
                    (slideOutVertically { -it / 10 * offset } + fadeOut(tween(180)))
            },
            label = "obStep",
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (step) {
                    ObStep.INTRO -> IntroStep()
                    ObStep.SEX -> ChoiceStep(
                        title = "First — who am I working with?",
                        subtitle = "It changes the maths, nothing else.",
                        options = listOf(
                            Triple(Sex.MALE, "Man", ""),
                            Triple(Sex.FEMALE, "Woman", ""),
                            Triple(Sex.UNSPECIFIED, "Rather not say", "Zephyr splits the difference"),
                        ),
                        selected = draft.sex,
                        onSelect = { onChange(draft.copy(sex = it, step = ObStep.AGE)) },
                    )
                    ObStep.AGE -> NumberStep(
                        title = "How old are you?",
                        subtitle = "Your body burns differently at 22 than at 42.",
                        fields = listOf(FieldSpec("Years", draft.age, "28") { onChange(draft.copy(age = it)) }),
                    )
                    ObStep.HEIGHT -> NumberStep(
                        title = "How tall are you?",
                        subtitle = "The single biggest input into what you burn all day.",
                        fields = listOf(
                            FieldSpec("Feet", draft.feet, "5") { onChange(draft.copy(feet = it)) },
                            FieldSpec("Inches", draft.inches, "11") { onChange(draft.copy(inches = it)) },
                        ),
                    )
                    ObStep.WEIGHT -> NumberStep(
                        title = "What do you weigh right now?",
                        subtitle = "No judgement — it's just the starting line.",
                        fields = listOf(FieldSpec("Pounds", draft.pounds, "187") { onChange(draft.copy(pounds = it)) }),
                    )
                    ObStep.GOAL -> GoalStep(draft, onChange)
                    ObStep.PACE -> ChoiceStep(
                        title = "How fast do you want this?",
                        subtitle = "Faster isn't better. Faster is just faster.",
                        options = GoalPace.entries
                            .filter { it != GoalPace.GAIN_SLOW }
                            .map { Triple(it, it.label, paceBlurb(it)) },
                        selected = draft.pace,
                        onSelect = { onChange(draft.copy(pace = it, step = ObStep.ACTIVITY)) },
                    )
                    ObStep.ACTIVITY -> ChoiceStep(
                        title = "Before any workouts — how much do you move?",
                        subtitle = "Just your normal day. Zephyr adds training on top from what you log.",
                        options = ActivityLevel.entries.map { Triple(it, it.label, "") },
                        selected = draft.activity,
                        onSelect = { onChange(draft.copy(activity = it, step = ObStep.REVEAL)) },
                    )
                    ObStep.REVEAL -> RevealStep(draft, preview)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Z.Card)
                        .clickable { onChange(draft.copy(step = steps[index - 1])) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Z.Ink) }
            }

            Button(
                onClick = {
                    if (draft.step == ObStep.REVEAL) onFinish()
                    else onChange(draft.copy(step = steps[index + 1]))
                },
                enabled = draft.ready,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Z.Violet, contentColor = Color.White),
            ) {
                Text(
                    when (draft.step) {
                        ObStep.INTRO -> "Get started"
                        ObStep.REVEAL -> "Let's go"
                        else -> "Continue"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun paceBlurb(pace: GoalPace) = when (pace) {
    GoalPace.MAINTAIN -> "Hold this weight, get fitter"
    GoalPace.LOSE_EASY -> "Barely notice it"
    GoalPace.LOSE_STEADY -> "The sweet spot"
    GoalPace.LOSE_FAST -> "You will feel this one"
    GoalPace.LOSE_AGGRESSIVE -> "Only if you have weight to spare"
    GoalPace.GAIN_SLOW -> "Build, slowly and cleanly"
}

@Composable
private fun IntroStep() {
    Text("Zephyr", color = Z.Violet, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = (-2.5).sp)
    Text(
        "Look the way\nyou want to look.",
        color = Z.Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.2).sp, lineHeight = 34.sp,
    )
    Text(
        "Eating, running, hiking, lifting — one number a day that tells you whether today counted. " +
            "No spreadsheets. No guilt. Just the number.",
        color = Z.Muted, fontSize = 15.5.sp, lineHeight = 23.sp,
    )
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Promise(Z.Violet, "It learns what your body burns, not a formula's guess")
        Promise(Z.Orange, "Protein set so what you lose is fat, not muscle")
        Promise(Z.Pink, "Chases you when it matters, shuts up when it doesn't")
    }
    Text(
        "General fitness guidance, not medical advice. Talk to a doctor before big changes if you " +
            "have a health condition.",
        color = Z.Faint, fontSize = 12.sp, lineHeight = 17.sp,
    )
}

@Composable
private fun Promise(dot: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(text, color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    }
}

@Composable
private fun <T> ChoiceStep(
    title: String,
    subtitle: String,
    options: List<Triple<T, String, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
) {
    StepHeading(title, subtitle)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (value, label, blurb) ->
            val isOn = value == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isOn) Z.VioletSoft else Z.Card)
                    .clickable { onSelect(value) }
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, color = Z.Ink, fontSize = 16.5.sp, fontWeight = FontWeight.Bold)
                    if (blurb.isNotEmpty()) {
                        Text(blurb, color = Z.Muted, fontSize = 13.sp)
                    }
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(if (isOn) Z.Violet else Z.Line),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isOn) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

private data class FieldSpec(
    val label: String,
    val value: String,
    val placeholder: String,
    val onChange: (String) -> Unit,
)

@Composable
private fun NumberStep(title: String, subtitle: String, fields: List<FieldSpec>) {
    StepHeading(title, subtitle)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.forEach { field ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Kicker(field.label)
                BigNumberField(field.value, field.placeholder, field.onChange)
            }
        }
    }
}

/** The question is the whole screen, so the answer gets to be large too. */
@Composable
private fun BigNumberField(value: String, placeholder: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter(Char::isDigit).take(3)) },
        placeholder = {
            Text(
                placeholder,
                color = Z.Line,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        textStyle = TextStyle(
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            textAlign = TextAlign.Center,
            color = Z.Ink,
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(20.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Z.Card,
            unfocusedContainerColor = Z.Card,
            focusedBorderColor = Z.Violet,
            unfocusedBorderColor = Z.Line,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GoalStep(draft: ObDraft, onChange: (ObDraft) -> Unit) {
    StepHeading(
        "Where do you want to be?",
        "Pick the number you actually want. Zephyr will tell you honestly if it's too fast.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Kicker("Goal weight")
        BigNumberField(
            value = draft.goalPounds,
            placeholder = draft.pounds.toDoubleOrNull()?.let { (it - 15).roundToInt().toString() } ?: "170",
            onChange = { onChange(draft.copy(goalPounds = it)) },
        )
    }

    val now = draft.pounds.toDoubleOrNull()
    val goal = draft.goalPounds.toDoubleOrNull()
    if (now != null && goal != null && abs(now - goal) >= 1) {
        val losing = now > goal
        Callout(
            text = if (losing) {
                "${(now - goal).roundToInt()} lb to go. That's the whole job — and it's a smaller " +
                    "number than it feels like right now."
            } else {
                "${(goal - now).roundToInt()} lb to put on. Zephyr will make sure it's the good kind."
            },
            background = Z.GreenSoft,
            foreground = Z.GreenInk,
        )
    }
}

/**
 * The payoff. Everything before this was earning the right to show a number, so it counts up rather
 * than simply appearing.
 */
@Composable
private fun RevealStep(draft: ObDraft, preview: Pair<CalorieTargetResult, MacroTargets>?) {
    if (preview == null) {
        Text("Fill in the earlier steps and your plan appears here.", color = Z.Muted, fontSize = 16.sp)
        return
    }
    val (target, macros) = preview

    val counted by animateFloatAsState(
        targetValue = target.targetKcal.toFloat(),
        animationSpec = spring(dampingRatio = 1f, stiffness = 26f),
        label = "revealCount",
    )

    Kicker("Your daily number")
    Text(
        Units.number(counted.roundToInt()),
        color = Z.Violet, fontSize = 76.sp, fontWeight = FontWeight.Black,
        letterSpacing = (-4).sp, lineHeight = 78.sp,
    )
    Text("calories a day", color = Z.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            StatTile("${macros.proteinG}g", "Protein — the muscle-saver", Z.GreenSoft, Z.GreenInk, Modifier.weight(1f))
            StatTile("${macros.carbsG}g", "Carbs — your fuel", Z.OrangeSoft, Z.OrangeInk, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            StatTile("${macros.fatG}g", "Fat — keeps you sane", Z.SkySoft, Z.SkyInk, Modifier.weight(1f))
            StatTile(
                Units.number(target.maintenanceKcal), "What you burn doing nothing",
                Z.VioletSoft, Z.VioletInk, Modifier.weight(1f),
            )
        }
    }

    if (target.adjustment != TargetAdjustment.NONE) {
        Callout(clampCopy(target), Z.WarnSoft, Z.WarnInk)
    } else {
        val lbPerWeek = abs(Units.kgToLb(target.effectiveKgPerWeek))
        val toGo = draft.pounds.toDoubleOrNull()?.let { now ->
            draft.goalPounds.toDoubleOrNull()?.let { goal -> abs(now - goal) }
        }
        if (toGo != null && lbPerWeek > 0.01) {
            val weeks = (toGo / lbPerWeek).roundToInt()
            if (weeks in 1..200) {
                Callout(
                    "Hit this most days and you're there in about $weeks weeks — losing " +
                        String.format(java.util.Locale.US, "%.1f", lbPerWeek) +
                        " lb a week. You'll notice it in the mirror before the scale catches up.",
                    Z.GreenSoft, Z.GreenInk,
                )
            }
        }
    }
}

private fun clampCopy(target: CalorieTargetResult): String =
    if (target.adjustment == TargetAdjustment.CAPPED_TO_PERCENTAGE) {
        "That pace needed a bigger cut than is safe, so Zephyr eased it to " +
            String.format(java.util.Locale.US, "%.1f", abs(Units.kgToLb(target.effectiveKgPerWeek))) +
            " lb a week. Going faster costs muscle — which is the opposite of looking better."
    } else {
        "Zephyr raised your target to keep it above what your body burns at rest. Eating under that " +
            "slows your metabolism and eats muscle."
    }

@Composable
private fun Callout(text: String, background: Color, foreground: Color) {
    Text(
        text = text,
        color = foreground,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(15.dp),
    )
}

@Composable
private fun StepHeading(title: String, subtitle: String) {
    Text(title, color = Z.Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.1).sp, lineHeight = 32.sp)
    Text(subtitle, color = Z.Muted, fontSize = 15.5.sp, lineHeight = 22.sp)
}
