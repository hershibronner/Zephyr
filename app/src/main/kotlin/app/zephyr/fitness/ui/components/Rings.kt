package app.zephyr.fitness.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zephyr.fitness.ui.theme.ZephyrColors

/**
 * The hero ring.
 *
 * Progress is animated with a spring rather than a linear tween because the ring is the app's one
 * moment of reward — logging a meal or finishing a run should feel like something moved, and a
 * spring settling into place reads as physical in a way a linear fill never does.
 *
 * Progress past 1.0 does not wrap around and start a second lap; it desaturates to amber and stops
 * at full. Wrapping would gamify overeating, and a ring that laps looks like an achievement.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    strokeWidth: Dp = 18.dp,
    trackColor: Color = ZephyrColors.Outline.copy(alpha = 0.45f),
    colors: List<Color> = listOf(ZephyrColors.Mint, ZephyrColors.Sky),
    overflowColor: Color = ZephyrColors.Amber,
    content: @Composable () -> Unit = {},
) {
    val isOver = progress > 1f
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 180f),
        label = "ringProgress",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (animated > 0f) {
                drawArc(
                    brush = if (isOver) {
                        Brush.sweepGradient(listOf(overflowColor, overflowColor))
                    } else {
                        Brush.sweepGradient(colors + colors.first())
                    },
                    startAngle = START_ANGLE,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

private const val START_ANGLE = -90f

/** A slim secondary ring, drawn inside the hero for a second metric such as steps. */
@Composable
fun InnerRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    strokeWidth: Dp = 8.dp,
    color: Color = ZephyrColors.Violet,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "innerRingProgress",
    )

    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)

        drawArc(
            color = color.copy(alpha = 0.18f),
            startAngle = START_ANGLE,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (animated > 0f) {
            drawArc(
                color = color,
                startAngle = START_ANGLE,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** A labelled horizontal bar, used for protein and other secondary targets. */
@Composable
fun MetricBar(
    label: String,
    value: String,
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = ZephyrColors.Mint,
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 200f),
        label = "barProgress",
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ZephyrColors.TextSecondary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = ZephyrColors.TextPrimary,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(
                color = ZephyrColors.Outline.copy(alpha = 0.4f),
                size = size,
                cornerRadius = radius,
            )
            if (animated > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(size.width * animated, size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}
