package com.pwnall.health

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * One walk from the history, opened out: the map over the whole panel, and over its foot a sheet —
 * the walk's name, how long and how far it was and what it burned, the button that sends it on as a
 * file, what the line's colours stand for, its elevation and speed along the way, and the
 * photographs taken on it.
 *
 * The sheet stops at [WALK_SHEET_MAX_SHARE] of the panel, so the map is never covered whole, and a
 * pull down lowers it to the name alone rather than closing the page: the page holds its panel
 * open, and its back arrow and the system back are the ways out. The map is the track page's
 * control — pinch, drag, double tap, plus and minus — with the route framed in the part the sheet
 * leaves showing, wherever the sheet stands.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalkScreen(modifier: Modifier, profile: UserProfile, trackId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val dao = remember { StepsDb.get(context).dao() }
    val track by remember(trackId) { dao.track(trackId) }.collectAsStateWithLifecycle(null)
    // Null until Room first answers, so the chart can wait for the fixes rather than say for a moment
    // that the walk has too few of them.
    val loaded by remember(trackId) { dao.trackPoints(trackId) }.collectAsStateWithLifecycle(null)
    val allPoints = loaded.orEmpty()
    val points = remember(allPoints) { allPoints.filter { it.accepted } }
    val photos by remember(trackId) { dao.trackPhotos(trackId) }.collectAsStateWithLifecycle(emptyList())

    // A walk that is not there — gone before the page opened, or while it stood — closes the page,
    // once: there is nothing left on it to show.
    val leave by rememberUpdatedState(onBack)
    LaunchedEffect(dao, trackId) {
        dao.track(trackId).first { it == null }
        leave()
    }

    val camera = rememberSaveable(saver = MapCamera.Saver) { MapCamera() }
    val tilt = rememberSaveable(saver = MapTilt.Saver) { MapTilt() }
    val style = mapStyle()
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    // Back to the whole walk on the way out of full screen: the picture on the page has no button
    // that would bring it back from wherever the fingers left the camera.
    val closeMap = {
        fullScreen = false
        camera.follow()
    }
    val kcal = remember(points, profile) {
        walkCalories(points, profile.weightKg, profile.heightCm, profile.restingKcalPerHour())
    }
    val description = stringResource(
        R.string.track_map_route,
        formatDistance(resources, track?.distanceMeters ?: 0.0, profile.imperial),
        points.size,
        remember(points) { points.stopCount() },
    )
    // What a failed share leaves behind: a card over the middle of the page that stays until it is
    // tapped away or a share goes, since a file that never left is read, not glimpsed.
    var failure by remember { mutableStateOf<String?>(null) }

    // A pull on the sheet is for the sheet. Handed on to the panel, it would be taken for the gesture
    // that shuts the page, and the walk would close under a finger that only wanted the map.
    HoldPanelOpen()
    val sheet = rememberBottomSheetState(SheetValue.Expanded, setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded))
    // What the lowered sheet keeps showing: the handle and the walk's name with its figures, as tall
    // as they turn out at this font scale and with a name long enough to wrap.
    var peek by remember { mutableStateOf(WALK_SHEET_PEEK) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panel = maxHeight
        val density = LocalDensity.current
        // How much of the map the sheet stands over in this frame, the strip of the panel's handle
        // row included: the route is framed in what is left, and the zoom rides on the sheet's edge.
        val scaffoldPx = constraints.maxHeight + with(density) { SHEET_HANDLE_ROW.roundToPx() }
        val covered = sheet.topOrNull()?.let { top -> with(density) { (scaffoldPx - top).coerceAtLeast(0f).toDp() } }
            ?: peek
        BottomSheetScaffold(
            sheetContent = {
                WalkSheet(
                    track = track,
                    allPoints = allPoints,
                    points = points.takeIf { loaded != null },
                    photos = photos,
                    kcal = kcal,
                    imperial = profile.imperial,
                    maxHeight = panel * WALK_SHEET_MAX_SHARE - WALK_SHEET_HANDLE,
                    onPeek = { peek = it },
                ) { failure = it }
            },
            // From the panel's very edge, over the row its handle stands in — this page holds the
            // panel open, so that row is empty — and on under the sheet's rounded corners.
            modifier = Modifier.overHandleRow(),
            scaffoldState = rememberBottomSheetScaffoldState(sheet),
            sheetPeekHeight = peek,
            sheetShape = RoundedCornerShape(topStart = PwnCorners.extraLarge, topEnd = PwnCorners.extraLarge),
            sheetContainerColor = MaterialTheme.colorScheme.background,
            sheetShadowElevation = PwnElevation.level2,
            // The panel's own handle, at Material's 48dp height: the row is what a screen reader
            // focuses to raise or lower the sheet, and what a finger finds to drag it.
            sheetDragHandle = {
                BottomSheetDefaults.DragHandle(
                    width = SHEET_HANDLE_WIDTH,
                    height = SHEET_HANDLE_HEIGHT,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            containerColor = style.water,
        ) {
            RouteMap(
                points = points,
                camera = camera,
                tilt = tilt,
                style = style,
                description = description,
                follow = !fullScreen,
                expanded = false,
                onToggle = { fullScreen = true },
                drawn = !fullScreen,
                modifier = Modifier.fillMaxSize(),
                // Level with the back disc, which stands under the handle row.
                controlsInset = Modifier.padding(horizontal = 12.dp).padding(top = SHEET_HANDLE_ROW),
                photos = photos,
                fitBottomInset = covered,
            )
        }
        // Over everything else, so the sheet raised under it never hides the way out; first for a
        // screen reader, as a top bar's arrow would be.
        WalkBackButton(
            onBack,
            Modifier.align(Alignment.TopStart).padding(start = 16.dp).semantics { traversalIndex = -1f },
        )
        if (fullScreen) {
            Dialog(onDismissRequest = closeMap, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(style.water)) {
                    RouteMap(
                        points = points,
                        camera = camera,
                        tilt = tilt,
                        style = style,
                        description = description,
                        follow = true,
                        expanded = true,
                        onToggle = closeMap,
                        controlsInset = Modifier.safeDrawingPadding().padding(12.dp),
                        modifier = Modifier.fillMaxSize(),
                        photos = photos,
                    )
                }
            }
        }
        PwnFailureFlash(failure) { failure = null }
    }
}

/** Where the sheet's top edge stands, from the top of its scaffold; null before it is first placed. */
@OptIn(ExperimentalMaterial3Api::class)
private fun SheetState.topOrNull(): Float? = try {
    requireOffset()
} catch (_: IllegalStateException) {
    null
}

/**
 * Laid out [SHEET_HANDLE_ROW] taller and that much higher, over the panel's handle row: the map
 * then runs to the panel's rounded edge instead of stopping a strip of ground short of it.
 */
private fun Modifier.overHandleRow(): Modifier = layout { measurable, constraints ->
    val row = SHEET_HANDLE_ROW.roundToPx()
    val tall = constraints.maxHeight + row
    val placeable = measurable.measure(constraints.copy(minHeight = tall, maxHeight = tall))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -row) }
}

/**
 * The walk itself, under the sheet's handle. Nothing in it waits for the rest — the chart and the
 * photographs come in as their rows arrive, and the head once the walk's own row has.
 *
 * Never taller than [maxHeight]; what does not fit scrolls inside it, which only happens with the
 * photographs at a large font scale. [onPeek] gets the height the lowered sheet needs to keep the
 * head in view above the navigation bar, handle included. [onFailure] gets the words of a share
 * that failed, and null for one that went.
 */
@Composable
private fun WalkSheet(
    track: TrackEntity?,
    allPoints: List<TrackPointEntity>,
    points: List<TrackPointEntity>?,
    photos: List<TrackPhotoEntity>,
    kcal: Double,
    imperial: Boolean,
    maxHeight: Dp,
    onPeek: (Dp) -> Unit,
    onFailure: (String?) -> Unit,
) {
    val density = LocalDensity.current
    val navigation = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth()
            .heightIn(max = maxHeight.coerceAtLeast(0.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = navigation + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        track?.let {
            WalkHeader(
                it,
                allPoints,
                kcal,
                imperial,
                onFailure,
                Modifier.onSizeChanged { size ->
                    onPeek(WALK_SHEET_HANDLE + with(density) { size.height.toDp() } + WALK_PEEK_GAP + navigation)
                },
            )
        }
        RouteLegend(mapStyle())
        points?.let { WalkProfile(it, imperial, Modifier.fillMaxWidth()) }
        if (photos.isNotEmpty()) WalkPhotos(photos)
    }
}

/**
 * The walk's name, the line of figures under it, and at their right the button that sends the walk on
 * and the one that deletes it. The name and the figures take whatever the buttons' 48dp each leave
 * them. A walk still recording has no bin: its row is the one the service is writing into.
 */
@Composable
private fun WalkHeader(
    track: TrackEntity,
    points: List<TrackPointEntity>,
    kcal: Double,
    imperial: Boolean,
    onFailure: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = track.title(LocalResources.current)
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(top = 4.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // One stop for a screen reader: the clock's name, then the figures.
            Row(Modifier.semantics(mergeDescendants = true) { }) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = stringResource(R.string.breathing_duration),
                    // Level with the middle of the first line, however the figures wrap.
                    modifier = Modifier.padding(top = 2.dp).size(16.dp),
                    tint = PwnMuted,
                )
                Spacer(Modifier.width(6.dp))
                Text(walkFacts(track, kcal, imperial), style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
            }
        }
        WalkShareButton(track, title, points, onFailure)
        if (track.endedAt != null) WalkDeleteButton(track)
    }
}

/** How long, how far, what it burned ([walkCalories]) and which day: the line under the walk's name. */
@Composable
private fun walkFacts(track: TrackEntity, kcal: Double, imperial: Boolean): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    // A walk in the history has ended; a recording would read as long as it had run when drawn. The
    // time it stood paused is not walking, and is left out of both.
    val millis = track.movingMillis(System.currentTimeMillis())
    return listOf(
        DateUtils.formatElapsedTime(millis.coerceAtLeast(0L) / 1_000),
        formatDistance(resources, track.distanceMeters, imperial),
        // Through the template the journal prints an entry's energy with: the figure in the digits
        // the language writes, and the unit where the language puts it.
        resources.getString(R.string.entry_energy, kcal),
        // The platform's own date, in the digits the language writes, as the duration and the
        // distance beside it are: java.time's localized date stays in ASCII digits in Arabic.
        DateUtils.formatDateTime(
            context,
            track.startedAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        ),
    ).joinToString(" · ")
}

/**
 * The share glyph, and under it the menu of the files the walk can go out as — the same ones the
 * track page saves into a folder. The format picked is written out and handed to whatever the person
 * picks out of the share sheet. [onFailure] gets the words when it cannot be, and null once a share
 * has gone, which takes an earlier failure's card down as the next attempt should.
 */
@Composable
private fun WalkShareButton(
    track: TrackEntity,
    title: String,
    points: List<TrackPointEntity>,
    onFailure: (String?) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { choosing = true },
            modifier = Modifier.opensMenu(choosing) { choosing = it },
            enabled = RouteFormat.entries.any { it.availableFor(points) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
        ) {
            Icon(Icons.Outlined.Share, stringResource(R.string.walk_share))
        }
        RouteFormatMenu(choosing, points, onDismiss = { choosing = false }) { format ->
            scope.launch {
                val failed = shareRoute(context, track, title, points, format).exceptionOrNull()
                onFailure(failed?.let { resources.getString(R.string.profile_export_error, it.message ?: "") })
            }
        }
    }
}

/**
 * The bin beside the share glyph, and the question it asks first, because a walk cannot be taken
 * again. The walk goes whole — its row, its fixes, its photographs and their files, see [deleteWalk]
 * — and the page then closes itself, as it does for any walk that is not there.
 */
@Composable
private fun WalkDeleteButton(track: TrackEntity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    IconButton(
        onClick = { asking = true },
        colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
    ) {
        Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.walk_delete))
    }
    if (asking) {
        DestructiveDialog(
            title = stringResource(R.string.walk_delete_title),
            body = stringResource(R.string.walk_delete_body),
            onDismiss = { asking = false },
            onConfirm = {
                asking = false
                scope.launch { StepsDb.get(context).deleteWalk(context, track) }
            },
        )
    }
}

/**
 * One walk written out as [format] and handed to the share sheet, as the doctor's report is. The
 * file goes into a folder of the walk's own, `shared/routes/<id>` in the cache: two walks of the same name
 * cannot hand out each other's file, and the report's clearing of its own folder before each report
 * cannot take a walk somebody is still reading. The receiving app gets a read grant on that one
 * file, and the grant outlives the moment it is sent, so the file stays until the next start sweeps
 * the folder — see [sweepExportLeftovers]. Written on the IO dispatcher: a long walk is a great many
 * fixes.
 */
internal suspend fun shareRoute(
    context: Context,
    track: TrackEntity,
    title: String,
    points: List<TrackPointEntity>,
    format: RouteFormat,
): Result<Unit> = runCatching {
    val fileName = walkShareName(title, track.id, format.extension)
    val file = withContext(Dispatchers.IO) {
        val folder = File(File(context.cacheDir, ROUTE_SHARE_DIR), track.id.toString()).apply { mkdirs() }
        File(folder, fileName).apply { writeText(format.write(track, points)) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = format.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        // The clip carries the URI a second time: it is what the chooser reads to preview the file.
        clipData = ClipData.newUri(context.contentResolver, fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.walk_share)))
}.onFailure { if (it is CancellationException) throw it }

/**
 * The name a shared walk's file goes out under: its own title, so the file is known for what it is
 * wherever it lands, cleared of what a file name may not hold — path separators, the characters
 * Windows and a memory card's FAT refuse, control characters — and of dots and spaces at either end,
 * which hide a file or drop off its name. A title with nothing left is the route's number instead.
 */
internal fun walkShareName(title: String, trackId: Long, extension: String): String {
    val cleaned = title.map { if (it in FILE_NAME_RESERVED || it.isISOControl()) ' ' else it }
        .joinToString("")
        .split(' ')
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .trim { it == '.' || it.isWhitespace() }
    return "${cleaned.ifEmpty { "route-$trackId" }}.$extension"
}

/**
 * The photographs in the order they were taken, numbered as the map numbers them, each with what
 * the phone could say about where it was pointing. A lazy row: a long walk can carry a great many,
 * and only the tiles in view are worth decoding.
 */
@Composable
private fun WalkPhotos(photos: List<TrackPhotoEntity>) {
    val remove = rememberWalkPhotoRemover()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HelpTitle(stringResource(R.string.walk_photos)) { Text(stringResource(R.string.walk_photos_help)) }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                WalkPhotoTile(photo, number = index + 1, onRemove = { remove(photo) })
            }
        }
    }
}

/**
 * One photograph: the picture, its number, and the readings under it. The tile is one stop for a
 * screen reader, which hears the readings whole — the caption under it is drawn only.
 */
@Composable
private fun WalkPhotoTile(photo: TrackPhotoEntity, number: Int, onRemove: () -> Unit) {
    val context = LocalContext.current
    // Decoded small and off the main thread; a missing file is an empty tile, not a crash.
    val image by produceState<ImageBitmap?>(initialValue = null, photo.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { thumbnail(File(walkPhotoDir(context), photo.file)) }.getOrNull()
        }
    }
    val words = photoWords(photo, number)
    Column(Modifier.width(PHOTO_TILE_WIDTH), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(PHOTO_TILE_WIDTH, PHOTO_TILE_HEIGHT)
                .clip(RoundedCornerShape(PwnCorners.large))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_open)) {
                    context.openWalkPhoto(photo)
                }
                .semantics { contentDescription = words.spoken },
        ) {
            image?.let {
                Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            PhotoBadge(number, Modifier.align(Alignment.TopStart).padding(8.dp))
            // The disc stays the small one drawn in the corner; the button around it is the full
            // 48dp. White on the dark disc rather than the resting slate, which a photograph under
            // it would swallow.
            IconButton(
                onClick = onRemove,
                modifier = Modifier.align(Alignment.TopEnd),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
            ) {
                Box(
                    Modifier.size(24.dp).background(Color.Black.copy(alpha = PHOTO_SCRIM_ALPHA), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.walk_photo_delete, number),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            words.caption,
            Modifier.clearAndSetSemantics { },
            style = MaterialTheme.typography.bodySmall,
            color = PwnMuted,
        )
    }
}

/** A photograph's number in the disc the map draws it in: the accent, the figure in the ink on it. */
@Composable
private fun PhotoBadge(number: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics { }
            .sizeIn(minWidth = PHOTO_BADGE_SIZE, minHeight = PHOTO_BADGE_SIZE)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            photoNumber(number),
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** What a photograph's readings say: short under its tile, and whole for a screen reader. */
private class PhotoWords(val caption: String, val spoken: String)

/**
 * Each reading the phone had, in words: which way the camera faced, how far it tilted, how the phone
 * was held, and when. A reading the phone could not give is left out, except the direction, whose
 * absence is said — a direction missing without a word reads as a direction forgotten. It is said as
 * unknown rather than as no compass: a phone with one still keeps none when there was nowhere to read
 * true north from, or no reading came while the camera was open.
 */
@Composable
private fun photoWords(photo: TrackPhotoEntity, number: Int): PhotoWords {
    val points = stringArrayResource(R.array.walk_compass_points)
    val names = stringArrayResource(R.array.walk_compass_names)
    val bearing = photo.bearingDeg
    val facing: String
    val facingSpoken: String
    if (bearing == null) {
        facing = stringResource(R.string.walk_photo_no_direction)
        facingSpoken = facing
    } else {
        val point = compassPoint(bearing)
        facing = stringResource(R.string.walk_photo_facing, points[point], bearing.roundToInt().mod(FULL_TURN))
        facingSpoken = stringResource(R.string.walk_photo_facing_spoken, names[point])
    }
    val tilt = photo.pitchDeg?.let { pitch ->
        val degrees = abs(pitch).roundToInt()
        when (photoTilt(pitch)) {
            PhotoTilt.UP -> stringResource(R.string.walk_photo_up, degrees)
            PhotoTilt.DOWN -> stringResource(R.string.walk_photo_down, degrees)
            PhotoTilt.LEVEL -> stringResource(R.string.walk_photo_level)
        }
    }
    val hold = photo.rollDeg?.let { roll ->
        stringResource(
            when (photoHold(roll)) {
                PhotoHold.PORTRAIT -> R.string.walk_photo_portrait
                PhotoHold.LANDSCAPE -> R.string.walk_photo_landscape
                PhotoHold.UPSIDE_DOWN -> R.string.walk_photo_upside_down
            },
        )
    }
    // In the digits the language writes, as the page's date is, and on the 24-hour clock every other
    // time in the app is on: see [clockText].
    val time = skeletonDateText("Hm", LocalLocale.current.platformLocale, photo.takenAt, ZoneId.systemDefault())
    val rest = listOfNotNull(tilt, hold, time)
    return PhotoWords(
        caption = (listOf(facing) + rest).joinToString(" · "),
        spoken = stringResource(
            R.string.walk_photo_spoken,
            number,
            (listOf(facingSpoken) + rest).joinToString(stringResource(R.string.list_comma)),
        ),
    )
}

/**
 * Which of the eight points of the compass [bearingDeg] falls in, 0 for north and on clockwise: each
 * point holds the 45° centred on it, so north runs from 337.5° up to 22.5°.
 */
internal fun compassPoint(bearingDeg: Float): Int =
    floor((bearingDeg + COMPASS_SECTOR / 2) / COMPASS_SECTOR).toInt().mod(COMPASS_POINTS)

/** Which way the camera pointed, as the caption words it. */
internal enum class PhotoTilt { UP, LEVEL, DOWN }

/** Within [LEVEL_PITCH_DEG] of the horizon either way the camera was level. */
internal fun photoTilt(pitchDeg: Float): PhotoTilt = when {
    abs(pitchDeg) < LEVEL_PITCH_DEG -> PhotoTilt.LEVEL
    pitchDeg > 0f -> PhotoTilt.UP
    else -> PhotoTilt.DOWN
}

/** How the phone was held, as the caption words it. */
internal enum class PhotoHold { PORTRAIT, LANDSCAPE, UPSIDE_DOWN }

/**
 * Upright within [PORTRAIT_ROLL_DEG] of portrait, on its side either way up to [LANDSCAPE_ROLL_DEG],
 * and upside down past that. The roll is the photograph row's own, ±180° about the camera's axis.
 */
internal fun photoHold(rollDeg: Float): PhotoHold = when {
    abs(rollDeg) < PORTRAIT_ROLL_DEG -> PhotoHold.PORTRAIT
    abs(rollDeg) <= LANDSCAPE_ROLL_DEG -> PhotoHold.LANDSCAPE
    else -> PhotoHold.UPSIDE_DOWN
}

/**
 * Deleting a photograph: the question first, then the row and the file together, not cancellable
 * half way — the progress photographs' remover, for the walk's. Asked because the moment cannot be
 * taken again.
 */
@Composable
private fun rememberWalkPhotoRemover(): (TrackPhotoEntity) -> Unit {
    val context = LocalContext.current
    val dao = remember(context) { StepsDb.get(context).dao() }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<TrackPhotoEntity?>(null) }
    pending?.let { photo ->
        DestructiveDialog(
            title = stringResource(R.string.progress_photo_delete_title),
            body = stringResource(R.string.walk_photo_delete_body),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                scope.launch {
                    withContext(NonCancellable) {
                        dao.deleteTrackPhoto(photo.id)
                        withContext(Dispatchers.IO) { File(walkPhotoDir(context), photo.file).delete() }
                    }
                }
            },
        )
    }
    return { pending = it }
}

/** Full-size in whatever the phone views pictures with, as a progress photograph opens. */
private fun Context.openWalkPhoto(photo: TrackPhotoEntity) {
    val file = File(walkPhotoDir(this), photo.file)
    openAttachment(FileProvider.getUriForFile(this, "$packageName.photos", file).toString())
}

/**
 * The way back, in a disc over the map as the vitamins page draws its own: there is no top bar here,
 * the map runs to the panel's edge, and the arrow needs ground of its own to be seen on.
 */
@Composable
private fun WalkBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        // The disc stays 46dp; what a finger reaches grows to the 48dp minimum.
        modifier.minimumInteractiveComponentSize()
            .size(46.dp)
            .background(PwnSurfaceElevated, CircleShape)
            .clickable(role = Role.Button, onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.common_back), tint = PwnMuted)
    }
}

/** As much of the panel as the raised sheet may cover: the map above it always stays in view. */
private const val WALK_SHEET_MAX_SHARE = 0.7f

/** Material's drag handle row: the 4dp bar and 22dp above and below it. */
private val WALK_SHEET_HANDLE = 48.dp

/** The lowered sheet before the head has been measured, and the room it keeps under the head. */
private val WALK_SHEET_PEEK = 140.dp
private val WALK_PEEK_GAP = 16.dp

/**
 * The cache folder a shared walk is written into, a folder for each walk inside it, in the cache's
 * shared folder the FileProvider serves ([SHARED_CACHE_DIR]); [sweepExportLeftovers] empties it at
 * each fresh start.
 */
internal const val ROUTE_SHARE_DIR = "$SHARED_CACHE_DIR/routes"

/** What a file name may not hold on Windows or a memory card, path separators among them. */
private const val FILE_NAME_RESERVED = "\\/:*?\"<>|"

private val PHOTO_TILE_WIDTH = 160.dp
private val PHOTO_TILE_HEIGHT = 120.dp
private val PHOTO_BADGE_SIZE = 22.dp
private const val PHOTO_SCRIM_ALPHA = 0.45f

private const val COMPASS_POINTS = 8
private const val COMPASS_SECTOR = 45f
private const val FULL_TURN = 360
private const val LEVEL_PITCH_DEG = 5f
private const val PORTRAIT_ROLL_DEG = 45f
private const val LANDSCAPE_ROLL_DEG = 135f
