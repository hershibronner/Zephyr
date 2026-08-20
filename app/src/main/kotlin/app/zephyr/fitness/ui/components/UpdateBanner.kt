package app.zephyr.fitness.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.theme.Z
import app.zephyr.fitness.update.UpdateState
import java.io.File

/**
 * The one place an update ever interrupts anything.
 *
 * It only appears when there is genuinely a newer build, it sits above the content rather than over
 * it, and it can be dismissed. A nag screen on launch is how people learn to dread opening an app.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Checking, up-to-date and failed states stay invisible: nobody opened Zephyr to watch it check
    // for updates, and a failure just means we try again next launch.
    val visible = state is UpdateState.Available ||
        state is UpdateState.Downloading ||
        state is UpdateState.ReadyToInstall

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Z.Nav)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    is UpdateState.Available -> {
                        Text(
                            "Update ready — ${state.manifest.versionName}",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                        if (state.manifest.notes.isNotBlank()) {
                            Text(
                                state.manifest.notes,
                                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp,
                            )
                        }
                    }

                    is UpdateState.Downloading -> {
                        Text(
                            if (state.fraction < 0f) {
                                "Downloading…"
                            } else {
                                "Downloading… ${(state.fraction * 100).toInt()}%"
                            },
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                        if (state.fraction >= 0f) {
                            Box(Modifier.padding(top = 4.dp)) {
                                ProgressBar(
                                    fraction = state.fraction,
                                    colour = Z.RingMint,
                                    track = Color.White.copy(alpha = 0.2f),
                                )
                            }
                        }
                    }

                    is UpdateState.ReadyToInstall -> {
                        Text(
                            "Ready to install",
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Takes a few seconds. Your data stays put.",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                        )
                    }

                    else -> Unit
                }
            }

            when (state) {
                is UpdateState.Available -> BannerButton("Get it", onDownload)
                is UpdateState.ReadyToInstall -> BannerButton("Install") { onInstall(state.file) }
                else -> Unit
            }

            if (state !is UpdateState.Downloading) {
                Text(
                    "×",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 20.sp,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun BannerButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Z.Nav,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
