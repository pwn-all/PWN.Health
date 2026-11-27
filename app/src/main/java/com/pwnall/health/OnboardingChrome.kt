package com.pwnall.health

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp

/**
 * How many questions the progress line counts: every page between the greeting and the finish.
 * The greeting is not a step and the finish is not a question, so neither has a segment.
 */
internal const val ONBOARDING_STEPS = 8

/** The side margin every page of onboarding keeps. */
internal val ONBOARDING_SIDE = 24.dp

/**
 * What stays put while the pages change under it: Back, and a line cut into one segment per
 * question. It lives outside the page transition on purpose — a progress line that slid away with
 * each page and slid back in with the next would never be seen to move, and moving is its job.
 */
@Composable
internal fun OnboardingHeader(step: Int, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = ONBOARDING_SIDE - 8.dp, end = ONBOARDING_SIDE, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OnboardingBack(onBack)
        SegmentedProgress(step, ONBOARDING_STEPS, Modifier.weight(1f))
        // The count in figures as well as in segments: eight short segments are easy to miscount,
        // and "3/8" is what the eye actually reads off a progress line.
        val locale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
        Text(
            String.format(locale, "%d/%d", step + 1, ONBOARDING_STEPS),
            Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = PwnMuted,
        )
    }
}

/**
 * One segment per question, filled up to and including the one on screen.
 *
 * Each segment fills from its start edge with the theme's spatial spring rather than snapping, so
 * Next is seen to push the line one segment on and Back to take it one back — the only motion on
 * the header, and the one that says where the person is. A Row lays the segments out, so in a
 * right-to-left language they fill from the right without a word of code about it.
 */
@Composable
private fun SegmentedProgress(step: Int, total: Int, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.m3_profile_onboarding_step, step + 1, total)
    val filled = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier.height(6.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo((step + 1).toFloat(), 0f..total.toFloat(), total - 1)
            stateDescription = spoken
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { index ->
            val fill by animateFloatAsState(
                if (index <= step) 1f else 0f,
                PwnMotion.spatial(),
                label = "segment $index",
            )
            Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(track)) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(fill.coerceIn(0f, 1f)).clip(CircleShape)
                        .background(filled),
                )
            }
        }
    }
}

/**
 * What the person has answered so far, one small tag per answer, under the progress line.
 *
 * The answers are the only thing on these pages that is about this person, so they are what the
 * flow shows of itself: a tag arrives the moment its question is behind, and Back takes it away
 * again. Nothing here is computed or guessed — it is the person's own figures, read back.
 */
@Composable
internal fun OnboardingFacts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    // The last text each tag carried, so a tag shrinking away after Back still reads as it did.
    val last = remember { mutableMapOf<String, String>() }
    facts.forEach { (key, text) -> last[key] = text }
    Row(
        modifier.fillMaxWidth().padding(horizontal = ONBOARDING_SIDE).heightIn(min = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FACT_KEYS.forEach { key ->
            val fact = facts.firstOrNull { it.first == key }?.second
            AnimatedVisibility(
                visible = fact != null,
                enter = expandHorizontally(PwnMotion.spatial()) + scaleIn(PwnMotion.spatial(), 0.6f) +
                    fadeIn(PwnMotion.effects()),
                exit = shrinkHorizontally(PwnMotion.spatial()) + fadeOut(PwnMotion.effects()),
                label = "fact $key",
            ) {
                FactTag(last[key].orEmpty())
            }
        }
    }
}

/** The order the tags stand in, which is the order the questions are asked in. */
internal val FACT_KEYS = listOf("age", "sex", "height", "weight", "steps")

@Composable
private fun FactTag(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(PwnCorners.small))
            .border(1.dp, PwnBorder, RoundedCornerShape(PwnCorners.small))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = PwnInk,
        maxLines = 1,
    )
}

/**
 * One question's page: the question as a heading, a line saying what the answer is for, the control
 * that answers it, and the one button that moves on, kept within reach at the bottom.
 *
 * No picture beside the heading: an icon on a blob would only repeat the heading in a drawing. What
 * a page draws is the answer.
 */
@Composable
internal fun OnboardingPage(
    title: String,
    action: @Composable () -> Unit,
    lead: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Centred at the width a panel stands at, so on a tablet the copy keeps a readable measure and
    // the one button does not stretch across the window.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = PwnDimensions.maxPaneWidth).fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(rememberScrollState())
                    .padding(horizontal = ONBOARDING_SIDE).padding(top = 20.dp, bottom = 24.dp),
            ) {
                Text(
                    title,
                    Modifier.semantics { heading() },
                    // A long single word — a German or Russian title at a large font — has to break
                    // somewhere. Broken at a syllable with a hyphen it still reads as one word.
                    style = MaterialTheme.typography.headlineLarge.copy(
                        hyphens = Hyphens.Auto,
                        lineBreak = LineBreak.Heading,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                lead?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = PwnMuted)
                }
                Spacer(Modifier.height(28.dp))
                content()
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = ONBOARDING_SIDE).padding(top = 8.dp, bottom = 20.dp)) {
                action()
            }
        }
    }
}

@Composable
internal fun OnboardingBack(onClick: () -> Unit) {
    // The clickable Surface clips its own ripple to the circle; a clickable laid on the outside of
    // a plain Surface would draw the pressed and focused state as a square around it.
    Surface(
        onClick = onClick,
        modifier = Modifier.minimumInteractiveComponentSize().size(44.dp).semantics { role = Role.Button },
        shape = CircleShape,
        color = Color.Transparent,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = PwnInk)
        }
    }
}

/**
 * The page's one action: a solid button in the theme's blue, deepened until white lettering reads on
 * it. Not a gradient: a gradient fill on the main button is one of the marks a generated design is
 * known by, and it carries nothing a single colour does not.
 */
@Composable
internal fun OnboardingAction(label: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    // Worked out once per accent rather than on every frame of the pressed corner below.
    val actionColor = remember(primary) { actionFill(primary).first() }
    // Material's disabled treatment: a faint container under faint content, so a button that cannot
    // be pressed recedes rather than standing out from the page in a grey of its own.
    val fill = if (enabled) actionColor else onSurface.copy(alpha = 0.12f)
    val ink = if (enabled) ACTION_LABEL else onSurface.copy(alpha = 0.38f)
    // The expressive pressed shape: the corners square off under the finger and spring back when it
    // lifts. The clip is what keeps the ripple inside those corners.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val corner by animateDpAsState(
        if (pressed) PwnCorners.small else PwnCorners.large,
        PwnMotion.fastSpatial(),
        label = "action corner",
    )
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.widthIn(max = PwnDimensions.maxButtonWidth).fillMaxWidth().heightIn(min = 58.dp)
            .clip(shape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = PwnDimensions.mediumButtonPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, color = ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Icon(icon, null, tint = ink)
        }
    }
}

/**
 * The action's colour: the theme's blue deepened toward [ACTION_DEEP], further until white
 * lettering clears normal-text contrast. The first stop is the button's fill. The second, paler one
 * is drawn by nothing, but the fill is deepened until it clears as well, which sets how deep the
 * dark palette's button stands.
 */
internal fun actionFill(accent: Color): List<Color> {
    var shift = 0f
    fun stops() = ACTION_MIXES.map { lerp(accent, ACTION_DEEP, (it + shift).coerceAtMost(1f)) }
    while (shift < 1f && stops().any { contrastRatio(ACTION_LABEL, it) < ACTION_LABEL_CONTRAST }) {
        shift += ACTION_MIX_STEP
    }
    return stops()
}

/** The deep blue the action is mixed toward: the far end of the brand's own hue. */
private val ACTION_DEEP = Color(0xFF163968)

/** The lettering on the action, which [actionFill] deepens the fill far enough to carry. */
private val ACTION_LABEL = Color.White

/** How far toward [ACTION_DEEP] each stop starts, the deeper one first. */
private val ACTION_MIXES = listOf(0.50f, 0.38f)
private const val ACTION_MIX_STEP = 0.05f
private const val ACTION_LABEL_CONTRAST = 4.5f
