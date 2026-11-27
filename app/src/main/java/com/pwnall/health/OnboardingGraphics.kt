package com.pwnall.health

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * A figure whose digits roll rather than cut: each character that changes slides out the way the
 * value went and the new one slides in behind it, so turning a wheel up reads as counting up.
 *
 * Only a bare number rolls digit by digit, and always laid left to right — a number reads that way
 * in every script this app ships, and a Row in a right-to-left layout would put 24,2 down as ٢,٤٢. A
 * figure with a word in it ("25 yrs", "٢٥ سنة") rolls as one piece instead: cut into letters, Arabic
 * would lose the joins its letters are written with. Characters are keyed from the end, so "9"
 * becoming "10" rolls the units and grows a tens digit in front of them.
 */
@Composable
internal fun RollingFigure(text: String, value: Double, style: TextStyle, modifier: Modifier = Modifier) {
    val previous = remember { doubleArrayOf(value) }
    val rising = value >= previous[0]
    previous[0] = value
    val sign = if (rising) 1 else -1
    val roll: AnimatedContentTransitionScope<*>.() -> ContentTransform = {
        (slideInVertically(PwnMotion.fastSpatial()) { sign * it / 2 } + fadeIn(PwnMotion.effects()))
            .togetherWith(slideOutVertically(PwnMotion.fastSpatial()) { -sign * it / 2 } + fadeOut(PwnMotion.effects()))
    }
    if (text.any(Char::isLetter)) {
        AnimatedContent(text, modifier, transitionSpec = { roll() }, label = "figure") { shown ->
            Text(shown, style = style)
        }
        return
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(modifier.semantics { contentDescription = text }) {
            text.forEachIndexed { index, char ->
                androidx.compose.runtime.key(text.length - index) {
                    AnimatedContent(
                        targetState = char,
                        modifier = Modifier.clearAndSetSemantics {},
                        transitionSpec = { roll() },
                        label = "digit",
                    ) { shown -> Text(shown.toString(), style = style) }
                }
            }
        }
    }
}

/** The span of body mass index the scale draws: below and above it a marker pins to the edge. */
private const val BMI_SCALE_MIN = 15.0
private const val BMI_SCALE_MAX = 35.0

/** WHO's adult cut-points, the same three [bmiLabelRes] grades against. */
private val BMI_CUTS = listOf(18.5, 25.0, 30.0)

/**
 * Where the height and weight just given put the person among WHO's adult bands, drawn as the bands
 * themselves with a marker that springs along them as the weight ruler turns.
 *
 * It is the one picture on these pages that is made of the person's own numbers. Under eighteen, or
 * with no birth date to tell, the bands are drawn but none is lit and the line under them says why,
 * the way the profile card says it: a child's index is a percentile of age, not a point on this line.
 */
@Composable
internal fun BmiScale(bmi: Double, labelRes: Int, graded: Boolean, modifier: Modifier = Modifier) {
    val position by animateFloatAsState(
        ((bmi - BMI_SCALE_MIN) / (BMI_SCALE_MAX - BMI_SCALE_MIN)).toFloat().coerceIn(0f, 1f),
        PwnMotion.spatial(),
        label = "bmi marker",
    )
    val band = BMI_CUTS.count { bmi >= it }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // The band the person is in, in the accent itself at half strength: the container tone stands
    // a shade off the track and the band would not be found at a glance.
    val lit = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val marker = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.background
    val muted = PwnMuted
    val measurer = rememberTextMeasurer()
    val tickStyle = MaterialTheme.typography.labelSmall.copy(color = muted, fontFeatureSettings = "tnum")
    val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    val figure = String.format(locale, "%.1f", bmi)
    val verdict = stringResource(labelRes)
    val source = stringResource(R.string.onboarding_bmi_source)
    Column(
        // Read as one sentence: the figure, the band and whose bands they are. The drawing itself
        // says nothing a screen reader could, and the rolling digits would be read one by one.
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$figure, $verdict. $source" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profile_bmi), style = MaterialTheme.typography.titleSmall, color = muted)
            RollingFigure(
                figure,
                bmi,
                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            )
            Text(verdict, style = MaterialTheme.typography.titleSmall, color = if (graded) PwnInk else muted)
        }
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val rtl = layoutDirection == LayoutDirection.Rtl
            fun across(fraction: Float) = if (rtl) size.width * (1f - fraction) else size.width * fraction
            fun at(value: Double) = ((value - BMI_SCALE_MIN) / (BMI_SCALE_MAX - BMI_SCALE_MIN)).toFloat()
            val barTop = 4.dp.toPx()
            val barHeight = 10.dp.toPx()
            val gap = 3.dp.toPx()
            val edges = listOf(BMI_SCALE_MIN) + BMI_CUTS + BMI_SCALE_MAX
            edges.zipWithNext().forEachIndexed { index, (from, to) ->
                val start = across(at(from))
                val end = across(at(to))
                val left = minOf(start, end) + gap / 2
                val right = maxOf(start, end) - gap / 2
                drawRoundRect(
                    if (graded && index == band) lit else track,
                    Offset(left, barTop),
                    Size(right - left, barHeight),
                    CornerRadius(barHeight / 2),
                )
            }
            BMI_CUTS.forEach { cut ->
                val label = measurer.measure(cutLabel(cut, locale), tickStyle)
                drawText(
                    label,
                    topLeft = Offset(across(at(cut)) - label.size.width / 2f, barTop + barHeight + 5.dp.toPx()),
                )
            }
            val x = across(position)
            val y = barTop + barHeight / 2
            drawCircle(ring, 9.dp.toPx(), Offset(x, y))
            drawCircle(marker, 7.dp.toPx(), Offset(x, y))
            drawCircle(ring, 7.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
        }
        Text(source, style = MaterialTheme.typography.labelSmall, color = muted)
    }
}

/** A cut-point as the locale writes numbers: 18.5, 18,5 or ١٨٫٥, and 25 with no decimal at all. */
private fun cutLabel(cut: Double, locale: Locale): String =
    if (cut % 1.0 == 0.0) String.format(locale, "%d", cut.toInt()) else String.format(locale, "%.1f", cut)
