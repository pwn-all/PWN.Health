package com.pwnall.health

import android.content.res.Resources
import android.graphics.ImageDecoder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One template's own page: its photo across
 * the top with the way back and a menu over it; a sheet rising over the photo's foot with the name
 * in large type, the few words, the muscles it works and the days it is for, and what it takes — the
 * minutes and the energy; then the plan, each step on a white card numbered, with its drawing, its
 * figures and a pill of the one that counts, a superset's exercises together, a rest between steps;
 * and fixed at the foot, Start, Log and Plan. A tap on an exercise unfolds the drawing of how it is
 * done.
 *
 * There is no "Watch video": the app has no videos and no network.
 */
@Composable
internal fun RoutineViewPage(
    routine: Routine,
    rows: List<RoutineExercise>,
    imperial: Boolean,
    restingKcalPerHour: Double,
    bodyKg: Double,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onLog: () -> Unit,
    onPlan: () -> Unit,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val blocks = remember(rows) { routineBlocks(rows) }
    val totals = remember(blocks, restingKcalPerHour, bodyKg) { routineTotals(blocks, restingKcalPerHour, bodyKg) }
    // The exercise whose how-to is unfolded, by its place in the plan; -1 while none is.
    var unfolded by rememberSaveable { mutableIntStateOf(-1) }
    val file = routinePhotoFile(context, routine.photo)
    val photo by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) {
            // Decoded at its size rather than as a thumbnail: it is as wide as the phone. Held to
            // PROGRESS_SIDE here as well as where it is picked: a backup puts its pictures back as the
            // file carries them, and a file edited by hand can carry one of any size.
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(checkNotNull(file))) { decoder, info, _ ->
                    val (width, height) = shrunkSize(info.size.width, info.size.height)
                    decoder.setTargetSize(width, height)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.asImageBitmap()
            }.getOrNull()
        }
    }
    val scroll = rememberScrollState()
    val clear = with(LocalDensity.current) { (HERO_HEIGHT - SHEET_OVERLAP - PwnDimensions.topBarHeight).toPx() }
    val pastPicture by remember(clear) { derivedStateOf { scroll.value >= clear } }
    val band by animateFloatAsState(if (pastPicture) 1f else 0f, PwnMotion.effects(), label = "hero band")
    val bandColor = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().panelVerticalScroll(scroll)) {
                RoutineHero(photo, rows.firstOrNull())
                Column(
                    Modifier.padding(top = HERO_HEIGHT - SHEET_OVERLAP)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = SHEET_OVERLAP, topEnd = SHEET_OVERLAP))
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RoutineHeader(routine, rows, totals, locale)
                    RoutinePlan(blocks, rows.size, imperial, unfolded) { unfolded = if (unfolded == it) -1 else it }
                }
            }
            RoutineActions(rows.isNotEmpty(), onPlay, onLog, onPlan)
        }
        // Fixed over the photo rather than scrolled away with it: the way back and the menu stay where
        // the thumb left them however far down the plan has been read. Once the sheet's edge has risen
        // to it, the row fills with the page, so the buttons never stand over the plan, and it takes
        // the taps on it, so a plan row hidden under it is not opened unseen. Before that it takes
        // none, and a drag that starts on the photo still scrolls.
        Row(
            Modifier.fillMaxWidth()
                .height(PwnDimensions.topBarHeight)
                .then(if (pastPicture) Modifier.takesTaps() else Modifier)
                .drawBehind { drawRect(bandColor, alpha = band) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoutineHeroButton(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.common_back),
                onPicture = !pastPicture,
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            Box {
                var menu by remember { mutableStateOf(false) }
                RoutineHeroButton(
                    Icons.Outlined.MoreHoriz,
                    stringResource(R.string.routines_menu),
                    onPicture = !pastPicture,
                    Modifier.opensMenu(menu) { menu = it },
                ) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Icons.Outlined.Share, stringResource(R.string.achievement_share)) {
                        menu = false
                        onShare()
                    }
                    MenuItem(Icons.Outlined.Edit, stringResource(R.string.common_edit)) {
                        menu = false
                        onEdit()
                    }
                    MenuItem(Icons.Outlined.DeleteOutline, stringResource(R.string.routines_delete)) {
                        menu = false
                        onDelete()
                    }
                }
            }
        }
    }
}

/** Takes every press on it, so whatever lies under it never gets one. */
private fun Modifier.takesTaps(): Modifier =
    pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }

/** The photo across the top, or — for a template without one — its first exercise's drawing on a wash. */
@Composable
private fun RoutineHero(photo: ImageBitmap?, first: RoutineExercise?) {
    Box(
        Modifier.fillMaxWidth().height(HERO_HEIGHT).background(PwnIndigoSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val spec = first?.let { row -> ACTIVITY_CATALOG.firstOrNull { it.code == row.activityCode } }
            if (spec != null) {
                Image(themePainterResource(spec.heroArt()), null, Modifier.size(HERO_ART), colorFilter = washTint())
            } else {
                Icon(Icons.Outlined.FitnessCenter, null, Modifier.size(HERO_ART / 2), tint = PwnMuted)
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** The template's words over its plan: the name, the few words, the chips and what it takes. */
@Composable
private fun RoutineHeader(routine: Routine, rows: List<RoutineExercise>, totals: RoutineTotals, locale: Locale) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                routine.name,
                Modifier.semantics { heading() },
                // Two lines of a long name set close together; any closer and a
                // descender meets the breve of a «Й» on the line under it.
                style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 32.sp),
                fontWeight = FontWeight.Bold,
            )
            if (routine.description.isNotEmpty()) {
                Text(routine.description, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
            }
        }
        RoutineChips(routine.days, rows, locale)
        RoutineTotalsCard(totals)
    }
}

/** The muscles the template works most and the days it is for, as quiet pills under its words. */
@Composable
private fun RoutineChips(days: Int, rows: List<RoutineExercise>, locale: Locale) {
    val muscles = remember(rows) { routineMuscles(rows) }
    if (days == 0 && muscles.isEmpty()) return
    // map rather than joinToString's transform: that lambda is not inline, so stringResource can't be called inside it.
    val muscleNames = muscles.map { stringResource(it.labelRes()) }.joinToString(" · ")
    // "Пн · Ср · Пт", capitalised as the editor's day circles are.
    val dayNames = localWeek(locale)
        .filter { days and it.routineBit() != 0 }
        .joinToString(" · ") { day ->
            day.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, locale)
                .replaceFirstChar { it.titlecase(locale) }
        }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (muscles.isNotEmpty()) InfoChip(Icons.Outlined.AccessibilityNew, muscleNames)
        if (days != 0) InfoChip(Icons.Outlined.CalendarMonth, dayNames)
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String) {
    Row(
        Modifier.clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = PwnMuted)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * What the template takes, side by side: the minutes and the energy, each by its own tinted disc.
 * Tonal, with a 16dp corner: PwnCard's 28 would make a stadium of a card this low.
 */
@Composable
private fun RoutineTotalsCard(totals: RoutineTotals) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(PwnCorners.large), color = PwnSurfaceElevated) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Total(
                Icons.Outlined.Schedule,
                stringResource(R.string.routines_total_time),
                pluralStringResource(R.plurals.count_minutes, totals.minutes, totals.minutes),
            )
            Box(Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(12.dp))
            Total(
                Icons.Outlined.LocalFireDepartment,
                stringResource(R.string.routines_burn),
                "${totals.kcal} ${stringResource(R.string.unit_kcal)}",
            )
        }
    }
}

@Composable
private fun RowScope.Total(icon: ImageVector, label: String, value: String) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        // Ringed in the cards' white: without the ring the pale disc is
        // lost on the tonal card (1.04:1).
        Box(
            Modifier.size(TOTAL_DISC)
                .background(PwnIndigoSoft, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(20.dp), tint = PwnMuted)
        }
        Spacer(Modifier.width(10.dp))
        // One stop for a screen reader: "Total time, 45 min" rather than two.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            // Untracked, so "Сожжёте примерно" keeps to one line.
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
                color = PwnMuted,
            )
            // A step under the 18sp headings.
            Text(value, style = RoutineHeadingStyle.copy(fontSize = 16.sp), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The plan: its heading and how many exercises, then each step on a card of its own with the rests
 * between them. The cards reach 8dp further out than the words over them.
 */
@Composable
private fun RoutinePlan(
    blocks: List<RoutineBlock>,
    count: Int,
    imperial: Boolean,
    unfolded: Int,
    onToggle: (Int) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.routines_plan_title),
                Modifier.weight(1f).semantics { heading() },
                style = RoutineHeadingStyle,
                fontWeight = FontWeight.Bold,
            )
            Text(
                pluralStringResource(R.plurals.routines_exercises, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
            )
        }
        var at = 0
        blocks.forEachIndexed { index, block ->
            PlanStep(index, block, imperial, at, unfolded, onToggle)
            at += block.exercises.size
            if (index < blocks.lastIndex && block.restSeconds > 0) RestLine(block.restSeconds)
        }
    }
}

/**
 * One step of the plan: an exercise on its own, numbered, or a superset's exercises together under
 * its number. [first] is the place of the step's first exercise in the whole plan, which is what
 * [unfolded] and [onToggle] count by.
 */
@Composable
private fun PlanStep(
    number: Int,
    block: RoutineBlock,
    imperial: Boolean,
    first: Int,
    unfolded: Int,
    onToggle: (Int) -> Unit,
) {
    RoutineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = if (block.superset) 4.dp else 0.dp)) {
            if (block.superset) {
                Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SetBadge(number)
                    Spacer(Modifier.width(8.dp))
                    val title = stringResource(R.string.workout_superset)
                    HelpTitle(title, Modifier.weight(1f), MaterialTheme.typography.titleSmall) {
                        Text(stringResource(R.string.workout_superset_explain))
                    }
                }
            }
            block.exercises.forEachIndexed { index, exercise ->
                PlanExercise(
                    number = number.takeUnless { block.superset },
                    exercise = exercise,
                    imperial = imperial,
                    open = unfolded == first + index,
                ) { onToggle(first + index) }
            }
        }
    }
}

/**
 * One exercise of the plan: its number where it is a step of its own, its drawing, its name and a
 * line of what the pill does not say, a pill of the one figure that counts, and — where the exercise
 * has a drawing of how it is done — a chevron that turns as that drawing unfolds under the row.
 */
@Composable
private fun PlanExercise(
    number: Int?,
    exercise: RoutineExercise,
    imperial: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val resources = LocalResources.current
    val spec = ACTIVITY_CATALOG.firstOrNull { it.code == exercise.activityCode }
    val panel = spec?.howTo()
    val detail = planDetail(resources, exercise, spec, imperial)
    val turn by animateFloatAsState(if (open) 90f else 0f, PwnMotion.fastSpatial(), label = "chevron")
    val state = disclosureState(open)
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = panel != null, role = Role.Button, onClick = onToggle)
                // A fold, where there is a drawing to unfold: a screen reader says which way it stands.
                .then(if (panel != null) Modifier.disclosureSemantics(open, state) { onToggle() } else Modifier)
                .heightIn(min = PwnDimensions.minimumTarget)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                SetBadge(number)
                Spacer(Modifier.width(8.dp))
            } else {
                // A superset's exercises stand under its number rather than each taking one.
                Spacer(Modifier.width(PLAN_INDENT))
            }
            Box(
                Modifier.size(width = PLAN_ART_WIDTH, height = PLAN_ART_HEIGHT)
                    .clip(RoundedCornerShape(PwnCorners.small))
                    .background(PwnIndigoSoft),
                contentAlignment = Alignment.Center,
            ) {
                spec?.let {
                    Image(
                        themePainterResource(it.art()),
                        null,
                        Modifier.size(PLAN_ART_HEIGHT - 4.dp),
                        colorFilter = washTint(),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                // A step under the role, so a name like "Ходьба,
                // умеренный темп" keeps to one line beside its pill.
                Text(
                    routineExerciseName(resources, exercise),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail.isNotEmpty()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = PwnMuted)
                }
            }
            FigurePill(exercise)
            // The chevron's room is kept on a row without one, so the pills stand in one column.
            if (panel != null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    null,
                    Modifier.padding(start = 12.dp).rotate(turn),
                    tint = PwnMuted,
                )
            } else {
                Spacer(Modifier.width(CHEVRON_ROOM))
            }
        }
        AnimatedVisibility(
            visible = open && panel != null,
            enter = expandVertically(PwnMotion.spatial()) + fadeIn(PwnMotion.effects()),
            exit = shrinkVertically(PwnMotion.spatial()) + fadeOut(PwnMotion.effects()),
        ) {
            panel?.let { RoutineHowToPanel(it, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) }
        }
    }
}

/**
 * The grey line under an exercise's name. Beside a sets-by-reps pill it stays the whole summary, its
 * "3 × 12" spelled out under the name. Beside a minutes pill the minutes would only
 * say the pill again, so the line keeps the load, or else the first muscles the catalogue names.
 */
private fun planDetail(
    resources: Resources,
    exercise: RoutineExercise,
    spec: ActivitySpec?,
    imperial: Boolean,
): String {
    val summary = routineExerciseSummary(resources, exercise, imperial)
    val sets = routineSets(exercise)
    if (sets.size > 1 || sets.any { it.reps > 0 }) return summary
    return summary.substringAfter(" · ", "").ifEmpty {
        spec?.muscles.orEmpty().take(PLAN_MUSCLES).joinToString(" · ") { resources.getString(it.labelRes()) }
    }
}

/**
 * The figure that counts for an exercise in a pill: sets by reps where it is counted so; sets of
 * seconds where it is counted in sets alone, as the player counts it ("3 × 45 sec"); else its minutes.
 */
@Composable
private fun FigurePill(exercise: RoutineExercise) {
    val resources = LocalResources.current
    val sets = routineSets(exercise)
    val counted = sets.any { it.reps > 0 }
    val figure = when {
        // Reps that change from set to set as their span: "3 × 7–10".
        counted -> {
            val fewest = sets.minOf { it.reps }
            val most = sets.maxOf { it.reps }
            "${sets.size} × ${if (fewest == most) "$most" else "$fewest–$most"}"
        }

        // Timed sets of one length as "3 × 45 sec"; of different ones, the time they hold in all.
        sets.size > 1 && sets.all { it.seconds == sets.first().seconds } ->
            "${sets.size} × ${durationText(resources, sets.first().seconds)}"

        sets.isNotEmpty() -> durationText(resources, sets.sumOf { it.seconds })

        else -> "${exercise.durationMinutes.shown()} ${stringResource(R.string.unit_minutes)}"
    }
    Surface(shape = CircleShape, color = PwnIndigoSoft) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (counted) Icons.Outlined.Autorenew else Icons.Outlined.Schedule,
                null,
                Modifier.size(14.dp),
                tint = PwnMuted,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                figure,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** The rest between two steps of the plan: a quiet line rather than a card of its own. */
@Composable
private fun RestLine(seconds: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp), tint = PwnMuted)
        Spacer(Modifier.width(6.dp))
        Text(
            "${stringResource(R.string.workout_rest)} · ${durationText(LocalResources.current, seconds)}",
            style = MaterialTheme.typography.labelMedium,
            color = PwnMuted,
        )
    }
}

/**
 * Fixed at the foot: Start, filled, for doing the session now; Log, for one
 * already done; and Plan, for a day ahead with a reminder on its morning — the two wide ones a fifth
 * wider than Log. The words step down in size before they would wrap: "Запланировать" is a long
 * word for a third of a phone.
 */
@Composable
private fun RoutineActions(enabled: Boolean, onPlay: () -> Unit, onLog: () -> Unit, onPlan: () -> Unit) {
    val shape = RoundedCornerShape(PwnCorners.large)
    val padding = PaddingValues(8.dp)
    val tonal = routineTonalColors()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val wide = Modifier.weight(WIDE_ACTION).heightIn(min = PwnDimensions.minimumTarget)
        Button(onClick = onPlay, enabled = enabled, modifier = wide, shape = shape, contentPadding = padding) {
            ActionLabel(
                Icons.Filled.PlayArrow,
                stringResource(R.string.common_start),
                iconSize = PwnDimensions.mediumButtonIcon,
            )
        }
        FilledTonalButton(
            onClick = onLog,
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = PwnDimensions.minimumTarget),
            shape = shape,
            colors = tonal,
            contentPadding = padding,
        ) {
            ActionLabel(Icons.Outlined.BarChart, stringResource(R.string.routines_start), muted = enabled)
        }
        FilledTonalButton(
            onClick = onPlan,
            enabled = enabled,
            modifier = wide,
            shape = shape,
            colors = tonal,
            contentPadding = padding,
        ) {
            ActionLabel(Icons.Outlined.CalendarMonth, stringResource(R.string.routines_plan), muted = enabled)
        }
    }
}

/** A foot button's icon and word; [muted] gives the icon a slate tone beside ink words. */
@Composable
private fun ActionLabel(icon: ImageVector, label: String, muted: Boolean = false, iconSize: Dp = 18.dp) {
    Icon(icon, null, Modifier.size(iconSize), tint = if (muted) PwnMuted else LocalContentColor.current)
    Spacer(Modifier.width(6.dp))
    Text(
        label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelLarge,
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
    )
}

/**
 * A template's white card — a step of the plan on its page, a tile or a card in the player: white
 * with a 16dp corner rather than the app's 28dp tinted slab, lifted by a soft shadow cast
 * downwards, the bar's own colour at half strength. At night the shadow
 * is lost on the dark page and the surface's own tone carries the edge.
 */
@Composable
internal fun RoutineCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(PwnCorners.large)
    Surface(
        modifier.dropShadow(shape, ROUTINE_CARD_SHADOW),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        content = content,
    )
}

/**
 * A round button over a template's picture — its photo, or a drawing on the wash: charcoal glass
 * with a faint light rim that keeps it apart from a dark
 * photo, 36dp to see in the 48dp to touch. Off the picture, once the page has scrolled it away, it
 * turns into the app's own tonal back disc.
 */
@Composable
internal fun RoutineHeroButton(
    icon: ImageVector,
    label: String,
    onPicture: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (onPicture) scheme.inverseSurface.copy(alpha = HERO_DISC_ALPHA) else PwnSurfaceElevated,
        PwnMotion.effects(),
        label = "disc",
    )
    val ink by animateColorAsState(
        if (onPicture) scheme.inverseOnSurface else PwnMuted,
        PwnMotion.effects(),
        label = "disc glyph",
    )
    val rim by animateColorAsState(
        scheme.inverseOnSurface.copy(alpha = if (onPicture) HERO_RIM_ALPHA else 0f),
        PwnMotion.effects(),
        label = "disc rim",
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Box(
            Modifier.size(HERO_DISC_SIZE).background(fill, CircleShape).border(1.dp, rim, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(20.dp), tint = ink) }
    }
}

/**
 * An exercise's drawn steps, at the width it is given or at the height the [modifier] caps it to,
 * whichever binds first: the panels are nodpi bitmaps, so their pixels say nothing about dp.
 * Unlabelled, as the workout form's is: the exercise's name is right by it.
 */
@Composable
internal fun RoutineHowToPanel(panel: Int, modifier: Modifier = Modifier) {
    val painter = themePainterResource(panel)
    Image(
        painter,
        contentDescription = null,
        modifier = modifier.aspectRatio(painter.intrinsicSize.width / painter.intrinsicSize.height)
            .clip(RoundedCornerShape(PwnCorners.extraSmall)),
        contentScale = ContentScale.Fit,
    )
}

/**
 * Multiplies a drawing into the pale wash it stands on, so the ground some pose drawings keep under
 * the body reads as the wash's own shade rather than a cream patch. Transparent pixels come out as
 * the wash itself, the colour of the box behind them. Off on a dark wash — night, or a dark accent
 * someone picked — where it would sink the figure.
 */
@Composable
internal fun washTint(): ColorFilter? =
    PwnIndigoSoft.takeIf { it.luminance() >= LIGHT_WASH }?.let { ColorFilter.tint(it, BlendMode.Multiply) }

/** The quiet buttons at the foot of both pages: the chips' pale fill with ink on it, kept when disabled. */
@Composable
internal fun routineTonalColors(): ButtonColors = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = PwnInk,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
)

/** A heading on a card or over the plan: 18sp. */
internal val RoutineHeadingStyle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)

private val HERO_HEIGHT = 268.dp

/** The sheet's corner, and as much of the photo as it rises over, so the photo fills the corners. */
private val SHEET_OVERLAP = PwnCorners.largeIncreased
private val HERO_ART = 180.dp

/** A total's disc: a 40dp tinted fill, and the 2dp ring drawn inside this round it. */
private val TOTAL_DISC = 44.dp

/** The plan's drawing beside a name. */
private val PLAN_ART_WIDTH = PwnDimensions.glyphSize
private val PLAN_ART_HEIGHT = 44.dp

/** A superset exercise's indent: the 26dp number disc and its gap. */
private val PLAN_INDENT = 34.dp

/** The chevron and its gap, kept on a row that has none. */
private val CHEVRON_ROOM = 36.dp

/** How many muscles the line under a timed exercise names. */
private const val PLAN_MUSCLES = 2

/** Start's and Plan's share of the foot against Log's one. */
private const val WIDE_ACTION = 1.2f

private val HERO_DISC_SIZE = 36.dp
private const val HERO_DISC_ALPHA = 0.85f
private const val HERO_RIM_ALPHA = 0.12f

/** A wash at least this light has a drawing multiplied into it. */
private const val LIGHT_WASH = 0.5f

/** The plan rows' lift: the bar's shadow colour at half strength, softer, and cast down rather than up. */
private val ROUTINE_CARD_SHADOW = Shadow(
    radius = 10.dp,
    color = BAR_SHADOW.color,
    offset = DpOffset(0.dp, 2.dp),
    alpha = 0.5f,
)
