package app.zephyr.fitness.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The app's motion vocabulary, in one place.
 *
 * Consistency is the whole point: when every card enters the same way and every press gives the
 * same amount of give, the motion reads as one designed system rather than a collection of separate
 * effects. Springs are used over fixed durations almost everywhere, because spring physics is what
 * makes an interface feel like it has weight rather than like it is playing a video.
 */
object Motion {

    /** Numbers settle rather than snapping — the difference between a value and an event. */
    fun countSpec() = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 90f,
    )

    val enterSpring = spring<Float>(
        dampingRatio = 0.78f,
        stiffness = 220f,
    )

    /** Each item waits a little longer than the one above it. Capped so long lists stay snappy. */
    fun staggerDelayMillis(index: Int): Int = (index.coerceAtMost(7) * 45)
}

/**
 * Counts a number up to its target instead of replacing it.
 *
 * The eye tracks a changing number; a swapped one is just new text. Worth it on the figures the
 * user actually came to look at, not on every label.
 */
@Composable
fun animatedCount(target: Int, animate: Boolean = true): Int {
    if (!animate) return target
    val value by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = Motion.countSpec(),
        label = "count",
    )
    return value.roundToInt()
}

/**
 * Fades and lifts content into place once, on first composition.
 *
 * [index] staggers a list so rows arrive in sequence rather than all at once, which is what makes a
 * screen feel assembled rather than pasted.
 */
@Composable
fun Modifier.entrance(index: Int = 0): Modifier {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(Motion.staggerDelayMillis(index).toLong())
        progress.animateTo(1f, animationSpec = Motion.enterSpring)
    }

    return this
        .graphicsLayer {
            alpha = progress.value
            // Small: a big travel distance reads as a slideshow, not as a screen settling.
            translationY = (1f - progress.value) * 34f
        }
}

/**
 * Gives a tappable surface physical give.
 *
 * Pass the same [interactionSource] to the `clickable` so the scale tracks the real press rather
 * than a guess about it.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "press",
    )
    return this.scale(scale)
}

/** A slow breath, for anything that is live right now — a recording session, a running fast. */
@Composable
fun rememberPulse(
    from: Float = 0.45f,
    to: Float = 1f,
    periodMillis: Int = 1400,
): State<Float> {
    val transition = rememberInfiniteTransition(label = "pulse")
    return transition.animateFloat(
        initialValue = from,
        targetValue = to,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseValue",
    )
}

/** Applies [rememberPulse] to opacity, for a live indicator dot. */
@Composable
fun Modifier.pulsing(): Modifier {
    val pulse by rememberPulse()
    return this.alpha(pulse)
}

/**
 * A one-off celebratory pop, for a number that has just landed — a finished run, a logged meal.
 * Overshoots and settles, which is what makes an arrival feel like an arrival.
 */
@Composable
fun Modifier.popIn(key: Any?): Modifier {
    val scale = remember { Animatable(0.9f) }
    LaunchedEffect(key) {
        scale.snapTo(0.9f)
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 380f))
    }
    return this.scale(scale.value)
}
