package app.zephyr.fitness.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.ProgressBar
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.fasting.Fasting
import dev.zephyr.core.fasting.FastingPhase
import dev.zephyr.core.fasting.FastingPlan
import dev.zephyr.core.fasting.FastingStatus
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The fasting clock, on Today.
 *
 * It runs off the last logged meal rather than a separate "start fast" button. A fast you have to
 * remember to declare is one you forget to declare — and then the app is confidently wrong about
 * the only thing it was tracking.
 */
@Composable
fun FastingCard(status: FastingStatus, onOpenPicker: () -> Unit) {
    val fraction by animateFloatAsState(
        targetValue = status.fraction,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 140f),
        label = "fastProgress",
    )

    val fasting = status.phase == FastingPhase.FASTING
    val background = if (fasting) Z.Nav else Z.Card
    val ink = if (fasting) Color.White else Z.Ink
    val muted = if (fasting) Color.White.copy(alpha = 0.62f) else Z.Muted

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(background)
            .clickable(onClick = onOpenPicker)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Kicker(
                if (status.plan == FastingPlan.OFF) "Fasting" else status.plan.label,
                colour = if (fasting) Color.White.copy(alpha = 0.5f) else Z.Faint,
            )
            Text("Change", color = if (fasting) Z.RingMint else Z.Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        when (status.phase) {
            FastingPhase.OFF -> {
                Text(
                    if (status.plan == FastingPlan.OFF) "Fasting is off" else "Waiting on your first meal",
                    color = ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp,
                )
                Text(Fasting.message(status), color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }

            FastingPhase.FASTING -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        status.remainingLabel,
                        color = ink, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.6).sp,
                    )
                    Text(
                        "  to go",
                        color = muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
                ProgressBar(
                    fraction = fraction,
                    colour = Z.RingMint,
                    track = Color.White.copy(alpha = 0.18f),
                )
                Text(
                    status.opensAt?.let { opens ->
                        "Eat from ${opens.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))} " +
                            "— ${status.elapsedLabel} fasted so far."
                    } ?: Fasting.message(status),
                    color = muted, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }

            FastingPhase.EATING -> {
                Text(
                    "Window open",
                    color = Z.GreenInk, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(Fasting.message(status), color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }

            FastingPhase.WINDOW_CLOSED -> {
                Text(
                    "Window closed",
                    color = ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                )
                Text(Fasting.message(status), color = muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

/** Picks the protocol, with the recommendation marked so there is an obvious default. */
@Composable
fun FastingPickerDialog(
    current: FastingPlan,
    recommended: FastingPlan,
    onPick: (FastingPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Card,
        title = { Text("Fasting window", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The clock starts from your last logged meal. Fasting doesn't burn fat by " +
                        "itself — it just makes a smaller day easier to hold.",
                    color = Z.Muted, fontSize = 12.5.sp, lineHeight = 17.sp,
                )

                FastingPlan.entries.forEach { plan ->
                    val on = plan == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) Z.VioletSoft else Z.Page)
                            .clickable { onPick(plan) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                plan.label,
                                color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(plan.blurb, color = Z.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        if (plan == recommended) {
                            Text(
                                "Suggested",
                                color = Z.GreenInk, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Z.GreenSoft)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", fontWeight = FontWeight.Bold) } },
    )
}

/**
 * The twice-weekly weigh-in ask.
 *
 * "Later" genuinely means later, not never — it returns in an hour and keeps returning until a
 * number is entered or the day ends. Defensible because the adaptive-TDEE engine, which is what
 * keeps the calorie target from going stale, is blind without regular weigh-ins. Bounded, because
 * it never survives midnight and one entry silences it for the day.
 */
@Composable
fun WeighInPromptDialog(
    attempt: Int,
    message: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLater: () -> Unit,
) {
    val weight = value.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onLater,
        containerColor = Z.Card,
        title = { Text("What are you weighing?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message, color = Z.Muted, fontSize = 13.5.sp, lineHeight = 19.sp)

                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> onValueChange(input.filter { it.isDigit() || it == '.' }.take(5)) },
                    label = { Text("Pounds") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (attempt >= 2) {
                    Text(
                        "Weigh in the morning, after the bathroom, before food — same conditions " +
                            "every time is what makes the trend readable.",
                        color = Z.Faint, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = weight != null && weight > 0) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
    )
}
