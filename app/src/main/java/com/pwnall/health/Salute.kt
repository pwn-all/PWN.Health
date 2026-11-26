package com.pwnall.health

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * The rectangle a salute is fired over: where a point given in fractions of the box lands, and how
 * long a length given as a fraction of it is. Those are the only two questions the fireworks ever
 * ask about what is underneath them.
 */
internal class SaluteBox(private val width: Float, private val height: Float) {
    fun at(point: Offset) = Offset(point.x * width, point.y * height)

    // The narrower side, so a burst over a wide short box stays a burst rather than an ellipse.
    fun span(fraction: Float) = fraction * minOf(width, height)
}

internal class Firework(val at: Offset, val start: Float, val reach: Float, val spin: Float)

/**
 * A number that looks random and is not.
 *
 * Every star needs a throw of its own, and `Math.random()` in a draw pass gives a different scene
 * on every frame — the salute would boil rather than burn. This is the usual shader hash: one
 * integer in, the same fraction out, for ever.
 */
private fun noise(seed: Int): Float {
    val x = sin(seed * 12.9898f) * 43_758.5453f
    return x - floor(x)
}

/** How many stars each shell is packed with, and how long one burst takes of the whole salute. */
private const val FIREWORK_ARMS = 18
private const val FIREWORK_LIFE = 0.40f

/** How long the shell spends climbing before it opens, and how far it climbs. */
private const val FIREWORK_RISE = 0.13f
private const val FIREWORK_CLIMB = 0.30f

/** How far a star falls by the end of its life, as a share of its own throw. */
private const val FIREWORK_FALL = 0.52f

/** How much of its flight a star has behind it as a visible streak. */
private const val FIREWORK_STREAK = 0.24f

/** Past this much of its life a star is cooling, and flickers as it goes. */
private const val FIREWORK_TWINKLE_FROM = 0.55f

/**
 * How far a star has flown at [life] of its own, as a share of its throw.
 *
 * Nearly all of it in the first third and then almost nothing: a star leaves the shell fast and is
 * stopped by the air, and that deceleration is the whole difference between a firework and a wheel
 * of spokes growing at a constant rate.
 */
private fun fireworkSpread(life: Float): Float = 1f - (1f - life.coerceIn(0f, 1f)).pow(2.4f)

/**
 * The salute for something finished: six shells over the box, once, and then it is quiet again.
 *
 * Drawn rather than animated frame by frame — one float runs from 0 to 1 over the whole thing and
 * every star's position, length, colour and fade is a function of it. Nothing is random and nothing
 * is kept: the same second of animation is the same picture every time it plays. What varies from
 * star to star comes out of the same deterministic hash the rain falls on.
 *
 * Four things make it read as a firework rather than as a diagram of one. The shell is seen
 * climbing before it opens. The stars decelerate instead of expanding evenly. They fall, and they
 * fall faster the longer they have been out. And each shell burns from its own bright core colour
 * down to an ember, flickering as it cools, rather than staying one flat colour to the end.
 */
internal fun DrawScope.drawSalute(
    clock: Float,
    field: SaluteBox,
    shells: List<Pair<Color, Color>>,
    bursts: List<Firework> = CENTRED_SALUTE,
) {
    if (clock <= 0f) return
    bursts.forEachIndexed { index, burst ->
        val since = clock - burst.start
        if (since <= 0f) return@forEachIndexed
        val centre = field.at(burst.at)
        val (core, ember) = shells[index % shells.size]
        if (since < FIREWORK_RISE) {
            drawFireworkShell(centre, field, since / FIREWORK_RISE, core)
            return@forEachIndexed
        }
        val life = (since - FIREWORK_RISE) / FIREWORK_LIFE
        if (life >= 1f) return@forEachIndexed

        val reach = field.span(burst.reach)
        val fade = (1f - life).pow(1.5f)
        val width = field.span(0.0055f) * (1f - life * 0.55f)
        // The flash the stars come out of, gone by the time they are out.
        val flash = (1f - life * 5f).coerceAtLeast(0f)
        if (flash > 0f) drawCircle(core, field.span(0.026f) * flash, centre, flash)

        val colour = lerp(core, ember, life)
        for (arm in 0 until FIREWORK_ARMS) {
            val angle = (arm.toFloat() / FIREWORK_ARMS + burst.spin) * 2f * Math.PI.toFloat()
            val dir = Offset(cos(angle), sin(angle))
            // No two stars in a shell fly the same distance. A ring of equal spokes is the single
            // thing that makes a drawn firework look drawn.
            val thrown = reach * (0.76f + 0.36f * noise(index * 61 + arm))
            val head = thrown * fireworkSpread(life)
            val tail = thrown * fireworkSpread(life - FIREWORK_STREAK)
            val headAt = centre + dir * head + Offset(0f, fireworkFall(reach, life))
            val tailAt = centre + dir * tail +
                Offset(0f, fireworkFall(reach, life - FIREWORK_STREAK))
            // A cooling star flickers. The flicker is a function of the one clock, so it is the
            // same flicker every time this plays rather than a per-frame sparkle.
            val alpha = fade * if (life > FIREWORK_TWINKLE_FROM) {
                0.55f + 0.45f * sin(clock * 88f + arm * 2.4f)
            } else {
                1f
            }
            drawLine(
                color = colour,
                start = tailAt,
                end = headAt,
                strokeWidth = width,
                cap = StrokeCap.Round,
                alpha = alpha.coerceIn(0f, 1f),
            )
            // The burning head, brighter and rounder than the trail behind it.
            drawCircle(colour, width * 0.85f, headAt, (alpha * 1.2f).coerceIn(0f, 1f))
        }
    }
}

/** How far a star has fallen at [life] of its own, in pixels. Gravity, so it grows on the square. */
private fun fireworkFall(reach: Float, life: Float): Float =
    reach * FIREWORK_FALL * life.coerceAtLeast(0f).pow(2f)

/**
 * The shell on its way up, before it opens.
 *
 * Slowest at the top, because that is where it bursts, and the trail behind it shortens as it goes.
 * Half a second of this is what tells the eye that the burst above came from somewhere.
 */
private fun DrawScope.drawFireworkShell(
    centre: Offset,
    field: SaluteBox,
    rise: Float,
    colour: Color,
) {
    val climb = field.span(FIREWORK_CLIMB)
    val eased = 1f - (1f - rise).pow(2f)
    val head = centre + Offset(0f, climb * (1f - eased))
    val tail = head + Offset(0f, climb * 0.16f * (1f - rise * 0.7f))
    drawLine(
        color = colour,
        start = tail,
        end = head,
        strokeWidth = field.span(0.0038f),
        cap = StrokeCap.Round,
        alpha = 0.50f * (1f - rise * 0.4f),
    )
    drawCircle(colour, field.span(0.0044f), head, alpha = 0.85f * (1f - rise * 0.3f))
}

/**
 * Where the bursts go off, when each is fired and how far each throws, as fractions of the box and
 * of the run — so the salute lands the same at any size and nothing about it is random.
 *
 * Six shells ringed around the middle: above it, either side of it, none of them on top of it, so
 * whatever the box is celebrating stays readable through the display. Deliberately not evenly
 * spaced — a display has a rhythm, and six on a metronome is a screensaver.
 */
internal val CENTRED_SALUTE = listOf(
    Firework(Offset(0.30f, 0.30f), start = 0.00f, reach = 0.30f, spin = 0.00f),
    Firework(Offset(0.72f, 0.24f), start = 0.08f, reach = 0.26f, spin = 0.22f),
    Firework(Offset(0.50f, 0.12f), start = 0.20f, reach = 0.24f, spin = 0.11f),
    Firework(Offset(0.16f, 0.52f), start = 0.26f, reach = 0.22f, spin = 0.31f),
    Firework(Offset(0.86f, 0.50f), start = 0.36f, reach = 0.21f, spin = 0.17f),
    Firework(Offset(0.60f, 0.40f), start = 0.44f, reach = 0.25f, spin = 0.06f),
)
