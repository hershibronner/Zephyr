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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.data.db.FoodEntity
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.data.db.MealSlot
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.components.Kicker
import app.zephyr.fitness.ui.components.ProgressBar
import app.zephyr.fitness.ui.components.SectionHeader
import app.zephyr.fitness.ui.theme.Z
import dev.zephyr.core.ledger.EnergyBalance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The day's eating, and every way of adding to it.
 *
 * Meal slots group the list because that's how people remember food — "what did I have at lunch" —
 * rather than as one undifferentiated stream sorted by clock time.
 */
@Composable
fun FoodScreen(
    balance: EnergyBalance,
    entries: List<FoodLogEntity>,
    recents: List<FoodEntity>,
    canUsePhotos: Boolean,
    onScan: () -> Unit,
    onQuickAdd: () -> Unit,
    onPickRecent: (FoodEntity) -> Unit,
    onDelete: (FoodLogEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Totals(balance) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    label = if (canUsePhotos) "Scan or shoot" else "Scan barcode",
                    sublabel = if (canUsePhotos) "Packet or plate" else "Packaged food",
                    background = Z.VioletSoft,
                    foreground = Z.VioletInk,
                    icon = if (canUsePhotos) Icons.Filled.CameraAlt else Icons.Filled.QrCodeScanner,
                    modifier = Modifier.weight(1f),
                    onClick = onScan,
                )
                ActionTile(
                    label = "Quick add",
                    sublabel = "Just calories",
                    background = Z.OrangeSoft,
                    foreground = Z.OrangeInk,
                    icon = null,
                    modifier = Modifier.weight(1f),
                    onClick = onQuickAdd,
                )
            }
        }

        if (entries.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Z.CardRadius))
                        .background(Z.Card)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Nothing logged yet", color = Z.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Scan a barcode, photograph the plate, or just punch in a number. " +
                            "Logging roughly beats not logging at all.",
                        color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
            }
        }

        MealSlot.entries.forEach { slot ->
            val forSlot = entries.filter { it.slot == slot }
            if (forSlot.isEmpty()) return@forEach

            item(key = "header-${slot.name}") {
                SlotHeader(slot, forSlot.sumOf { it.kcal })
            }
            items(forSlot, key = { it.id }) { entry ->
                LoggedRow(entry) { onDelete(entry) }
            }
        }

        if (recents.isNotEmpty()) {
            item { SectionHeader("Log again") }
            items(recents, key = { "recent-${it.id}" }) { food ->
                RecentRow(food) { onPickRecent(food) }
            }
        }
    }
}

@Composable
private fun Totals(balance: EnergyBalance) {
    val over = balance.remainingKcal < 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.CardRadius))
            .background(Z.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kicker(if (over) "Over budget" else "Left to eat")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Units.number(abs(balance.remainingKcal)),
                color = if (over) Z.Warn else Z.Ink,
                fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp,
            )
            Text(
                "  of ${Units.number(balance.adjustedTargetKcal)} kcal",
                color = Z.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        ProgressBar(balance.fractionConsumed, if (over) Z.Warn else Z.Violet)
        Text(
            "${balance.macros.proteinG}g protein of ${balance.proteinTargetG}g — " +
                "the part that decides whether you lose fat or muscle.",
            color = Z.Muted, fontSize = 12.5.sp, lineHeight = 17.sp,
        )
    }
}

@Composable
private fun ActionTile(
    label: String,
    sublabel: String,
    background: Color,
    foreground: Color,
    icon: ImageVector?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(background)
            .clickable(onClick = onClick)
            .padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = foreground, modifier = Modifier.size(18.dp))
            } else {
                Text("+", color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(label, color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(sublabel, color = foreground.copy(alpha = 0.72f), fontSize = 12.sp)
    }
}

@Composable
private fun SlotHeader(slot: MealSlot, kcal: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slot.label, color = Z.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("${Units.number(kcal)} kcal", color = Z.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoggedRow(entry: FoodLogEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(Z.Card)
            .padding(horizontal = 17.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val detail = buildList {
                entry.quantityGrams?.let { add("${it.roundToInt()}g") }
                if (entry.proteinG > 0) add("${entry.proteinG.roundToInt()}g protein")
            }.joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, color = Z.Faint, fontSize = 11.5.sp)
            }
        }
        Text(Units.number(entry.kcal), color = Z.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "×",
            color = Z.Faint, fontSize = 20.sp,
            modifier = Modifier.padding(start = 8.dp).clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun RecentRow(food: FoodEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(Z.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(food.brand, food.name).joinToString(" "),
                color = Z.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                String.format(Locale.US, "%.0f kcal per 100g", food.kcalPer100),
                color = Z.Faint, fontSize = 11.5.sp,
            )
        }
        Text("Add", color = Z.Violet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
