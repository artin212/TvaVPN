package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlin.math.floor
import kotlin.math.sin

/**
 * The travelling border light of the connection card.
 *
 * ## The palette: primary colours only
 *
 * 1.2.7 ran the cycle through mint, cyan, azure, violet, rose and amber - six
 * tints of the same cool corner of the wheel, three of which (mint/cyan/azure)
 * are barely distinguishable at hairline width on a navy card, and none of which
 * reads as a colour in its own right. The cycle is now the **primary colours**:
 * red, green, blue, yellow. Four laps, four unmistakable colours, and the
 * sequence is legible from across a room.
 *
 * The two cool primaries are luminance-trimmed rather than mathematically pure,
 * and that is deliberate: this light is drawn *additively* on a `#0A0E1A`
 * surface, where pure `#0000FF` has too little luminance to read as light at all,
 * and pure `#00FF00` clips its own bloom to white. See [GlowCycleColors].
 *
 * ## One colour per lap
 *
 * The light keeps ONE colour for a whole lap of the perimeter and the next lap
 * runs in the next colour. After the last colour it wraps back to the first, so
 * the sequence is endless and never fades mid-lap.
 *
 * ## Fidelity
 *
 * The 1.2.7 bloom was three strokes, the widest of them `4.4x` the band width,
 * with a highlight lerped 42% toward white. At peak amplitude that stacked to a
 * hard-edged white blob: additive blending clipped the core, and three steps of
 * alpha across a very wide falloff banded visibly on 8-bit panels - which is the
 * "pixelated" look. It is now **five** graded strokes over a narrower falloff,
 * with a smoothstep on the amplitude and a peak alpha held below saturation, so
 * the band reads as light with a smooth edge instead of a blob with a staircase
 * on it. Every stroke is round-capped AND round-joined; a mitred join on the
 * card's corner radius put a visible spike on the corners.
 *
 * ## Cost
 *
 * Unchanged from 1.2.7, which is the point of keeping it here:
 *  - the whole cycle is ONE `animateFloat`, from 0 to the number of colours, so
 *    the lap index is `floor(value)` and the wrap is seamless;
 *  - callers read the state INSIDE their draw lambda, so a frame costs a redraw
 *    and never a recomposition;
 *  - the transition is composed ONLY while connected, so a disconnected app
 *    subscribes to no frame callbacks;
 *  - the bloom is strokes in additive blend - no blur pass, no extra layer.
 */
val GlowCycleColors: List<Color> = listOf(
    Color(0xFFFF1E1E), // red
    Color(0xFF00E23C), // green   - trimmed so its own bloom does not clip to white
    Color(0xFF2A6BFF), // blue    - lifted off #0000FF, which reads as black on navy
    Color(0xFFFFD400), // yellow
)

/** Milliseconds for ONE full lap of the perimeter (one colour). */
const val GLOW_LAP_MS = 5_200

/**
 * The animated state of the light show: how far round the current lap the bands
 * are, which colour this lap runs in, and the slow overall breathing.
 */
class GlowCycle(
    private val travel: State<Float>,
    private val breathState: State<Float>,
) {
    /** 0..1 within the current lap. Read inside a draw lambda. */
    val phase: Float get() = travel.value.let { it - floor(it) }

    /** Overall intensity, so the edge breathes instead of only flickering. */
    val breath: Float get() = breathState.value

    /** The colour of the CURRENT lap. */
    val colour: Color
        get() = GlowCycleColors[floor(travel.value).toInt().mod(GlowCycleColors.size)]
}

@Composable
fun rememberGlowCycle(lapMillis: Int = GLOW_LAP_MS): GlowCycle {
    val transition = rememberInfiniteTransition(label = "glowCycle")
    // 0 -> N linearly, so floor() is the lap (== the colour) and the fractional
    // part is the position on the perimeter. Restarting at 0 after the last lap
    // is what returns the sequence to the first colour with no visible seam.
    val travel = transition.animateFloat(
        initialValue = 0f,
        targetValue = GlowCycleColors.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(lapMillis * GlowCycleColors.size, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "travel",
    )
    val breath = transition.animateFloat(
        initialValue = 0.74f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    return remember(travel, breath) { GlowCycle(travel, breath) }
}

/**
 * One equaliser band: where it sits on the perimeter, how long it is, and which
 * harmonic of the travel phase drives its intensity. The harmonics are WHOLE
 * numbers on purpose - a fractional one would jump when the phase wraps from 1
 * back to 0 and the whole edge would visibly stutter once per lap.
 */
private class GlowBand(
    val offset: Float,
    val span: Float,
    val harmonic: Int,
    val skew: Float,
    val tint: Float,
)

private val GLOW_BANDS = listOf(
    GlowBand(offset = 0.00f, span = 0.15f, harmonic = 2, skew = 0.00f, tint = 0.00f),
    GlowBand(offset = 0.13f, span = 0.08f, harmonic = 3, skew = 0.34f, tint = 0.45f),
    GlowBand(offset = 0.28f, span = 0.13f, harmonic = 5, skew = 0.11f, tint = 0.20f),
    GlowBand(offset = 0.43f, span = 0.06f, harmonic = 7, skew = 0.61f, tint = 0.85f),
    GlowBand(offset = 0.56f, span = 0.14f, harmonic = 3, skew = 0.79f, tint = 0.35f),
    GlowBand(offset = 0.70f, span = 0.09f, harmonic = 5, skew = 0.24f, tint = 0.65f),
    GlowBand(offset = 0.85f, span = 0.12f, harmonic = 2, skew = 0.50f, tint = 1.00f),
)

/**
 * The bloom, outside-in: width multiplier and alpha multiplier per layer.
 *
 * Five steps over a 3.2x falloff instead of three over 4.4x. The alphas are a
 * smooth curve rather than three jumps, which is what removes the banding, and
 * the last one stops short of 1 so an additive core never clips to white.
 */
private val GLOW_LAYERS = listOf(
    3.2f to 0.045f,
    2.4f to 0.085f,
    1.7f to 0.16f,
    1.25f to 0.34f,
    1.0f to 0.82f,
)

/**
 * Draws the travelling bands along [measure]'s path, in this lap's colour.
 *
 * @param band a scratch [Path], owned by the caller so a frame allocates nothing.
 * @param stroke base stroke width in pixels.
 */
fun DrawScope.drawGlowCycle(
    measure: PathMeasure,
    perimeter: Float,
    band: Path,
    cycle: GlowCycle,
    stroke: Float,
) {
    if (perimeter <= 0f) return
    val phase = cycle.phase
    val breath = cycle.breath
    val base = cycle.colour
    // Band-to-band variety WITHIN the lap's colour: a lighter version of the
    // same hue, never a different one, so the lap reads as a single colour. 0.30
    // instead of 0.42: at 0.42 a peaking band was closer to white than to its own
    // colour, which is the opposite of "one colour per lap".
    val highlight = lerp(base, Color.White, 0.30f)

    for (spec in GLOW_BANDS) {
        val raw = 0.5f + 0.5f * sin(TWO_PI * (spec.harmonic * phase + spec.skew))
        // Smoothstep: eases the ends of every band's swell, so its edge fades
        // instead of stepping.
        val amp = raw * raw * (3f - 2f * raw)
        val length = perimeter * spec.span * (0.30f + 0.95f * amp)
        val start = ((phase + spec.offset) % 1f) * perimeter
        val colour = lerp(base, highlight, (spec.tint * 0.6f + amp * 0.4f).coerceIn(0f, 1f))
        val width = stroke * (1.05f + 1.75f * amp)
        val alpha = (0.18f + 0.82f * amp) * breath

        band.reset()
        measure.appendSegment(band, start, length, perimeter)

        for ((widthScale, alphaScale) in GLOW_LAYERS) {
            drawGlowStroke(band, colour, alpha * alphaScale, width * widthScale)
        }
    }
}

private fun DrawScope.drawGlowStroke(path: Path, colour: Color, alpha: Float, width: Float) {
    drawPath(
        path = path,
        color = colour.copy(alpha = alpha.coerceIn(0f, 1f)),
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            // Round, not the default mitre: a mitred join on the card's 26 dp
            // corner radius spiked visibly at the corners.
            join = StrokeJoin.Round,
        ),
        blendMode = BlendMode.Plus,
    )
}

/** Copies a piece of the perimeter, wrapping around the corner if it overruns. */
private fun PathMeasure.appendSegment(dst: Path, start: Float, length: Float, perimeter: Float) {
    val end = start + length
    if (end <= perimeter) {
        getSegment(start, end, dst, true)
    } else {
        getSegment(start, perimeter, dst, true)
        getSegment(0f, end - perimeter, dst, true)
    }
}

const val TWO_PI = 6.2831855f
