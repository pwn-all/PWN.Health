package com.pwnall.health

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RamenDining
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Which of the two things is being logged, and so which recogniser a photograph of it belongs to.
 *
 * "Home cooked" and "packaged" is the one distinction that actually changes what the app can do
 * with a picture: a plate goes to the dish classifier, a packet to the label reader. It is a
 * routing choice and nothing more — no journal column records it, because what a saved row needs
 * to say about itself ("Fage, 0%", "Fruit") is the catalogue's own detail line, which already
 * exists.
 */
internal enum class FoodKind {
    MEAL,
    PRODUCT,
    ;

    /**
     * The half of the catalogue this door opens on.
     *
     * Landing both doors on the same five thousand rows would make the choice decorative: a
     * person who said "meal" would still scroll past raw beef to reach a Big Mac. The catalogue
     * already sorts itself along exactly this line — [FoodCategory.FAST_FOOD] and
     * [FoodCategory.DISH] are the fifteen hundred rows somebody cooked or a restaurant sold
     * (Big Mac, borscht, lasagne), and the other ten groups are the thirty-five hundred a shop
     * sells you to cook with (chicken breast, oats, milk). So the door is the filter.
     */
    val groups: Set<FoodCategory>
        get() = when (this) {
            MEAL -> MEAL_GROUPS
            PRODUCT -> FoodCategory.entries.toSet() - MEAL_GROUPS
        }
}

/** Cooked, composed or sold ready to eat. Everything else in the catalogue is an ingredient. */
private val MEAL_GROUPS = setOf(FoodCategory.FAST_FOOD, FoodCategory.DISH)

/** Which page of the food flow is on screen. */
internal enum class FoodStep { HUB, SEARCH, DISH, LABEL, EDIT, DONE }

/**
 * The two doors, side by side in one card with a hairline between them.
 *
 * One card cut in half reads as one choice with two answers; two separate bordered cards with a gap
 * down the middle would read as two unrelated choices. The tiles carry no camera: a lens hung in
 * each corner would ask for the kind twice over — once here and again in the recogniser — and leave
 * no room for the icon to sit beside its title. Photographing lives on the search field, where the
 * kind is already decided.
 *
 * The halves are a matched pair, a plate and a carton: two doors made of the same stuff read as one
 * choice with two answers, which is what they are.
 */
@Composable
internal fun FoodKindTiles(onKind: (FoodKind) -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        // Intrinsic height so the divider is exactly as tall as the taller half rather than
        // stretching the card or falling short of it.
        Row(Modifier.height(IntrinsicSize.Min)) {
            FoodKindTile(
                art = R.drawable.ic_art_dish,
                title = stringResource(R.string.photo_capture_meal),
                hint = stringResource(R.string.food_kind_meal_hint),
                modifier = Modifier.weight(1f),
                onClick = { onKind(FoodKind.MEAL) },
            )
            VerticalDivider(Modifier.padding(vertical = 14.dp), color = PwnBorder)
            FoodKindTile(
                art = R.drawable.ic_art_product,
                title = stringResource(R.string.photo_capture_product),
                hint = stringResource(R.string.food_kind_product_hint),
                modifier = Modifier.weight(1f),
                onClick = { onKind(FoodKind.PRODUCT) },
            )
        }
    }
}

@Composable
private fun FoodKindTile(
    art: Int,
    title: String,
    hint: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        // A drawing needs more room than a 24 dp glyph, and it comes out of the padding rather
        // than out of the title beside it: the two halves still share the row equally.
        modifier.fillMaxHeight().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(themePainterResource(art), null, Modifier.size(FOOD_KIND_PICTURE))
        // Two lines each rather than one: half a card is narrow, and at a large font a single line
        // would cut the title itself down to its first letters. The row sits in an intrinsic-height card,
        // so the other half grows with whichever wraps.
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Everything a photograph needs, from the tap that opens a camera to the sheet that reads what came
 * back — held in one place, so the whole flow shares one copy of it.
 *
 * Both cameras are the system camera app and both galleries the photo picker, so neither needs a
 * permission. The product photograph passes through [LabelCropDialog] first, where the printed
 * table is marked out by the finger that photographed it, and is then handed to the flow's own
 * label page ([ProductLabelPage]) through [onLabelPhoto]; the dish goes straight to the classifier,
 * which wants the whole plate. Every file is one fixed name in the cache, overwritten per shot and
 * deleted once whatever read it is done: the photograph is an input to a recogniser, not
 * something this app keeps. One whose reader never came back for it — the app closed behind the
 * camera and opened somewhere else — goes at a later start ([sweepLostCaptures]).
 */
@Composable
internal fun rememberFoodPhotography(
    onDish: (String, String, List<DishItem>) -> Unit,
    onLabelPhoto: (Uri) -> Unit,
): (FoodKind) -> Unit {
    val context = LocalContext.current
    // Saved rather than remembered, and as a string, the way the editor keeps the label page's
    // photograph. Turning the phone, a dark theme coming on or a second window rebuilds the
    // activity, and a photograph that was only remembered would take the dish sheet or the crop screen
    // off the display with it and leave the camera's file in the cache with nothing to delete it.
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val photo = shot?.toUri()
    // Chosen at the tap and read when the photograph arrives. Saved rather than remembered: the
    // camera is another activity and the process can die behind it — the result is redelivered, so
    // a packet must not come back as a plate.
    var kind by rememberSaveable { mutableStateOf(FoodKind.MEAL) }
    val capture = remember(context) {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.photos",
            File(sharedCacheDir(context), MEAL_PHOTO_FILE),
        )
    }
    val clearPhoto: () -> Unit = {
        clearFoodPhoto(context, photo)
        shot = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) shot = capture.toString()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        if (picked != null) shot = picked.toString()
    }

    photo?.let { taken ->
        if (kind == FoodKind.MEAL) {
            PhotoDishSheet(
                photo = taken,
                onDismiss = clearPhoto,
                onApply = { title, subtitle, items ->
                    clearPhoto()
                    onDish(title, subtitle, items)
                },
            )
        } else {
            // The table is marked out first, then read — on the flow's own page, which takes the
            // photograph over from here, cache file and all: the packet decides where it printed
            // the table, and the page decides what becomes of the reading.
            LabelCropDialog(
                photo = taken,
                onDismiss = clearPhoto,
                onCrop = { chosen ->
                    shot = null
                    onLabelPhoto(chosen)
                },
            )
        }
    }

    return { chosen ->
        kind = chosen
        // A tablet without a camera app throws on launch; the gallery is the same feature, one step
        // later, so it stands in rather than the tap doing nothing.
        runCatching { camera.launch(capture) }.onFailure { _ ->
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

/**
 * The first page: how this food is going to be named, and what has been named already.
 *
 * Everything here is a door — the two kinds, the dish builder, the catalogue, a favourite, a
 * recent — and every one of them ends in the same edit form, except the vitamin table's, which
 * leaves the meal for a page of its own. The page keeps no state of its own.
 */
@Composable
internal fun FoodHubPage(
    modifier: Modifier,
    selected: FoodState?,
    favorites: List<FavoriteFood>,
    recent: List<JournalEntry>,
    onKind: (FoodKind) -> Unit,
    onFavorite: (FavoriteFood) -> Unit,
    onRecent: (JournalEntry) -> Unit,
    onEditSelected: () -> Unit,
    onClearSelected: () -> Unit,
) {
    // The hub is the one step of the meal flow that is NOT a panel, so the bottom bar stands over
    // it: without the inset the selected-item card at the foot — its Edit link and its clear
    // button — goes under the pill in landscape or at a large font scale.
    Column(
        modifier.fillMaxSize()
            .panelVerticalScroll(rememberScrollState())
            .padding(20.dp)
            .padding(bottom = LocalBottomBarInset.current),
    ) {
        Text(
            stringResource(R.string.editor_food_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(18.dp))
        // The two doors are the whole of the choice. A catalogue row under them would offer the
        // same five thousand rows both of these lead to, unsorted — a third door onto the two
        // rooms already standing open. Photographing is on the search field of whichever room
        // you walk into, where the kind is settled and the camera knows what it is reading.
        FoodKindTiles(onKind = onKind)
        if (favorites.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            FoodSectionHeader(stringResource(R.string.food_favourites))
            Spacer(Modifier.height(10.dp))
            FoodChipRow {
                favorites.forEach { favorite ->
                    FoodChip(
                        label = favorite.title,
                        art = rememberFoodArt(favorite.title, favorite.key),
                        onClick = { onFavorite(favorite) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        FoodSectionHeader(stringResource(R.string.editor_recent))
        Spacer(Modifier.height(10.dp))
        if (recent.isEmpty()) {
            RecentPlaceholder()
        } else {
            FoodChipRow {
                recent.forEach { meal ->
                    // The recents wear the food, not a clock: a row of identical clock
                    // faces says only "these are recent", which the heading above already said.
                    FoodChip(
                        label = meal.title,
                        art = rememberFoodArt(meal.title),
                        onClick = { onRecent(meal) },
                    )
                }
            }
        }
        // A way out of the meal rather than into it, so it stands under the doors in and the recents,
        // in the catalogue's own door row: what the meals picked here add up to against the day.
        LocalOpenVitamins.current?.let { openVitamins ->
            Spacer(Modifier.height(24.dp))
            FoodRowGroup {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = openVitamins),
                    color = Color.Transparent,
                ) {
                    DoorRowContent(
                        icon = Icons.Outlined.Eco,
                        title = stringResource(R.string.report_part_micronutrients),
                        hint = stringResource(R.string.vitamins_door_body),
                    )
                }
            }
        }
        // Only once something has been named: an empty card headed "selected item" is a hole in the
        // page, and there is nothing to edit behind it.
        if (selected != null && selected.title.isNotBlank()) {
            // The rule under the recents. It is the one place on this page where the subject
            // changes — everything above is a way in, everything below is what came back — and a
            // gap alone would not say so.
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = PwnBorder)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FoodSectionHeader(stringResource(R.string.food_selected), Modifier.weight(1f))
                // A bare Text is clickable over exactly its own layout box, and a 14sp line lays
                // out 20dp tall — 42% of the 48dp minimum, and short even of WCAG 2.2's weaker
                // 24dp. The padding sits inside the click node, so the word keeps its place in its
                // own box while the tap area becomes 48dp tall and about 54dp wide.
                Text(
                    stringResource(R.string.common_edit),
                    Modifier.clickable(role = Role.Button, onClick = onEditSelected)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    color = PwnAccentInk,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(10.dp))
            SelectedFoodCard(selected, onEditSelected, onClearSelected)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Where the recents will be once there are some.
 *
 * A holding shape rather than a designed empty state. It keeps the heading above it from pointing
 * at nothing and holds a fixed height, so nothing below it moves when the first recent arrives.
 */
@Composable
private fun RecentPlaceholder() {
    Box(
        Modifier.fillMaxWidth()
            .height(RECENT_PLACEHOLDER_HEIGHT)
            // The app's card corner, so the holding shape matches the cards on the page around it.
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(PwnCorners.extraLarge)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.food_recent_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * What came back from whichever door was opened.
 *
 * Given a shadow where the cards above it lie flat, on purpose: those are doors, all alike, and
 * this is the one thing on the page that is a result. Three lines stand against the picture, so
 * the energy joins the name and the detail as a line rather than sitting off to the right as a
 * figure — where, next to a title, it would read as the card's subject rather than its size.
 */
@Composable
internal fun SelectedFoodCard(selected: FoodState, onClick: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(SELECTED_CARD_RADIUS)
    // The clickable Surface rather than a clickable modifier handed to a plain one: that would run
    // ahead of the Surface's own clip, so the press would draw a rectangle past the rounded corners.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pwnShadow(shape, SELECTED_CARD_SHADOW),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            PwnGlyph(
                rememberFoodArt(selected.title),
                Icons.Outlined.RamenDining,
                PwnIndigoSoft,
                null,
                Modifier.size(56.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selected.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selected.subtitle.isNotBlank()) {
                    Text(
                        selected.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.entry_energy, selected.portionCalories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            }
            IconButton(onClick = onClear, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.food_recent_clear),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The picture for a food somebody logged, kept the same wherever that food appears.
 *
 * A chip that guessed its plate from the name while the catalogue list beside it reads the row's
 * own `group` column would wear two different pictures on two screens — "Big Mac" a burger in the
 * results and a nothing-in-particular plate as a recent. So the guess is a fallback, not the
 * first answer: a favourite files itself under its catalogue id where it has one, and
 * a name that is a catalogue row verbatim finds itself back through [catalogPhraseMatch].
 *
 * Whichever end answers, the picture asked for is the food's own — an egg, a banana, a slice of
 * bread — and the category's plate only where nothing was drawn for that food: [TopFood.art] and
 * [foodArt] hold that rule between them, so a chip and a catalogue row still agree.
 *
 * The id side is a map lookup; the name side still walks the catalogue, so the answer is
 * remembered — a rail of chips would otherwise walk five thousand rows on every recomposition.
 * Both read a catalogue the app's window loaded as it opened ([warmFoodCatalog]): asked for here
 * first, it would be a megabyte parsed and every row's phrases folded inside the hub's first frame.
 */
@Composable
internal fun rememberFoodArt(title: String, catalogId: String? = null): Int = remember(title, catalogId) {
    val known = catalogId?.let(TOP_FOODS_BY_ID::get) ?: catalogPhraseMatch(title)
    known?.art() ?: foodArt(title)
}

/** The scrolling rail the favourites and the recents both ride on. */
@Composable
private fun FoodChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** One recent or kept food, wearing its own picture. */
@Composable
private fun FoodChip(label: String, art: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FOOD_CHIP_RADIUS)
    // The clickable Surface, so the press is clipped to the chip's corners, and flat: the outline
    // is the chip's boundary, the way a Material outlined chip has no shadow of its own.
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        // The row rule's hairline as this chip's only edge would not say there is anything here
        // to tap; the control edge reads 4.20:1 against the white fill and 4.13:1 against the
        // page, over the 3:1 that boundary needs.
        border = BorderStroke(1.dp, PwnControlEdge),
    ) {
        Row(
            // 13dp above and below the 22dp picture: 13 + 22 + 13 = 48dp, the Android minimum.
            // The same 48dp the category chips reach with 14dp around their 20dp glyph — one
            // height for both chips.
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(themePainterResource(art), null, Modifier.size(22.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FoodSectionHeader(text: String, modifier: Modifier = Modifier) {
    // A heading to a screen reader as well, so a page of sixty catalogue rows can be crossed by
    // section rather than row by row.
    Text(
        text,
        modifier.semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A door's contents, with no box of its own. */
@Composable
private fun DoorRowContent(icon: ImageVector, title: String, hint: String) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = PwnMuted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = PwnMuted,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The catalogue as a page rather than a dialog.
 *
 * Not an `AlertDialog`, which is the wrong shape for the one screen a person spends the longest
 * on: a window that floats above the form cannot be scrolled comfortably, cannot show recents and
 * favourites beside the results, and reads as an interruption of the thing it is actually the main
 * step of. The entry editor keeps a dialog form of the catalogue for adding one row from its card.
 *
 * Everything on it is grouped rather than loose. Rows lying straight on the page with a rule under
 * each would run the whole width of the screen and make three sections look like one long list;
 * each run of rows sits in its own card with the rules inset to start where the text does, so the
 * eye can see where a group begins and ends.
 */
@Composable
internal fun FoodSearchPage(
    modifier: Modifier,
    kind: FoodKind,
    favorites: List<FavoriteFood>,
    recent: List<JournalEntry>,
    onBack: () -> Unit,
    onPhoto: (FoodKind) -> Unit,
    onBuildDish: () -> Unit,
    onPick: (TopFood) -> Unit,
    onFavorite: (FavoriteFood) -> Unit,
    onRecent: (JournalEntry) -> Unit,
    building: Boolean = false,
    picked: List<String> = emptyList(),
    onToggle: (TopFood) -> Unit = {},
    onContinue: () -> Unit = {},
    savedDishes: List<SavedDish> = emptyList(),
    onSavedDish: (SavedDish) -> Unit = {},
    onDeleteDish: (SavedDish) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    // "Clear" hides the list, it does not delete anything. Recents are read straight off the food
    // diary, so wiping them would mean deleting meals somebody logged — which is not what a person
    // tidying a search page is asking for.
    var recentHidden by rememberSaveable { mutableStateOf(false) }
    var groupName by rememberSaveable { mutableStateOf("") }
    val group = remember(groupName) { FoodCategory.entries.firstOrNull { it.name == groupName } }
    val groups = remember(kind) { kind.groups }
    val results = rememberFoodSearchAnswer(query, group, CATALOG_PAGE_RESULT_LIMIT, groups)
    // Only the groups this door actually carries. Offering "Dairy" on the meal page would be a
    // chip that empties the list, and the meal page has two groups to choose between anyway.
    val rail = remember(groups) { TOP_FOOD_GROUPS.filter { it in groups } }
    val searching = query.isNotBlank() || group != null

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(4.dp))
            // The picture off the door that was just opened. Both rooms are the same list of rows
            // and the same field above them, so the word alone would carry the whole of which
            // half of the catalogue is underneath — and the drawing says it faster than the word.
            Image(
                themePainterResource(if (kind == FoodKind.MEAL) R.drawable.ic_art_dish else R.drawable.ic_art_product),
                null,
                Modifier.size(FOOD_KIND_MARK),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    if (kind == FoodKind.MEAL) R.string.photo_capture_meal else R.string.photo_capture_product,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        // A dish is several products, so the builder belongs on the products page and nowhere else.
        if (kind == FoodKind.PRODUCT) {
            val joined = RoundedCornerShape(
                topStart = SEARCH_FIELD_RADIUS,
                topEnd = SEARCH_FIELD_RADIUS,
                bottomStart = DOOR_ROW_RADIUS,
                bottomEnd = DOOR_ROW_RADIUS,
            )
            Surface(
                modifier = Modifier.fillMaxWidth().pwnShadow(joined),
                shape = joined,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column {
                    SearchFieldRow(query, { query = it }, kind) { onPhoto(kind) }
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = PwnBorder)
                    if (building) {
                        DishModeBanner(picked.size)
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onBuildDish),
                            color = Color.Transparent,
                        ) {
                            DoorRowContent(
                                icon = Icons.Outlined.Add,
                                title = stringResource(R.string.dish_build_open),
                                hint = stringResource(R.string.food_build_dish_hint),
                            )
                        }
                    }
                }
            }
        } else {
            FoodSearchField(
                query = query,
                onQuery = { query = it },
                kind = kind,
                onPhoto = { onPhoto(kind) },
            )
        }
        // The list scrolls up to this edge; flush against the card it would run into the card's shadow.
        Spacer(Modifier.height(4.dp))
        Column(Modifier.fillMaxWidth().weight(1f).panelVerticalScroll(rememberScrollState())) {
            // Recents and favourites are what the page is for when nothing has been typed; once a
            // query narrows the catalogue they would only push the answer off the screen.
            if (!searching && !building) {
                if (favorites.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    FoodSectionHeader(stringResource(R.string.food_favourites))
                    Spacer(Modifier.height(10.dp))
                    FoodRowGroup {
                        favorites.forEachIndexed { index, favorite ->
                            if (index > 0) FoodRowRule()
                            FoodListRow(
                                title = favorite.title,
                                detail = favorite.subtitle,
                                art = rememberFoodArt(favorite.title, favorite.key),
                                trailing = Icons.Outlined.Favorite,
                                onClick = { onFavorite(favorite) },
                            )
                        }
                    }
                }
                if (recent.isNotEmpty() && !recentHidden) {
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FoodSectionHeader(stringResource(R.string.editor_recent), Modifier.weight(1f))
                        // Same 20dp-tall hit box as the hub's "edit" link, and the same remedy:
                        // padding inside the click node lifts it to 48dp without moving the word
                        // within its own box.
                        Text(
                            stringResource(R.string.food_recent_clear),
                            Modifier.clickable(role = Role.Button) { recentHidden = true }
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            color = PwnAccentInk,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    FoodRowGroup {
                        recent.forEachIndexed { index, meal ->
                            if (index > 0) FoodRowRule()
                            FoodListRow(
                                title = meal.title,
                                detail = meal.subtitle,
                                art = rememberFoodArt(meal.title),
                                trailing = Icons.Outlined.Schedule,
                                onClick = { onRecent(meal) },
                            )
                        }
                    }
                }
            }
            // A dish somebody built themselves outranks the catalogue: it is the one row on this
            // page they have already decided about. Hidden while assembling another one, where the
            // question is which products go in, not which finished plate to have.
            val matchingDishes = remember(savedDishes, query) {
                savedDishes.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            }
            if (!building && matchingDishes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                FoodSectionHeader(stringResource(R.string.dish_saved_section))
                Spacer(Modifier.height(10.dp))
                FoodRowGroup {
                    matchingDishes.forEachIndexed { index, dish ->
                        if (index > 0) FoodRowRule()
                        SavedDishRow(dish, { onSavedDish(dish) }, { onDeleteDish(dish) })
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            FoodSectionHeader(stringResource(R.string.food_browse_categories))
            Spacer(Modifier.height(10.dp))
            // One choice across the rail: each chip says whether it is the filter applied below.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // "Popular" is not a category the catalogue carries — it is the catalogue's own
                // order, curated first and USDA-ranked after, which is exactly what an empty query
                // returns. So the chip clears the filter rather than applying one.
                CategoryChip(
                    label = stringResource(R.string.food_group_popular),
                    icon = Icons.Outlined.StarOutline,
                    selected = group == null,
                    onClick = { groupName = "" },
                )
                rail.forEach { category ->
                    CategoryChip(
                        label = foodGroupLabel(category),
                        art = category.art(),
                        selected = group == category,
                        onClick = { groupName = if (group == category) "" else category.name },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            FoodSectionHeader(
                if (searching) {
                    stringResource(
                        R.string.food_search_results,
                    )
                } else {
                    stringResource(R.string.food_group_popular)
                },
            )
            Spacer(Modifier.height(10.dp))
            if (results?.isEmpty() == true) {
                Text(
                    stringResource(R.string.food_catalog_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                )
            } else if (results != null) {
                FoodRowGroup {
                    results.forEachIndexed { index, food ->
                        if (index > 0) FoodRowRule()
                        if (building) {
                            DishPickRow(food, food.id in picked) { onToggle(food) }
                        } else {
                            CatalogFoodRow(food) { onPick(food) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        if (building) {
            Button(
                onClick = onContinue,
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
                shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
            ) {
                Text(stringResource(R.string.common_continue), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** What mode this page is in: a dish being assembled, and how far along it is. */
@Composable
private fun DishModeBanner(count: Int) {
    Row(
        Modifier.fillMaxWidth().background(PwnIndigoSoft).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = PwnMuted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.dish_build_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = PwnIndigoDark,
            )
            // What to do while nothing has been ticked, and how far along once something has:
            // the instruction is only worth its line until it has been followed.
            Text(
                if (count == 0) {
                    stringResource(R.string.dish_build_hint)
                } else {
                    stringResource(R.string.dish_build_chosen, count)
                },
                style = MaterialTheme.typography.bodySmall,
                // The accent as ink on its own pale container measures 4.061:1, short of the 4.5:1
                // this 12sp line needs; deepened it is 4.79:1 on that container by day and 5.64:1
                // on the night one. The title above keeps the container's own on-colour, 7.563:1,
                // so the two lines stay a heading and its hint rather than one block of ink.
                color = PwnAccentInk,
            )
        }
    }
}

/** A catalogue row while a dish is being assembled: the same row, with a box to tick. */
@Composable
private fun DishPickRow(food: TopFood, checked: Boolean, onToggle: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    // Toggleable, not clickable: a clickable row is announced as a button with no state, and the
    // tick beside it would be a second focusable node carrying the state with no name — two nodes for
    // one control, neither of which says both halves. One toggleable node speaks the food and
    // whether it is chosen, and the tick becomes the drawing of that answer rather than a control.
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(food.art()), null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            // Two lines each: catalogue variants share their first words ("Chicken breast, grilled"
            // and "…, boiled"), and at a large font one line would cut them down to the same stem.
            Text(
                food.name.localized(locale),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                food.detail.localized(locale),
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The row owns the toggle, so the box itself must not be a second stop for a screen
        // reader — but a Checkbox handed no callback also drops Material's own 48dp floor and
        // shrinks from 48dp to 24dp, sliding the tick 12dp toward the edge. The floor is asked
        // for by name so the tick stays where it was drawn.
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.minimumInteractiveComponentSize(),
        )
    }
}

/**
 * The field, and the lens beside it.
 *
 * The scan button is the only camera on this page: by the time anyone is here the kind has already
 * been chosen at the hub, so the button knows whether it is looking at a plate or at a printed table
 * and does not have to ask. It stands at the one place where typing a name has just failed to find
 * the thing.
 */
@Composable
internal fun FoodSearchField(
    query: String,
    onQuery: (String) -> Unit,
    kind: FoodKind,
    onPhoto: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(SEARCH_FIELD_RADIUS)
    Surface(
        modifier = Modifier.fillMaxWidth().pwnShadow(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        SearchFieldRow(query, onQuery, kind, onPhoto)
    }
}

/** The field's contents, with no box of its own — so a joined container can hold them instead. */
@Composable
private fun SearchFieldRow(
    query: String,
    onQuery: (String) -> Unit,
    kind: FoodKind,
    onPhoto: (() -> Unit)?,
) {
    Row(
        Modifier.padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = PwnMuted, modifier = Modifier.size(22.dp))
        // The bare field rather than an OutlinedTextField: the card around it is already
        // drawing the box, and a second one inside it would double the edge on this screen.
        val text = rememberSyncedText(query, onQuery)
        BasicTextField(
            state = text.state,
            inputTransformation = text.handOff,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 18.dp),
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = PwnInk),
            cursorBrush = SolidColor(PwnIndigo),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            decorator = TextFieldDecorator { field ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.food_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                field()
            },
        )
        // The painted bubble stays 38dp; what grows is what answers a finger. Tapped as a bare Box
        // the target would be 38 x 38dp against the 48 x 48dp minimum — 5dp short on every edge,
        // 63% of the area — because a raw clickable gets
        // none of the floor a Material control carries. The IconButton around it is that floor,
        // and the row is 56dp tall already, so nothing above or below it moves.
        // No camera where the field stands without one: a chat's record card picks from the catalogue alone.
        onPhoto ?: return@Row
        IconButton(
            onClick = onPhoto,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
        ) {
            Box(
                Modifier.size(38.dp).background(PwnIndigoSoft, RoundedCornerShape(PwnCorners.medium)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PhotoCamera,
                    contentDescription = stringResource(
                        if (kind == FoodKind.PRODUCT) {
                            R.string.food_photograph_product
                        } else {
                            R.string.food_photograph_meal
                        },
                    ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A run of rows inside one card.
 *
 * The app's own card, tonal and flat: a group of rows is a container rather than a control, and a
 * shadow around every group would make the page a stack of raised blocks.
 */
@Composable
private fun FoodRowGroup(content: @Composable () -> Unit) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

/** The rule between two rows of a group: inset to the row's own padding, crossing under the art. */
@Composable
private fun FoodRowRule() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = PwnBorder)
}

/**
 * One filter, drawn as a card rather than a Material chip.
 *
 * A tick when it is on is a checkbox's word for it — and with thirteen groups on one rail a tick
 * would make the selected chip a different width from the rest, so the rail would shift every time
 * somebody changed their mind. Filling it instead says the same thing without
 * moving anything, and leaves the leading picture to say which group it is. The fill is the solid
 * accent, so the chosen chip is the most prominent one on the rail rather than a pale blob a
 * step off the page, and the selectable Surface says the same to a screen reader.
 */
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    art: Int? = null,
) {
    val shape = RoundedCornerShape(FOOD_CHIP_RADIUS)
    val scheme = MaterialTheme.colorScheme
    // The selectable Surface: it carries the chosen state to a screen reader, and it clips the
    // press to the chip's corners, which a clickable handed in ahead of a plain Surface does not.
    Surface(
        selected = selected,
        onClick = onClick,
        shape = shape,
        // The accent reads 6.32:1 against the page by day and 10.61:1 at night.
        color = if (selected) scheme.primary else scheme.surface,
        // An unselected chip is white on a near-white page, so its hairline is the whole of what
        // says it is a control: the control edge reads 4.20:1 against the fill and 4.13:1 against
        // the page, over the 3:1 a boundary needs.
        border = if (selected) null else BorderStroke(1.dp, PwnControlEdge),
    ) {
        Row(
            // 14dp above and below the 20dp glyph: 14 + 20 + 14 = 48dp, the Android minimum.
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                art != null -> {
                    // On the chosen chip's accent fill the drawing turns to the fill's own ink, the
                    // way its label does; resting, it wears the app's quiet icon set.
                    val onFill = ThemeIconColors(
                        ink = scheme.onPrimary,
                        mid = lerp(scheme.onPrimary, scheme.primary, ON_FILL_MID_MIX),
                        light = scheme.primary,
                        line = scheme.onPrimary,
                    )
                    CompositionLocalProvider(
                        LocalThemeIconColors provides if (selected) onFill else LocalThemeIconColors.current,
                    ) { Image(themePainterResource(art), null, Modifier.size(20.dp)) }
                }

                icon != null -> Icon(
                    icon,
                    null,
                    Modifier.size(20.dp),
                    tint = if (selected) scheme.onPrimary else PwnMuted,
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                // The accent's own on-colour on the chosen fill, 6.44:1 by day and 10.61:1 at
                // night; the muted ink on the surface of the others, 5.71:1 and 7.87:1.
                color = if (selected) scheme.onPrimary else PwnMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A row of the search page: its picture, a name, what distinguishes it, and why it is on the list. */
@Composable
private fun FoodListRow(
    title: String,
    detail: String,
    art: Int,
    trailing: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(art), null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            // Two lines each, as the dish picker allows: a name cut to one line at a large font can
            // leave two different foods reading the same.
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PwnMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(trailing, contentDescription = null, tint = PwnMuted, modifier = Modifier.size(20.dp))
    }
}

/**
 * The last page: what was written down, and the two things worth doing next.
 *
 * The row is already saved by the time this appears — "Add another" would otherwise have to decide
 * what to do with the meal behind it, and the sentence at the top says "logged" in the past tense
 * because that is true.
 */
@Composable
internal fun FoodSuccessPage(
    modifier: Modifier,
    state: FoodState,
    onAddAnother: () -> Unit,
    onDone: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(20.dp)) {
        Box(Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onDone,
                modifier = Modifier.align(Alignment.TopEnd),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = PwnIndigoSoft,
                    contentColor = PwnIndigoDark,
                ),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_done), tint = PwnMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(SUCCESS_BADGE_BOX), contentAlignment = Alignment.Center) {
            WorkoutSalute(Modifier.fillMaxWidth().height(SUCCESS_BADGE_BOX), running = shown)
            // Fully qualified: the enclosing Column would otherwise pick the ColumnScope
            // overload, which cannot be called from inside this Box.
            androidx.compose.animation.AnimatedVisibility(
                visible = shown,
                enter = scaleIn(spring(dampingRatio = 0.55f)) + fadeIn(),
            ) {
                // The same rosette the workout summary ends on, and for the same reason: one app,
                // one mark for "it is written down". It also brings its own disc and fills it, so
                // the sentence under it reads as this badge's caption — a stock tick at half the
                // width of the circle behind it would leave a ring of empty green between the two.
                Image(themePainterResource(R.drawable.ic_art_done), null, Modifier.size(SUCCESS_BADGE_ART))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.food_added),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PwnGreen,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.food_added_body, state.title),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        PwnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PwnGlyph(
                        rememberFoodArt(state.title),
                        Icons.Outlined.RamenDining,
                        PwnIndigoSoft,
                        null,
                        Modifier.size(44.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.subtitle.isNotBlank()) {
                            Text(
                                state.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = PwnMuted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            grams(state.servingG),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.photo_dish_portion),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = PwnBorder)
                Spacer(Modifier.height(8.dp))
                FoodSummaryLine(
                    stringResource(R.string.editor_calories),
                    stringResource(R.string.entry_energy, state.portionCalories),
                )
                FoodSummaryLine(stringResource(R.string.editor_protein), grams(state.portionProtein))
                FoodSummaryLine(stringResource(R.string.editor_fat), grams(state.portionFat))
                FoodSummaryLine(stringResource(R.string.editor_carbs), grams(state.portionCarbs))
                ExtraNutrient.entries.forEach { extra ->
                    val value = state.portionExtra(extra)
                    if (value != null && value > 0.0) {
                        FoodSummaryLine(
                            stringResource(extra.labelRes),
                            "${value.shown()} ${stringResource(extra.unitRes)}",
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAddAnother,
            modifier = Modifier.fillMaxWidth().heightIn(min = ACTION_HEIGHT),
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.food_add_another), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            RingedIcon(Icons.Outlined.Add, MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().heightIn(min = ACTION_HEIGHT),
            shape = CircleShape,
        ) {
            Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            RingedIcon(Icons.Outlined.Check, PwnIndigo)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The mark beside a button's word: the glyph, ringed, in the button's own colour.
 *
 * Beside it and not at the far end of the row: the mark pinned to the right edge and the word to
 * the left would read as two controls in one capsule on a phone this wide; the label
 * and its mark travel together in the middle instead, which is where a button's content belongs.
 */
@Composable
private fun RingedIcon(icon: ImageVector, tint: Color) {
    Box(
        Modifier.size(26.dp).border(1.5.dp, tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

/** A weight the way every other figure on this page reads it: the number, then its unit. */
@Composable
private fun grams(value: Double): String = "${value.shown()} ${stringResource(R.string.unit_grams)}"

@Composable
private fun FoodSummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** The heart on the edit page: filled when this food is already kept, outlined when it is not. */
@Composable
internal fun FavoriteToggle(marked: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            if (marked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(
                if (marked) R.string.food_favourite_remove else R.string.food_favourite_add,
            ),
            tint = if (marked) PwnIndigo else PwnMuted,
        )
    }
}

/**
 * What a favourite is filed under: the catalogue's id where there is one, and the folded name
 * otherwise, so the same food marked twice replaces itself instead of stacking up.
 *
 * The name stands behind [FAVORITE_NAME_PREFIX], where no id can. Bare, the two would share one set
 * of keys, and hundreds of ids are one lowercase word: a packet read and named "Kefir" would be filed
 * as "kefir", which is the catalogue's own row. Whichever of the two was kept second would replace
 * the first, the label page would call a packet kept that never was, and the kept packet would open
 * in the form as the catalogue's kefir, portion chips and all.
 */
internal fun favoriteKey(catalogId: String?, title: String): String =
    catalogId ?: (FAVORITE_NAME_PREFIX + title.trim().lowercase())

/** Marks a favourite's key as a name. A catalogue id is lowercase letters, digits and underscores. */
internal const val FAVORITE_NAME_PREFIX = "name:"

internal fun FoodState.toFavorite(catalogId: String?, now: Long): FavoriteFood = FavoriteFood(
    key = favoriteKey(catalogId, title),
    title = title.trim(),
    subtitle = subtitle.trim(),
    servingG = servingG,
    calories = portionCalories,
    proteinG = portionProtein,
    fatG = portionFat,
    carbsG = portionCarbs,
    fiberG = portionExtra(ExtraNutrient.FIBER),
    sugarG = portionExtra(ExtraNutrient.SUGAR),
    saturatedFatG = portionExtra(ExtraNutrient.SATURATED_FAT),
    sodiumMg = portionExtra(ExtraNutrient.SODIUM),
    cholesterolMg = portionExtra(ExtraNutrient.CHOLESTEROL),
    createdAt = now,
)

/**
 * A blank sheet, keeping only what belongs to the day rather than to the food — the energy budget
 * the calorie bar measures against outlives whatever was just logged.
 */
internal fun FoodState.cleared(): FoodState = FoodState(dailyBudgetKcal = dailyBudgetKcal)

/**
 * The catalogue row a kept food is, as a meal's pointer, at the portion it was kept at — or null
 * where its figures are not that row's.
 *
 * A key that is a catalogue id says where the food was picked, not what was kept. The form's
 * figures can be typed over after the pick and the heart tapped then, and that favourite is the
 * person's own food under the row's id; taking the row on the key alone would log it with the
 * catalogue's nutrients. So the figures have to be the row's too — at its own fat content or at a
 * step of its rail, which is also how the fat content a kept food does not store is read back. A
 * food kept without a row does not land on an id by its name — [favoriteKey] files names where no
 * id can stand — and the same check refuses a dish kept under a bare name such as "orange".
 */
internal fun FavoriteFood.foodSource(): String? = TOP_FOODS_BY_ID[key]
    ?.let { food -> catalogVariant(food, servingG, calories, proteinG, fatG, carbsG) }
    ?.let { listOf(it).encodeDish() }

/** A kept food back in the form, on the portion basis its figures were stored for. */
internal fun FoodState.withFavorite(favorite: FavoriteFood): FoodState = copy(
    title = favorite.title,
    subtitle = favorite.subtitle,
    serving = favorite.servingG.asWeightText(),
    protein = favorite.proteinG.asWeightText(),
    fat = favorite.fatG.asWeightText(),
    carbs = favorite.carbsG.asWeightText(),
    reportedEnergy = favorite.calories.asWeightText(),
    basis = MealBasis.PER_PORTION,
    extra = buildMap {
        favorite.fiberG?.let { put(ExtraNutrient.FIBER, it.asWeightText()) }
        favorite.sugarG?.let { put(ExtraNutrient.SUGAR, it.asWeightText()) }
        favorite.saturatedFatG?.let { put(ExtraNutrient.SATURATED_FAT, it.asWeightText()) }
        favorite.sodiumMg?.let { put(ExtraNutrient.SODIUM, it.asWeightText()) }
        favorite.cholesterolMg?.let { put(ExtraNutrient.CHOLESTEROL, it.asWeightText()) }
    },
)

/**
 * The second half of building a dish: what was picked, and how much of each.
 *
 * One spoiler per ingredient rather than one long form, because a dish of six products is six
 * portion controls and all six open at once is a wall. Shut, a row says the name and the weight,
 * which is the whole of what a person scanning the list wants; open, it is the same standard
 * portion controls the catalogue offers anywhere else — the packet's own portions as chips, the
 * fat contents it is sold at where there are several, and a stepper for everything in between.
 *
 * Two ways in and one page: a dish being assembled for the first time, and a dish opened again out
 * of "your dishes". The second is [alreadySaved] and differs by one block — it is here to be
 * weighed and logged, and asking it for a name it already has would send the person round the same
 * loop they have just come out of.
 *
 * The dish is recomputed into the form's own [FoodState] on every change rather than at the end,
 * so the button at the foot is the same button the ordinary food form ends with and saves by
 * exactly the same road.
 */
@Composable
internal fun DishBuilderPage(
    modifier: Modifier,
    foods: List<TopFood>,
    grams: Map<String, Double>,
    fats: Map<String, Double>,
    calories: Double,
    note: String,
    onNote: (String) -> Unit,
    onGrams: (TopFood, Double) -> Unit,
    onFat: (TopFood, Double) -> Unit,
    onRemove: (TopFood) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    alreadySaved: Boolean,
    onSaveDish: (String) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    var open by rememberSaveable { mutableStateOf(foods.firstOrNull()?.id.orEmpty()) }

    Column(modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(4.dp))
            Text(
                stringResource(R.string.dish_build_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(12.dp))
        foods.forEach { food ->
            DishIngredientCard(
                food = food,
                grams = grams[food.id] ?: food.defaultPortion.grams,
                fatPercent = fats[food.id],
                expanded = open == food.id,
                locale = locale,
                onToggle = { open = if (open == food.id) "" else food.id },
                onGrams = { onGrams(food, it) },
                onFat = { onFat(food, it) },
                onRemove = { onRemove(food) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(4.dp))
        PwnCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dish_build_total),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.entry_energy, calories),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PwnAccentInk,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!alreadySaved) {
            SaveDishField(onSaveDish)
            Spacer(Modifier.height(12.dp))
        }
        NoteField(note, onNote)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAdd,
            enabled = foods.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
            shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
        ) {
            Text(stringResource(R.string.food_add), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Keeping the recipe, not the reckoning.
 *
 * Logging a dish writes what it was worth; this writes what it was made of, under a name, so the
 * same plate can be had again without picking its products over one at a time. It starts as a
 * button and grows into a field the way a note does — a text box sitting open under a dish nobody
 * has decided to keep is a question asked too early.
 */
@Composable
private fun SaveDishField(onSave: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }

    when {
        saved -> Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = PwnGreen)
            Spacer(Modifier.width(8.dp))
            // Worded, as the kept packet's row is: the tick is drawn for the eye, and a screen reader
            // handed the name alone would never hear that anything had been kept.
            Text(stringResource(R.string.dish_kept, name), color = PwnMuted, fontWeight = FontWeight.SemiBold)
        }

        open -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(FOOD_NAME_MAX) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.dish_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    onSave(name.trim())
                    saved = true
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.heightIn(min = NOTE_BUTTON_HEIGHT),
                shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
            ) { Text(stringResource(R.string.common_save)) }
        }

        else -> OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
            shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
            // An outlined button has no fill, so this line is the button: the row rule's outline
            // would draw it at 1.28:1 on white, the control edge at 4.20:1 there and 4.13:1 on the page,
            // over what 1.4.11 asks of a boundary that identifies a control.
            border = BorderStroke(1.dp, PwnControlEdge),
        ) {
            Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(20.dp), tint = PwnMuted)
            Spacer(Modifier.width(10.dp))
            // Centred, because at a large font the label wraps and the button grows round it.
            Text(
                stringResource(R.string.dish_save),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = PwnAccentInk,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A dish somebody built, offered back to them: its name, and what it is made of. */
@Composable
private fun SavedDishRow(dish: SavedDish, onClick: () -> Unit, onDelete: () -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val items = remember(dish.items) { dish.items.decodeDish() }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(themePainterResource(R.drawable.ic_art_dish), null, Modifier.size(40.dp))
        Column(Modifier.weight(1f)) {
            Text(dish.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                items.joinToString(" · ") { it.food.name.localized(locale) },
                style = MaterialTheme.typography.bodySmall,
                color = PwnMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.common_delete),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One ingredient: shut it is a name and a weight, open it is the catalogue's own portion controls.
 *
 * The same two rails a single food gets on the edit form — the portions this product is sold in,
 * and the fat contents it is sold at where a shop sells several of one thing. There is deliberately
 * no third rail for how it was cooked: grilled chicken and boiled chicken are two catalogue rows
 * with two sets of figures, not one row with a switch, so the preparation is chosen by picking the
 * right row in the search this page was reached through.
 */
@Composable
private fun DishIngredientCard(
    food: TopFood,
    grams: Double,
    fatPercent: Double?,
    expanded: Boolean,
    locale: java.util.Locale,
    onToggle: () -> Unit,
    onGrams: (Double) -> Unit,
    onFat: (Double) -> Unit,
    onRemove: () -> Unit,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    // The chevron shows whether the portion controls are open; the expand and
                    // collapse actions let a screen reader say it too.
                    .semantics {
                        if (expanded) {
                            collapse {
                                onToggle()
                                true
                            }
                        } else {
                            expand {
                                onToggle()
                                true
                            }
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(themePainterResource(food.art()), null, Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        food.name.localized(locale),
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.dish_build_grams, grams.asWeightText(locale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = PwnMuted,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = PwnBorder)
                Column(Modifier.padding(16.dp)) {
                    CatalogPortions(
                        food = food,
                        selectedGrams = grams,
                        // Nothing picked is not nothing on the plate: the row's own fat content is
                        // one of the steps, so the rail opens on where the product already stands
                        // rather than blank.
                        selectedFat = fatPercent ?: food.nutrition.fatG,
                        onFat = onFat,
                    ) { portion -> onGrams(portion.grams) }
                    Spacer(Modifier.height(12.dp))
                    DishGramsStepper(grams, locale, onGrams)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.common_delete), color = PwnMuted)
                    }
                }
            }
        }
    }
}

/** The weight, nudged rather than retyped — the same shape the portion card uses on the food form. */
@Composable
private fun DishGramsStepper(grams: Double, locale: java.util.Locale, onGrams: (Double) -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PwnCorners.large),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DishStep(Icons.Outlined.Remove, R.string.editor_portion_less) {
                onGrams((grams - DISH_STEP_G).coerceAtLeast(0.0))
            }
            Text(
                stringResource(R.string.dish_build_grams, grams.asWeightText(locale)),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            DishStep(Icons.Outlined.Add, R.string.editor_portion_more) { onGrams(grams + DISH_STEP_G) }
        }
    }
}

@Composable
private fun DishStep(icon: ImageVector, descriptionRes: Int, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(PwnCorners.medium), color = MaterialTheme.colorScheme.surface) {
        // The full minimum target: a size handed to an IconButton replaces its own 48dp floor.
        IconButton(onClick = onClick, modifier = Modifier.size(PwnDimensions.minimumTarget)) {
            Icon(icon, stringResource(descriptionRes), tint = PwnIndigo, modifier = Modifier.size(22.dp))
        }
    }
}

/** A mouthful rather than a gram: the chips above cover the weights anyone actually measures. */
private const val DISH_STEP_G = 10.0

/** The doors' pictures. A drawn plate and a drawn carton carry detail a 24 dp glyph never had, and want the room. */
// 32, not the 40 a tile drawing usually takes: two of these share a phone's width with a title
// and a line of hint each, and at 40 the hint would come out as "Packaged fo…". The drawing is still
// well above the size a glassy one turns to mush at.
private val FOOD_KIND_PICTURE = 32.dp

/**
 * The same picture again in the title bar of the room it opens on. Smaller than the door, because
 * there it is the subject and here it is only a reminder — but not much smaller: these drawings are
 * glass and shadow, and under about 24 dp they stop being a carton and become a smudge.
 */
private val FOOD_KIND_MARK = 28.dp

/** Enough rows to scroll through without the page turning into the whole catalogue. */
private const val CATALOG_PAGE_RESULT_LIMIT = 60

/** The result of the flow: the card corner, and a shadow the flat doors that lead to it do not have. */
private val SELECTED_CARD_RADIUS = PwnCorners.extraLarge
private val SELECTED_CARD_SHADOW = PwnElevation.level2

/** A door. Softer than a result, squarer than a chip. */
private val DOOR_ROW_RADIUS = PwnCorners.largeIncreased

/** A chip: a recent, a favourite, a category. */
private val FOOD_CHIP_RADIUS = PwnCorners.large

/** The search field's capsule. */
private val SEARCH_FIELD_RADIUS = PwnCorners.extraLarge

/** The fixed height of the empty recents' holding shape. */
private val RECENT_PLACEHOLDER_HEIGHT = 120.dp

/**
 * The two actions that close the flow, sized so the ringed mark has room to sit in them. A floor
 * rather than a height, and a full capsule at whatever height the label needs.
 */
private val ACTION_HEIGHT = 56.dp

/**
 * The rosette and the room the salute is fired into. The box is only a little wider than the badge
 * on purpose: the fireworks are placed around the centre and scaled to the box, so a taller one
 * would buy them height by pushing the sentence away from the mark it belongs to.
 */
private val SUCCESS_BADGE_BOX = 150.dp
private val SUCCESS_BADGE_ART = 120.dp

/**
 * Deletes what a photograph left in the cache. The crop screen hands back a file:// URI of the cut
 * it wrote itself; the system camera writes [MEAL_PHOTO_FILE] behind a content:// one. A picked
 * gallery image is neither, and is not this app's to delete.
 */
internal fun clearFoodPhoto(context: Context, photo: Uri?) {
    photo?.takeIf { it.scheme == "file" }?.path?.let { File(it).delete() }
    File(sharedCacheDir(context), MEAL_PHOTO_FILE).delete()
}

/**
 * The packet's printed table, read and offered — a page of the flow, where the dish builder is,
 * rather than a window over it.
 *
 * Not an `AlertDialog`: a window is the wrong shape for the step a person spends the longest on
 * with a packet in one hand: it cannot be scrolled comfortably, it hides the page it interrupts,
 * and it has nowhere to put the two things print cannot know — the name on the front of the pack
 * and whether this product is worth keeping. Both live here: the name is typed once, and one
 * tap files the product beside the favourites, so the next packet of it is a row to pick rather
 * than a photograph to take.
 *
 * The reader runs the moment the page opens and thinks out loud while it climbs; a table that
 * would not read says so, and the arrow leads back to a closer shot. What it did read stands in
 * fields, not in a sentence: print read through a camera gets a digit wrong now and then, and the
 * figures kept beside the favourites are the ones on this page, so they are corrected here. That
 * holds for sugar, fibre and sodium too, each a field where the packet printed one
 * ([figuresShown]): without one they would be kept as read without being shown, and they are the rows a reader gets
 * wrong most often. What the fields hold, the chosen basis, the name and the reading itself outlive
 * a turn of the phone; the reader does not climb a second time.
 */
@Composable
internal fun ProductLabelPage(
    modifier: Modifier,
    photo: Uri,
    favorites: List<FavoriteFood>,
    onBack: () -> Unit,
    onKeep: (String, NutritionLabel) -> Unit,
    onContinue: (String, NutritionLabel) -> Unit,
) {
    val context = LocalContext.current
    // What the page holds is saved, not merely remembered: a phone turned over the packet rebuilds
    // the activity, and the corrections typed with the packet in the other hand would go with it.
    // The reading is saved with them ([savedReading]) — null until the reader has finished, and
    // then what it read, nothing for a table that would not read. Unsaved, it would be read again
    // behind the fields on every rebuild: seconds of climbing for an answer already given, and a
    // second pass that filled the fields again where the person had emptied every one.
    var keptReading by rememberSaveable(photo) { mutableStateOf<List<String>?>(null) }
    // null while the reader is still climbing; a parse that found nothing shows the retake advice.
    var reading by remember(photo) { mutableStateOf(keptReading?.let(::restoredReading)) }
    var working by remember(photo) { mutableStateOf(keptReading == null) }
    val steps = remember(photo) { mutableStateListOf<LabelStep>() }

    // Null until the person overrules the packet; then it is their answer, not the reader's.
    var perHundred by rememberSaveable(photo) { mutableStateOf<Boolean?>(null) }
    // The figures as the fields hold them: the reader's, until typed over. Held as typed, so a
    // half-typed "12." is still there on the next keystroke.
    val typed = rememberSaveable(photo, saver = LABEL_FIELDS_SAVER) { mutableStateMapOf<LabelFigure, String>() }
    // The energy the reader only guessed at stops being a guess once somebody has typed it.
    var energyTyped by rememberSaveable(photo) { mutableStateOf(false) }
    var name by rememberSaveable(photo) { mutableStateOf("") }
    var justKept by rememberSaveable(photo) { mutableStateOf(false) }

    LaunchedEffect(photo) {
        if (keptReading != null) return@LaunchedEffect
        reading = withContext(Dispatchers.Default) {
            runCatching { LabelReader.label(context, photo) { step -> steps += step } }
                // A page left mid-climb cancels the reader between rungs: that is no failure to log.
                .onFailure { if (it is CancellationException) throw it }
                .onFailure { Log.w("ProductLabel", "label reading failed", it) }
                .getOrDefault(NutritionLabel())
        }.takeIf { it.found }
        // Only into fields nobody holds yet: a page rebuilt while the reader was still climbing
        // reads again, and the reader finishing a second time must not type over the person.
        if (typed.isEmpty()) reading?.let { typed.putAll(it.figuresRead()) }
        keptReading = reading.savedReading()
        working = false
    }

    val read = reading
    val corrected = read?.typedOver(typed, energyTyped)
    // Emptied fields can leave nothing worth logging, and then there is nothing to go on with.
    val found = corrected?.takeIf { it.found }?.onChosenBasis(perHundred)
    val named = name.trim()
    // Kept just now, or kept some other day under this same name: either way, not offered again.
    val kept = justKept || (named.isNotEmpty() && favorites.any { it.key == favoriteKey(null, named) })

    Column(modifier.fillMaxSize().panelVerticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back))
            }
            Spacer(Modifier.width(4.dp))
            Image(themePainterResource(R.drawable.ic_art_product), null, Modifier.size(FOOD_KIND_MARK))
            Spacer(Modifier.width(8.dp))
            val title = stringResource(R.string.product_label_title)
            Text(
                title,
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // What the page is for and what keeping does are read once, not under the fields on
            // every packet.
            HelpDot(title) {
                Text(stringResource(R.string.photo_label_hint))
                Text(stringResource(R.string.product_keep_hint))
            }
        }
        Spacer(Modifier.height(12.dp))
        PwnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                when {
                    working -> LabelReadingProgress(steps)

                    // The outcome is announced when it replaces the reader's progress, which takes
                    // seconds and may be waited out with the phone held at the packet.
                    read == null -> Text(
                        stringResource(R.string.photo_label_empty),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = PwnMuted,
                    )

                    else -> PhotoLabelFound(
                        reading = read,
                        typed = typed,
                        onTyped = { figure, value ->
                            typed[figure] = value
                            if (figure == LabelFigure.ENERGY) energyTyped = true
                        },
                        approximate = corrected?.approximate == true,
                        perHundred = perHundred,
                        onBasis = { perHundred = it },
                    )
                }
            }
        }
        if (read != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(FOOD_NAME_MAX)
                    // A new name is a new product; what was kept was the old one.
                    justKept = false
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.product_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Spacer(Modifier.height(12.dp))
            KeepProductField(
                kept = kept,
                enabled = named.isNotEmpty() && found != null,
                onKeep = {
                    found?.let { onKeep(named, it) }
                    justKept = true
                },
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { found?.let { onContinue(named, it) } },
            enabled = found != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
            shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
        ) {
            Text(stringResource(R.string.common_continue), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** One tap keeps the packet; kept, the row says so and asks nothing more. */
@Composable
private fun KeepProductField(kept: Boolean, enabled: Boolean, onKeep: () -> Unit) {
    if (kept) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = PwnGreen)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.product_kept), color = PwnMuted, fontWeight = FontWeight.SemiBold)
        }
        return
    }
    OutlinedButton(
        onClick = onKeep,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = NOTE_BUTTON_HEIGHT),
        shape = RoundedCornerShape(NOTE_BUTTON_RADIUS),
        // The control edge standing in for the whole edge of a fill-less button, on the page and not
        // in a card. It fades with the label while no name is typed, as Material's own outlined
        // button does, so a button that cannot be pressed yet does not keep a live-looking edge.
        border = BorderStroke(
            1.dp,
            if (enabled) PwnControlEdge else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_EDGE_ALPHA),
        ),
    ) {
        Icon(Icons.Outlined.FavoriteBorder, null, Modifier.size(20.dp), tint = PwnMuted)
        Spacer(Modifier.width(10.dp))
        // Centred, because at a large font the label wraps and the button grows round it.
        Text(
            stringResource(R.string.product_keep),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The nutrition table read off the packet: printed figures beat classified pixels, and a figure
 * misread is typed over in place. The basis is the one thing a wrong guess quietly triples, so
 * where the packet gave something to choose between it is offered as a choice rather than
 * announced — and the choice can be taken back.
 */
@Composable
private fun PhotoLabelFound(
    reading: NutritionLabel,
    typed: Map<LabelFigure, String>,
    onTyped: (LabelFigure, String) -> Unit,
    approximate: Boolean,
    perHundred: Boolean?,
    onBasis: (Boolean) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val shown = reading.onChosenBasis(perHundred)
    val basis = shown.perServingG?.let { grams ->
        stringResource(R.string.photo_label_found_serving, grams.asWeightText(locale))
    } ?: stringResource(R.string.photo_label_found)
    Text(
        basis,
        // Announced as it appears in place of the reader's progress lines; the figures under it
        // are fields, and each says its own name and value when reached.
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelMedium,
        color = PwnMuted,
    )
    reading.figuresShown().chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            pair.forEach { figure ->
                val label = stringResource(figure.label)
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                    MacroValue(
                        text = typed[figure].orEmpty(),
                        typed = typed[figure].orEmpty(),
                        editable = true,
                        onTyped = { onTyped(figure, it.decimalInput()) },
                        unit = stringResource(figure.unit),
                        accent = false,
                        description = label,
                    )
                }
            }
            // A last figure without a neighbour keeps to its half, under the column above it.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (approximate) {
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.photo_label_approximate),
            style = MaterialTheme.typography.bodySmall,
            color = PwnWarn,
        )
    }
    reading.perServingG?.let { grams ->
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PwnFilterChip(
                selected = perHundred != true,
                onClick = { onBasis(false) },
                label = stringResource(R.string.photo_label_basis_serving, grams.asWeightText(locale)),
            )
            PwnFilterChip(
                selected = perHundred == true,
                onClick = { onBasis(true) },
                label = stringResource(R.string.photo_label_basis_hundred),
            )
        }
    }
}

/** The basis the person chose, when they chose one; otherwise the one the packet declared. */
private fun NutritionLabel.onChosenBasis(perHundred: Boolean?): NutritionLabel =
    if (perHundred == true) copy(perServingG = null) else this

/**
 * The reader thinking out loud.
 *
 * Seconds pass while a packet is turned four ways and read in three scripts, and a bare spinner
 * spends them saying nothing. These are the steps as they happen — where it is looking, how much
 * text came back, what parsed out of it, and the moment it gives up on the frame and goes closer.
 */
@Composable
private fun LabelReadingProgress(steps: List<LabelStep>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.photo_label_working), color = PwnMuted)
    }
    Spacer(Modifier.height(8.dp))
    steps.takeLast(PROGRESS_LINES).forEach { step ->
        Text(
            step.spoken(),
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LabelStep.spoken(): String = when (this) {
    is LabelStep.Turning -> stringResource(R.string.photo_label_step_turn, degrees)

    is LabelStep.Read -> stringResource(R.string.photo_label_step_read, fragments)

    is LabelStep.Found -> stringResource(
        R.string.photo_label_step_found,
        reading.kcal?.asKcalText() ?: EMPTY_READING,
        listOfNotNull(reading.proteinG, reading.fatG, reading.carbsG).size,
    )

    LabelStep.Closer -> stringResource(R.string.photo_label_step_closer)

    LabelStep.Done -> stringResource(R.string.photo_label_step_done)
}

/** What a label prints instead of a figure it does not state. */
private const val EMPTY_READING = "—"

/**
 * The figures a packet is read for, each one a field on the label page. The energy and the three
 * macros always have theirs, read or not: a packet that printed one the reader missed is typed in.
 * The [printedOnly] three have a field where the reader found the row and none where it did not —
 * most packets print no fibre, and three empty fields would ask for figures nobody has.
 */
internal enum class LabelFigure(
    @StringRes val label: Int,
    @StringRes val unit: Int,
    val printedOnly: Boolean = false,
) {
    ENERGY(R.string.editor_calories, R.string.unit_kcal),
    PROTEIN(R.string.editor_protein, R.string.unit_grams),
    FAT(R.string.editor_fat, R.string.unit_grams),
    CARBS(R.string.editor_carbs, R.string.unit_grams),
    SUGAR(R.string.editor_sugar, R.string.unit_grams, printedOnly = true),
    FIBER(R.string.editor_fiber, R.string.unit_grams, printedOnly = true),
    SODIUM(R.string.editor_sodium, R.string.unit_milligrams, printedOnly = true),
    ;

    fun of(label: NutritionLabel): Double? = when (this) {
        ENERGY -> label.kcal
        PROTEIN -> label.proteinG
        FAT -> label.fatG
        CARBS -> label.carbsG
        SUGAR -> label.sugarG
        FIBER -> label.fiberG
        SODIUM -> label.sodiumMg
    }

    fun into(label: NutritionLabel, value: Double?): NutritionLabel = when (this) {
        ENERGY -> label.copy(kcal = value)
        PROTEIN -> label.copy(proteinG = value)
        FAT -> label.copy(fatG = value)
        CARBS -> label.copy(carbsG = value)
        SUGAR -> label.copy(sugarG = value)
        FIBER -> label.copy(fiberG = value)
        SODIUM -> label.copy(sodiumMg = value)
    }
}

/**
 * The fields the label page draws for this reading, in the order it draws them. Everything the
 * reading states is among them, so nothing is kept or sent on to the form that was not shown first.
 */
internal fun NutritionLabel.figuresShown(): List<LabelFigure> =
    LabelFigure.entries.filter { figure -> !figure.printedOnly || figure.of(this) != null }

/** What those fields open on: each figure the reader found, as its field writes it. */
internal fun NutritionLabel.figuresRead(): Map<LabelFigure, String> =
    LabelFigure.entries.mapNotNull { figure -> figure.of(this)?.let { figure to it.asWeightText() } }.toMap()

/**
 * The reading with the label page's fields in place of its figures: a field emptied is a figure the
 * packet does not state, and an energy line typed by somebody who looked is no longer a guess. Only
 * the fields the page draws are asked, so nothing reaches the result past them.
 */
internal fun NutritionLabel.typedOver(typed: Map<LabelFigure, String>, energyTyped: Boolean): NutritionLabel =
    figuresShown().fold(this) { label, figure -> figure.into(label, typed[figure]?.toDoubleOrNull()) }
        .copy(approximate = approximate && !energyTyped)

/**
 * The label page's fields across a rebuilt activity. A state map is nothing a saved state carries,
 * so the fields cross as a flat list: a figure's name, then what its field holds.
 */
private val LABEL_FIELDS_SAVER = listSaver<SnapshotStateMap<LabelFigure, String>, String>(
    save = { fields -> fields.savedFields() },
    restore = { saved -> mutableStateMapOf<LabelFigure, String>().apply { putAll(restoredFields(saved)) } },
)

internal fun Map<LabelFigure, String>.savedFields(): List<String> =
    flatMap { (figure, text) -> listOf(figure.name, text) }

/** The fields back out of [savedFields]; a name that is no figure of this build goes with its text. */
internal fun restoredFields(saved: List<String>): Map<LabelFigure, String> = saved.chunked(2)
    .mapNotNull { pair ->
        LabelFigure.entries.firstOrNull { it.name == pair[0] }?.let { figure -> figure to pair.getOrElse(1) { "" } }
    }
    .toMap()

/** A reading is ten cells of a saved state: its eight figures, then whether it is per 100 g and approximate. */
private const val SAVED_READING_CELLS = 10

/**
 * A finished reading as a saved state carries it: the figures in the class's own order, one the
 * packet did not print left empty, then the two flags. Of no reading — a table that would not read
 * — the empty list, which is still "finished" to the page that asks.
 */
internal fun NutritionLabel?.savedReading(): List<String> = this?.run {
    listOf(kcal, proteinG, fatG, carbsG, sugarG, fiberG, sodiumMg, perServingG).map { it?.toString().orEmpty() } +
        listOf(perHundred.toString(), approximate.toString())
}.orEmpty()

/** The reading back out of [savedReading]; null of the empty list a table that would not read left. */
internal fun restoredReading(saved: List<String>): NutritionLabel? {
    if (saved.size != SAVED_READING_CELLS) return null
    val figures = saved.map(String::toDoubleOrNull)
    return NutritionLabel(
        kcal = figures[0],
        proteinG = figures[1],
        fatG = figures[2],
        carbsG = figures[3],
        sugarG = figures[4],
        fiberG = figures[5],
        sodiumMg = figures[6],
        perServingG = figures[7],
        perHundred = saved[8].toBoolean(),
        approximate = saved[9].toBoolean(),
    )
}

/** Material's disabled outline: the on-surface colour at twelve per cent. */
private const val DISABLED_EDGE_ALPHA = 0.12f

/** How far the chosen chip's mid tone sits from its ink toward the fill. */
private const val ON_FILL_MID_MIX = 0.35f

/**
 * The catalogue's answer to [query], found off the main thread and a beat after the last keystroke.
 *
 * Walking the 4 995 rows in composition at every letter typed, scoring each with a Levenshtein
 * distance, would make a visibly janky list on a mid-range phone. The previous answer stays on screen
 * until the next one is ready, and an empty query — the page as it opens — is answered in
 * composition: the popular-first list costs nothing to take once the catalogue is loaded, which
 * the app's window sees to as it opens ([warmFoodCatalog]).
 */
@Composable
internal fun rememberFoodSearch(
    query: String,
    group: FoodCategory? = null,
    limit: Int,
    groups: Set<FoodCategory>? = null,
): List<TopFood> = rememberFoodSearchAnswer(query, group, limit, groups).orEmpty()

/**
 * [rememberFoodSearch], with null until the first answer, for a list that says when nothing was
 * found: a page restored with a query already typed, or a dialog opened on one, has no earlier answer
 * to keep on screen, and an empty list would say "nothing found" for the beat the search took.
 */
@Composable
internal fun rememberFoodSearchAnswer(
    query: String,
    group: FoodCategory? = null,
    limit: Int,
    groups: Set<FoodCategory>? = null,
): List<TopFood>? {
    val opening = remember { if (query.isBlank()) searchTopFoods(query, group, limit, groups) else null }
    val results by produceState(opening, query, group, limit, groups) {
        if (query.isNotBlank()) delay(FOOD_SEARCH_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { searchTopFoods(query, group, limit, groups) }
    }
    return results
}

/** A beat after the last keystroke: a word is typed faster than this, and only its end is searched. */
private const val FOOD_SEARCH_DEBOUNCE_MS = 150L
