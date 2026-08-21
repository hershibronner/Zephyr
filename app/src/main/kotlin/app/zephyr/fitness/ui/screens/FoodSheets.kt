package app.zephyr.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.data.db.FoodEntity
import app.zephyr.fitness.data.food.MealEstimate
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.theme.Z
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Asks how much was eaten, and shows the calories updating as the number changes.
 *
 * This is the step the app was missing. Logging everything as a flat 100 g meant the ledger — the
 * single number the whole app is built on — was quietly wrong for almost every entry.
 */
@Composable
fun PortionDialog(
    food: FoodEntity,
    grams: String,
    onGramsChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val value = grams.toDoubleOrNull() ?: 0.0
    val factor = value / 100.0
    val kcal = (food.kcalPer100 * factor).roundToInt()
    val protein = (food.proteinPer100 * factor).roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Card,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    listOfNotNull(food.brand, food.name).joinToString(" "),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Z.Ink,
                )
                Text(
                    String.format(Locale.US, "%.0f kcal per 100g", food.kcalPer100),
                    fontSize = 12.5.sp, color = Z.Faint,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = grams,
                    onValueChange = onGramsChange,
                    label = { Text("Grams") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Z.Violet,
                        unfocusedBorderColor = Z.Line,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                food.servingLabel?.let { label ->
                    Text("Packet says: $label", color = Z.Muted, fontSize = 12.5.sp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Z.VioletSoft)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${Units.number(kcal)} kcal",
                        color = Z.VioletInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "${protein}g protein",
                        color = Z.VioletInk.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value > 0) {
                Text("Log it", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Shows what the photo estimate found, itemised, before any of it reaches the ledger.
 *
 * The confidence is stated plainly rather than buried. An estimate presented as fact is worse than
 * no estimate, because the user stops checking it — and a photo genuinely cannot see portion depth
 * or how much oil went in the pan.
 */
@Composable
fun EstimateDialog(
    estimate: MealEstimate,
    offline: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (badgeBg, badgeInk) = when {
        offline -> Z.WarnSoft to Z.WarnInk
        estimate.confidence.lowercase() == "high" -> Z.GreenSoft to Z.GreenInk
        estimate.confidence.lowercase() == "low" -> Z.WarnSoft to Z.WarnInk
        else -> Z.SkySoft to Z.SkyInk
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Card,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${Units.number(estimate.totalKcal)} kcal",
                    fontWeight = FontWeight.Black, fontSize = 30.sp, color = Z.Ink,
                    letterSpacing = (-1.2).sp,
                )
                Text(
                    if (offline) {
                        "Offline guess"
                    } else {
                        "${estimate.confidence.replaceFirstChar { it.uppercase() }} confidence"
                    },
                    color = badgeInk, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(badgeBg)
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                estimate.items.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(item.name, color = Z.Ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            val detail = listOfNotNull(
                                item.portion.takeIf { it.isNotBlank() },
                                item.grams.takeIf { it > 0 }?.let { "${it.roundToInt()}g" },
                            ).joinToString(" · ")
                            if (detail.isNotEmpty()) {
                                Text(detail, color = Z.Faint, fontSize = 11.5.sp)
                            }
                        }
                        Text(
                            Units.number(item.kcal),
                            color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (estimate.note.isNotBlank()) {
                    Text(
                        estimate.note,
                        color = Z.Muted, fontSize = 12.5.sp, lineHeight = 17.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Z.Page)
                            .padding(12.dp),
                    )
                }

                Text(
                    if (offline) {
                        "Recognised on your phone, so it can name the food but not judge how much " +
                            "of it there is. Add an API key in settings for estimates that read " +
                            "the actual portion."
                    } else {
                        "Logged as separate items, so you can delete or correct any one of them."
                    },
                    color = Z.Faint, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Log it", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Discard") } },
    )
}
