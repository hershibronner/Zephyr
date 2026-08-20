package app.zephyr.fitness.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zephyr.fitness.ui.theme.Z

/**
 * The hero ring, drawn white-on-colour because it sits on the violet card.
 *
 * Progress stops at full rather than starting a second lap — a lapping ring makes overeating look
 * like an achievement. The inner ring is steps, so one glance covers both numbers.
 */
@Composable
fun EnergyRing(
    calorieFraction: Float,
    stepFraction: Float,
    over: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 218.dp,
    content: @Composable () -> Unit,
) {
    val outer by animateFloatAsState(
        targetValue = calorieFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 160f),
        label = "calorieRing",
    )
    val inner by animateFloatAsState(
        targetValue = stepFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 180f),
        label = "stepRing",
    )
    val innerColour by animateColorAsState(
        targetValue = if (over) Z.RingAmber else Z.RingMint,
        label = "innerRingColour",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerStroke = 17.dp.toPx()
            val innerStroke = 7.dp.toPx()
            val gap = 19.dp.toPx()

            fun arc(color: Color, sweepFraction: Float, stroke: Float, inset: Float) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * sweepFraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            arc(Color.White.copy(alpha = 0.22f), 1f, outerStroke, outerStroke / 2)
            if (outer > 0f) arc(Color.White, outer, outerStroke, outerStroke / 2)

            val innerInset = outerStroke / 2 + gap
            arc(Color.White.copy(alpha = 0.16f), 1f, innerStroke, innerInset)
            if (inner > 0f) arc(innerColour, inner, innerStroke, innerInset)
        }
        content()
    }
}

/**
 * A colour-blocked stat tile. The hue carries the meaning — sky is always steps, green always
 * protein — so after a day or two the grid reads without reading any labels.
 */
@Composable
fun StatTile(
    value: String,
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    fraction: Float? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Z.TileRadius))
            .background(background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) { icon?.invoke() }

            if (fraction != null) {
                Text(
                    text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = foreground.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(value, color = foreground, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Text(label, color = foreground.copy(alpha = 0.72f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ZCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    background: Color = Z.Card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = RoundedCornerShape(Z.CardRadius),
        color = background,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun ProgressBar(
    fraction: Float,
    colour: Color,
    modifier: Modifier = Modifier,
    track: Color = Z.Line,
) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 190f),
        label = "bar",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxSize()
                .clip(CircleShape)
                .background(colour),
        )
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Z.Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.7).sp)
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                color = Z.Violet,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

@Composable
fun Kicker(text: String, colour: Color = Z.Faint) {
    Text(
        text = text.uppercase(),
        color = colour,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
    )
}

/** Soft blooms on the hero card, echoing the reference's props without pretending to be objects. */
fun Modifier.heroGlow(over: Boolean): Modifier = this.then(
    Modifier.background(
        Brush.linearGradient(
            colors = if (over) {
                listOf(Z.Warn, Color(0xFFF2A63A))
            } else {
                listOf(Z.Violet, Color(0xFF8E76FF))
            },
        ),
    ),
)
