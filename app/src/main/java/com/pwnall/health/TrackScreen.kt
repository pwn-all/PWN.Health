package com.pwnall.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The route recorder: a map from edge to edge, and laid over it the way back and the saved routes in
 * one top corner, the GPS signal in the other, and at the foot the camera beside Start, which parts
 * into Stop and Pause while a walk records. Before a walk the map stands over where the phone is;
 * during one, over the walk and the phone together. The walk's figures and the saved routes are
 * pages held over the map, opened from the signal pill and the history disc.
 *
 * The map is drawn offline, from the assets the app ships with: no internet and no map tiles are
 * involved. A saved walk opens on a page of its own through [onOpenWalk], by its id.
 */
@Composable
fun TrackScreen(modifier: Modifier = Modifier, profile: UserProfile, onBack: () -> Unit, onOpenWalk: (Long) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val imperial = profile.imperial
    val dao = remember { StepsDb.get(context).dao() }
    // Null until Room first answers. A walk already recording must not be drawn as Start for a frame
    // and then act out its whole start, and that first frame must not read as a walk ending either.
    val opened by remember { dao.activeTrack().map(::OpenRoute) }.collectAsStateWithLifecycle(null)
    val active = opened?.track
    val tracks by remember { dao.tracks() }.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable(stateSaver = TRACK_PAGE_SAVER) { mutableStateOf(TrackPage.MAP) }
    // Whose fixes are read: the recording's, which the map draws, or else the last walk's, read only
    // while the info page shows its figures — the map draws no walk but the one recording.
    val shown = active ?: tracks.firstOrNull().takeIf { page == TrackPage.INFO }
    // One query rather than two: a fix arrives every couple of seconds while recording and
    // invalidates the whole result set, and the accepted line is a filter over the raw rows. What
    // is counted over them is counted where they are read, see [routeFixes], and not here: every
    // fix re-materialises the whole set, and by the end of a long route that would be tens of
    // thousands of rows walked three times over on the thread that draws the screen.
    val fixes by remember(shown?.id, profile) {
        (shown?.id?.let { dao.trackPoints(it) } ?: flowOf(emptyList()))
            .map { routeFixes(it, profile.weightKg, profile.heightCm, profile.restingKcalPerHour()) }
            .flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(NO_FIXES)
    val allPoints = fixes.all
    val points = fixes.accepted
    val rejected = fixes.rejected
    val kcal = fixes.kcal
    // The photographs taken on the walk recording, marked on the map where they were taken.
    val photos by remember(active?.id) {
        active?.id?.let { dao.trackPhotos(it) } ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    // Checked against the walk as well: a query just re-aimed still holds the rows it read before,
    // and those of the last walk must not flash onto the map as a recording starts.
    val line = points.takeIf { active != null && it.firstOrNull()?.trackId == active.id }.orEmpty()

    var granted by remember { mutableStateOf(context.canRecordRoute()) }

    // A granted permission says nothing about the device switch: location can be turned off while a
    // recording runs, and then no fix ever arrives. Both are looked at every couple of seconds while
    // the app is in front, and that look is also the clock a fix's age is read against.
    // A 2 s poll instead of a PROVIDERS_CHANGED receiver — nothing to register, unregister or leak,
    // and the check is wanted only while the screen is in front.
    val locationManager = remember { context.getSystemService(LocationManager::class.java) }
    var locationOn by remember { mutableStateOf(locationManager.isLocationEnabled) }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                locationOn = locationManager.isLocationEnabled
                granted = context.canRecordRoute()
                clock = SystemClock.elapsedRealtime()
                delay(LOOK_INTERVAL_MS)
            }
        }
    }
    // A route in hand needs the service to hold it as a location service, and after Android has
    // restarted the service in the background it does not: that start is refused the `location` type,
    // the recording carries on as a pedometer and no fix arrives once the phone is put away — or,
    // where there is no pedometer to carry on as, no service is left at all. A plain start from here,
    // with the app in front, lets it pick the route up and claim the type. The home screen's start
    // does that as well; the recording notification opens this screen and never passes it.
    LaunchedEffect(active?.id, granted) {
        if (active != null && granted) {
            ContextCompat.startForegroundService(context, Intent(context, StepService::class.java))
        }
    }
    val here = rememberPhoneFix(listening = granted && locationOn)
    val signal = GpsSignal(
        level = gpsSignalLevel(here?.accuracyM, here?.let { clock - it.elapsedNanos / NANOS_PER_MILLI }),
        accuracyM = here?.accuracyM,
        locationOn = locationOn,
        granted = granted,
    )

    // The word an export, a photograph or a finished walk leaves behind, held up here so the card
    // that shows it stands over the map and over either page alike. A failure is held apart from it:
    // the tick is green and gone in three seconds, and a route not saved or a photograph not kept is
    // neither, so its card stays until it is tapped away. Each clears the other, or the two cards
    // would stand one on the other.
    var status by rememberStatusLine()
    var failure by remember { mutableStateOf<String?>(null) }

    fun confirm(message: String) {
        failure = null
        status = message
    }

    fun fail(message: String) {
        status = null
        failure = message
    }

    // Start's question. A refusal is said: refused twice, Android answers every later request at
    // once and shows nothing, and Start would then be a button that did nothing at all. The way to the
    // app's settings is on the info page, behind the signal pill and its red mark.
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = context.canRecordRoute()
        if (granted) {
            context.sendToService(StepService.ACTION_START_TRACK)
        } else {
            fail(resources.getString(R.string.track_permission_needed))
        }
    }

    // Asked for whatever stands on the screen, so the camera's answer finds its launcher in the same
    // place: after a rotation, or in a process started again behind the camera app.
    val photograph = rememberWalkPhotoCapture(active?.id, onSaved = ::confirm, onFailed = ::fail)

    val camera = rememberSaveable(saver = MapCamera.Saver) { MapCamera(closest = HERE_PIXELS_PER_UNIT) }
    val tilt = rememberSaveable(saver = MapTilt.Saver) { MapTilt() }
    val style = mapStyle()
    // Which walk the camera was last aimed at, kept alongside it: a start or an end puts the map back
    // on following, but the phone turning composes the screen again with the same walk, and that must
    // not read as either and undo the zoom.
    var framedTrackId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(opened != null, active?.id) {
        if (opened != null && framedTrackId != active?.id) {
            framedTrackId = active?.id
            camera.follow()
        }
    }
    // The line, the dots and the ground under them are all paint, and paint says nothing out loud.
    // A screen reader gets the three numbers the picture is made of, or what it is centred on.
    val stopCount = if (line.isEmpty()) 0 else fixes.stops
    val mapDescription = when {
        active != null -> stringResource(
            R.string.track_map_route,
            formatDistance(resources, active.distanceMeters, imperial),
            line.size,
            stopCount,
        )

        here != null -> stringResource(R.string.track_map_here)

        else -> stringResource(R.string.track_map_waiting)
    }

    // The map as it stood when a page was opened over it, for the page's backdrop.
    val behind = rememberBackdropShot()
    // A page's back arrow takes the system's road, so the page falls away as a back gesture lets it.
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val closePage: () -> Unit = {
        if (backDispatcher != null) backDispatcher.onBackPressed() else page = TrackPage.MAP
    }
    val title = stringResource(R.string.track_title)
    Box(modifier.fillMaxSize().semantics { paneTitle = title }) {
        Box(Modifier.fillMaxSize().background(style.water).inertUnder(page != TrackPage.MAP)) {
            RouteMap(
                points = line,
                camera = camera,
                tilt = tilt,
                style = style,
                description = mapDescription,
                follow = true,
                expanded = false,
                onToggle = null,
                // Drawn first and read last: a screen reader comes to the controls over it before it.
                modifier = Modifier.fillMaxSize().semantics {
                    isTraversalGroup = true
                    traversalIndex = 1f
                },
                controlsInset = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(MAP_CONTROLS_CLEAR),
                photos = if (line.isEmpty()) emptyList() else photos,
                position = here,
                // Square to the window's own edges: rounded, its corners would show the water.
                shape = RectangleShape,
            )
            TrackOverlays(
                state = opened?.let { recordState(it.track) },
                signal = signal,
                imperial = imperial,
                startEnabled = locationOn,
                onBack = onBack,
                onHistory = { behind.open { page = TrackPage.HISTORY } },
                onSignal = { behind.open { page = TrackPage.INFO } },
                onPhoto = photograph,
                onStart = {
                    if (granted) {
                        context.sendToService(StepService.ACTION_START_TRACK)
                    } else {
                        // Android 12 and later ignore a request for the precise permission that does
                        // not ask for the approximate one alongside it: no dialog appears at all, and
                        // the button does nothing.
                        request.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onFinish = {
                    // A plain start and not a foreground one: the screen is in front, so it is allowed,
                    // and it leaves the service owing Android no startForeground. One brought up only
                    // to close a route a restart had left open has, on Android 14 without the physical
                    // activity permission, no type left to declare, and stopping without one would
                    // close the app.
                    context.startService(
                        Intent(context, StepService::class.java).setAction(StepService.ACTION_STOP_TRACK),
                    )
                    confirm(resources.getString(R.string.track_finished))
                },
                // Plain starts as well, for Finish's reason: a pause or a resume of a route a restart
                // left open brings a service up, and with neither permission that one has no type to
                // declare on Android 14 — stopped without one after a foreground start, it would close the app.
                onPause = {
                    context.startService(
                        Intent(context, StepService::class.java).setAction(StepService.ACTION_PAUSE_TRACK),
                    )
                },
                onResume = {
                    context.startService(
                        Intent(context, StepService::class.java).setAction(StepService.ACTION_RESUME_TRACK),
                    )
                },
            )
        }
        when (page) {
            TrackPage.MAP -> Unit

            TrackPage.INFO -> TrackPageSheet(onClose = { page = TrackPage.MAP }, backdrop = behind.backdrop) {
                TrackInfoPage(
                    signal = signal,
                    route = shown,
                    state = recordState(active),
                    allPoints = allPoints,
                    points = points,
                    rejected = rejected,
                    kcal = kcal,
                    imperial = imperial,
                    onBack = closePage,
                    onSaved = ::confirm,
                    onFailed = ::fail,
                )
            }

            TrackPage.HISTORY -> TrackPageSheet(onClose = { page = TrackPage.MAP }, backdrop = behind.backdrop) {
                // Every route, where [tracks] is only the latest twenty: enough for the map and the
                // info page, but not for the history once there are more walks than that. Those
                // stand in while the table is read, and it is read again whenever they change —
                // whenever a route starts, moves on or ends.
                val everyTrack by produceState(tracks, tracks) { value = dao.allTracks() }
                TrackHistoryPage(everyTrack, imperial, onBack = closePage, onOpenWalk = onOpenWalk)
            }
        }
        PwnActionFlash(status)
        PwnFailureFlash(failure) { failure = null }
    }
}

/** What stands on the track screen: the map alone, or one of the two pages held over it. */
private enum class TrackPage { MAP, INFO, HISTORY }

/** By name, so a build that drops a page reopens on the map rather than failing to restore. */
private val TRACK_PAGE_SAVER = Saver<TrackPage, String>(
    save = { it.name },
    restore = { name -> TrackPage.entries.firstOrNull { it.name == name } ?: TrackPage.MAP },
)

/** The open walk as Room last answered, or none: held, so that no answer yet can be null. */
private class OpenRoute(val track: TrackEntity?)

/**
 * One reading of a route's fixes with what the screen counts over them: [all] as stored, parted
 * into the ones the filter [accepted] and the ones it [rejected], what the accepted ones burned
 * ([walkCalories]) and how many [stops] they hold.
 */
internal class RouteFixes(
    val all: List<TrackPointEntity>,
    val accepted: List<TrackPointEntity>,
    val rejected: List<TrackPointEntity>,
    val kcal: Double,
    val stops: Int,
)

/**
 * [all] parted and counted in one place, so that the track screen can do it where the rows are read
 * rather than in composition: the parting in one pass, not two, and the energy for the walker's
 * weight, height and rest as [walkCalories] takes them.
 */
internal fun routeFixes(
    all: List<TrackPointEntity>,
    weightKg: Double,
    heightCm: Int,
    restingKcalPerHour: Double,
): RouteFixes {
    val (accepted, rejected) = all.partition { it.accepted }
    return RouteFixes(
        all = all,
        accepted = accepted,
        rejected = rejected,
        kcal = walkCalories(accepted, weightKg, heightCm, restingKcalPerHour),
        stops = accepted.stopCount(),
    )
}

/** Before Room first answers, and for no route at all. */
private val NO_FIXES = RouteFixes(emptyList(), emptyList(), emptyList(), kcal = 0.0, stops = 0)

/**
 * A walk as the map draws it: its fixes, and with them what is worked out from every one of them —
 * where each falls on the map ([projectRoute]), how high it stands ([routeLift]) and the colour and
 * width of its piece of line ([routeInk]). One object, because the drawing reads them side by side,
 * fix by fix: a list one fix longer than its arrays would be read past their end.
 */
internal class DrawnRoute(
    val points: List<TrackPointEntity>,
    val route: DoubleArray,
    val lift: FloatArray,
    val ink: RouteInk,
) {
    /**
     * Whether this is the walk [fixes] belong to, or as empty as they are. A fix or two behind them
     * it is still that walk; another walk's line, or any line once there is none to draw, is not.
     */
    fun isOf(fixes: List<TrackPointEntity>): Boolean = points.firstOrNull()?.trackId == fixes.firstOrNull()?.trackId
}

/** [points] with all of that worked out, the line in [style]'s inks. */
internal fun drawnRoute(points: List<TrackPointEntity>, style: MapStyle): DrawnRoute {
    val route = projectRoute(points)
    return DrawnRoute(points, route, routeLift(points), routeInk(points, route, style))
}

private val NO_ROUTE = DrawnRoute(emptyList(), DoubleArray(0), FloatArray(0), RouteInk(IntArray(0), FloatArray(0)))

/**
 * [points] as the map draws them, worked out off the thread that draws. While a walk records, every
 * fix hands the map the whole list again, and the lift alone sorts thirty-one heights for each fix
 * of it: by the end of a long day's walk that would be a pause in the drawing every couple of seconds.
 * The line in hand goes on being drawn meanwhile, a fix behind for a moment, and the next arrives
 * whole. A line that is not of the walk asked for is not drawn at all, so a walk that has ended
 * leaves the map at once and the last one's never shows under a new recording.
 */
@Composable
private fun rememberDrawnRoute(points: List<TrackPointEntity>, style: MapStyle): DrawnRoute {
    val drawn by produceState(NO_ROUTE, points, style) {
        value = if (points.isEmpty()) NO_ROUTE else withContext(Dispatchers.Default) { drawnRoute(points, style) }
    }
    return if (drawn.isOf(points)) drawn else NO_ROUTE
}

/**
 * A page held over the map: the app's panel, standing in an edge-to-edge screen, so it is handed the
 * window's own insets — the status bar over it, the side bars and cutouts beside it. It runs on under
 * the navigation bar, as the panel's ground, and the pages keep their last row clear of it.
 */
@Composable
private fun TrackPageSheet(
    onClose: () -> Unit,
    backdrop: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    PwnSheetPage(
        onClose = onClose,
        topInset = safe.calculateTopPadding(),
        backdrop = backdrop,
        sides = PaddingValues(start = safe.calculateStartPadding(direction), end = safe.calculateEndPadding(direction)),
        content = content,
    )
}

/**
 * The map and its controls while a page stands over them: kept composed, so the ground already read
 * is there the moment the page goes rather than a second later, but out of reach — no touch, no key
 * and no screen reader gets through the page to what it covers.
 */
private fun Modifier.inertUnder(covered: Boolean): Modifier = if (!covered) {
    this
} else {
    clearAndSetSemantics { }
        .focusProperties { onEnter = { cancelFocusChange() } }
        .focusGroup()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
}

/** How often the screen looks at the location switch and the grant, and moves its clock on. */
private const val LOOK_INTERVAL_MS = 2_000L

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * What the map's own buttons keep clear of: the signal pill over the one in the top corner, and the
 * camera and the recording control under the zoom.
 */
private val MAP_CONTROLS_CLEAR = PaddingValues(
    start = 12.dp,
    end = 12.dp,
    top = OVERLAY_EDGE + DISC_SIZE + 8.dp,
    bottom = OVERLAY_EDGE + RECORD_SIZE + 8.dp,
)

/** The camera's frame key while no walk is drawn and the map follows the phone instead. */
private const val HERE_FRAME = -1L

/**
 * The route drawn on the offline world map, in the same Mercator projection the map uses — so the
 * line keeps its shape at every zoom instead of only near the latitude it was fitted for.
 *
 * Pinch and drag inside the panel, double-tap to zoom in, or use the plus and minus in its corner,
 * which are the ways in for one finger, a keyboard or a switch; the first of any of them takes the
 * view off the route and the button in the other corner gives it back. [follow] is false for the
 * panel behind an open full-screen dialog, so two copies of the same camera do not fight over the
 * fit. [description] is what the canvas answers a screen reader with, since none of what it draws is
 * text. [expanded], [onToggle] and [controlsInset] are the full-screen button's state, its action
 * and how far the buttons stand in from the edges; a map that already fills the screen passes no
 * [onToggle] and has no such button.
 *
 * [photos] are drawn over the line as numbered discs, numbered by their place in the list — the
 * order the walk page lists them in — each with a pale wedge for the way the camera faced, where the
 * compass said. [interactive] false
 * draws a picture: nothing to pinch and no buttons, for a page that lays its own tap over the map.
 * [fitBottomInset] is how much of the map's foot something else covers; the route is framed in the
 * part left showing, and the plus and minus stand just above it.
 *
 * [position] is where the phone is, drawn over everything else as a disc in the accent inside the
 * circle its accuracy draws. It is framed with the route, a few streets of ground around it — see
 * [viewAround] — so the walker stays on the map through a pause however far they went; with no route
 * at all, the map follows the phone alone, and its button in the corner comes back to the phone.
 * [shape] is the map's outline: rounded as a card is, unless the map is the whole window.
 *
 * [drawn] false keeps the map composed but blank, for a page whose map is open in full screen over it:
 * the two share one camera, and a page map still drawn behind the dialog would render every turn of it
 * a second time, which nobody sees; its compass rests too, or the two would take turns at the heading,
 * and so do its readers of the map, the relief and the ground.
 */
@Composable
internal fun RouteMap(
    points: List<TrackPointEntity>,
    camera: MapCamera,
    tilt: MapTilt,
    style: MapStyle,
    description: String,
    follow: Boolean,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    controlsInset: Modifier = Modifier,
    photos: List<TrackPhotoEntity> = emptyList(),
    interactive: Boolean = true,
    fitBottomInset: Dp = 0.dp,
    position: PhoneFix? = null,
    shape: Shape = RoundedCornerShape(PwnCorners.medium),
    drawn: Boolean = true,
) {
    val world = rememberWorldMap()
    val relief = rememberWorldRelief()
    val towns = rememberWorldTowns()
    val forest = rememberWorldForest()
    // Null where the renderer will not build the shaders: without the ground's the map is drawn flat,
    // in lines and fills without the dots, and without the relief's own the land is flat too.
    val tilted = remember { GroundShading.createOrNull() }
    val shading = remember { ReliefShading.createOrNull() }
    val dotSpacing = with(LocalDensity.current) { DOT_SPACING.toPx() }
    // Everything from here on draws [fixes], the ones the arrays beside them were worked out for,
    // and never [points] as just handed in: see [rememberDrawnRoute].
    val walk = rememberDrawnRoute(points, style)
    val fixes = walk.points
    val route = walk.route
    val lift = walk.lift
    // The phone's longitude is unwrapped against the route's first fix, as the route's own are.
    val origin = fixes.firstOrNull()?.displayLon
    // Tilted, the map turns with the phone, so the box framed is the one that fits whichever way it faces.
    val bounds = remember(route, position, origin, tilted) {
        routeBounds(route).spanning(position?.let { viewAround(it.lat, it.lon, origin ?: it.lon) })
            ?.let { if (tilted != null) it.anyTurn() else it }
    }
    val marks = remember(photos, fixes) { projectPhotos(photos, fixes) }
    // Blended once per new fix rather than on every frame a pinch draws.
    val ink = walk.ink
    val labels = rememberPhotoLabels(photos.size)
    val markFill = MaterialTheme.colorScheme.primary
    val markInk = MaterialTheme.colorScheme.onPrimary
    val path = remember { Path() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // What the camera frames and zooms about: the canvas less the strip covered at its foot. The
    // drawing below is laid on a canvas stretched up by that strip, which puts the camera's middle
    // in the middle of the part left showing; MapCamera itself knows nothing of insets.
    val covered = with(LocalDensity.current) { fitBottomInset.toPx() }
    val frame = framed(canvasSize, covered)
    if (tilted != null && interactive && drawn) {
        val lat = fixes.firstOrNull()?.displayLat ?: position?.lat
        val lon = fixes.firstOrNull()?.displayLon ?: position?.lon
        // A degree of position changes the declination by a fraction of a degree: worth asking again past that.
        // The map turns with the phone while the camera follows; zoomed or dragged by hand it holds
        // still, and the arrow on the position turns instead.
        FollowCompass(tilt, remember(lat?.roundToInt(), lon?.roundToInt()) { mapDeclination(lat, lon) }) {
            !camera.manual
        }
    }

    // The route the camera was last framed on while following, or null once it stopped following,
    // told by the route the fixes themselves belong to: those arrive a moment after a route is
    // picked, and it is their box that is framed. A new route, or the way back from a view set by
    // hand, is travelled to; a fix arriving on the route already framed is snapped to, or a
    // recording would trail its line by a spring at every fix. With no fix to frame, the view stays
    // where it was, and only a camera with no view yet is shown the whole world.
    // The first route this map frames is snapped to as well, unless the view was set by hand before
    // it: the page opens on that world view while the fixes load, and travelling from there would
    // play every visit to the page as a long zoom. A map with no route but the phone's position
    // frames that under a key of its own, so the phone is followed fix by fix in the same way.
    val routeId = fixes.firstOrNull()?.trackId ?: HERE_FRAME.takeIf { position != null }
    var framedRoute by remember { mutableStateOf<Long?>(null) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(bounds, frame, follow, camera.manual, camera.zoomed) {
        camera.restrict(bounds, frame)
        when {
            !follow || camera.manual -> {
                framedRoute = null
                if (camera.manual) placed = true
            }

            bounds == null -> if (camera.pixelsPerUnit <= 0.0) camera.fitTo(null, frame)

            // Zoomed by hand while following: the zoom is the person's, and only the middle follows —
            // the phone where there is one, else the route.
            camera.zoomed -> {
                val here = position
                if (here != null) {
                    val x = mercatorX(unwrapLongitude(here.lon, origin ?: here.lon))
                    camera.centreOn(x, mercatorY(here.lat), frame)
                } else {
                    camera.centreOn(bounds.center.x.toDouble(), bounds.center.y.toDouble(), frame)
                }
            }

            framedRoute == routeId -> camera.fitTo(bounds, frame)

            else -> {
                if (placed) camera.glideTo(bounds, frame) else camera.fitTo(bounds, frame)
                framedRoute = routeId
                placed = true
            }
        }
    }

    // A level is read for the part of the world in sight, and re-read when the zoom crosses a
    // boundary or the view leaves what was read. The one in hand keeps drawing meanwhile, so a
    // boundary shows as detail arriving rather than as the map blinking out. Conflated, because
    // during a pinch the view changes faster than the asset can be walked. Tilted, what is in sight
    // reaches as far as the ground the camera looks across. Blank, the map reads nothing, nor do the
    // relief's and the ground's readers below: the full-screen map over it reads for the same camera,
    // and these would decode every view of it a second time. What they hold is kept for the way back.
    var level by remember(world) { mutableStateOf<MapLevel?>(null) }
    LaunchedEffect(world, tilted, drawn) {
        if (!drawn) return@LaunchedEffect
        val map = world ?: return@LaunchedEffect
        snapshotFlow { map.levelFor(camera.degreesPerPixel()) to camera.viewport(canvasSize) }
            .conflate()
            .collect { (index, view) ->
                val wanted = if (tilted != null) view.tiltReach() else view
                val seen = if (tilted != null) camera.limit.tiltLimit(view) else camera.limit
                val held = level
                if (held != null && held.index == index && held.covers.holds(wanted.intersect(seen))) {
                    return@collect
                }
                // Reading a patch still costs one pass over the whole level, so the patch has to
                // be big enough that panning almost never asks for another: a few hundred
                // kilometres, and never wider than the camera can see from where it may go.
                val span = mapUnitsFor(MAP_READ_METRES, view.center.y.toDouble()).toFloat()
                val region = wanted.inflate(maxOf(view.width, view.height, span)).intersect(seen)
                level = withContext(Dispatchers.Default) { map.level(index, region) }
            }
    }

    // The relief, shaded for the part of the world in sight and a margin around it, at the level
    // whose cells are a few pixels across. The same bargain as the lines: the picture in hand keeps
    // drawing while the next is shaded, and is shaded again only once the view leaves what was asked
    // for or the zoom wants other cells. What was asked is kept even when it came back empty, or a pan
    // across the sea would shade the same empty water at every frame.
    // Lit from the upper left of the screen whichever way the map is turned, as a shaded map must be:
    // lit from below, a ridge reads as a valley. The light goes round with the map in steps, so a
    // phone swaying in the hand does not shade the relief again at every degree.
    var lightFrom by remember { mutableFloatStateOf(RELIEF_LIGHT_FROM) }
    LaunchedEffect(tilt) {
        snapshotFlow { normalisedBearing((tilt.heading + RELIEF_LIGHT_FROM).toDouble()) }.collect { wanted ->
            if (bearingGap(wanted, lightFrom) >= RELIEF_LIGHT_STEP) lightFrom = wanted
        }
    }
    var reliefImage by remember(relief, style) { mutableStateOf<ReliefImage?>(null) }
    // The ground under every fix, for the tilted walk: once a relief, not at every frame of a turn.
    val floors = remember(route, reliefImage) { routeFloors(route, reliefImage?.cells) }
    LaunchedEffect(relief, style, shading, tilted, drawn) {
        val source = relief ?: return@LaunchedEffect
        if (!drawn || (shading == null && tilted == null)) return@LaunchedEffect
        var asked: Pair<Pair<Int, Float>, Rect>? = null
        snapshotFlow { Triple(source.levelFor(camera.degreesPerPixel()), camera.viewport(canvasSize), lightFrom) }
            .conflate()
            .collect { (index, view, light) ->
                if (canvasSize.minDimension <= 0f) return@collect
                val seen = if (tilted != null) camera.limit.tiltLimit(view) else camera.limit
                val wanted = (if (tilted != null) view.tiltReach() else view).intersect(seen)
                val last = asked
                if (last != null && last.first == (index to light) && last.second.holds(wanted)) return@collect
                val region = wanted.inflate(maxOf(view.width, view.height) * RELIEF_MARGIN).intersect(seen)
                asked = (index to light) to region
                reliefImage = withContext(Dispatchers.Default) { source.render(region, index, style.relief, light) }
            }
    }

    // What lies under each dot, for the ground in sight and a margin round it, classified at the
    // lattice the zoom draws and read from the map level in hand: again when the lattice leaves the
    // levels the picture serves (see servesLattice), the view leaves what was classified, or a finer
    // map level arrives.
    var ground by remember(world, towns, forest) { mutableStateOf<GroundImage?>(null) }
    LaunchedEffect(world, towns, forest, tilted, dotSpacing, drawn) {
        if (tilted == null || !drawn) return@LaunchedEffect
        var classified: MapLevel? = null
        snapshotFlow { Triple(level, dotLevel(camera.pixelsPerUnit, dotSpacing), camera.viewport(canvasSize)) }
            .conflate()
            .collect { (map, latticeLevel, view) ->
                if (map == null || canvasSize.minDimension <= 0f || camera.pixelsPerUnit <= 0.0) return@collect
                val seen = camera.limit.tiltLimit(view)
                val wanted = view.tiltReach().intersect(seen)
                val held = ground
                val holds = held != null && servesLattice(held.level, latticeLevel) && held.covers.holds(wanted)
                if (holds && classified === map) return@collect
                val region = wanted.inflate(view.width * GROUND_MARGIN).intersect(seen)
                ground = withContext(Dispatchers.Default) {
                    classifyGround(map, towns, forest, region, groundTexel(latticeLevel), latticeLevel)
                }
                classified = map
            }
    }

    Box(modifier) {
        Canvas(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(if (tilted != null) style.dots.paper else style.water)
                .semantics { contentDescription = description }
                .onSizeChanged { canvasSize = it.toSize() }
                .then(
                    when {
                        !interactive -> Modifier
                        tilted != null -> Modifier.tiltGestures(camera, tilt, covered)
                        else -> Modifier.mapGestures(camera, covered)
                    },
                ),
        ) {
            // Blank, it reads nothing a turn or a pinch changes, so neither redraws it.
            if (!drawn) return@Canvas
            inset(0f, -covered, 0f, 0f) {
                if (tilted != null) {
                    val view = tiltCamera(camera, tilt, size, reliefImage)
                    val heights = reliefImage?.cells
                    ground?.let { with(tilted) { drawGround(it, reliefImage, view, style.dots) } }
                    val picture = ground
                    if (picture != null) level?.let { drawRiversTilted(it, view, heights, style.dotPens, picture) }
                    drawRouteTilted(fixes, route, lift, floors, view, ink, style)
                    drawPhotoMarksTilted(marks, labels, view, heights, markFill, markInk, style.land)
                } else {
                    level?.let { map ->
                        drawWorld(map, camera, path, style.pens) {
                            val image = reliefImage
                            if (image != null && shading != null) with(shading) { drawRelief(image, camera) }
                        }
                        towns?.let { drawTowns(it, camera, style.town) }
                    }
                    drawRoute(fixes, route, camera, path, style, ink)
                    drawPhotoMarks(marks, labels, camera, markFill, markInk, style.land)
                    position?.let { drawPosition(it, origin ?: it.lon, camera, markFill, style.land) }
                }
            }
        }
        // The phone and the way it faces, tilted, on a layer of their own: the compass turns the arrow
        // many times a second, and drawn with the map every turn of it would re-project the ground, the
        // rivers and the walk underneath for an arrow's sake. While the camera follows, the map turns
        // with the phone and both layers redraw; held still by hand, only this one does.
        if (tilted != null && position != null && drawn) {
            Canvas(Modifier.matchParentSize().clip(shape)) {
                inset(0f, -covered, 0f, 0f) {
                    val view = tiltCamera(camera, tilt, size, reliefImage)
                    val heights = reliefImage?.cells
                    val from = origin ?: position.lon
                    drawPositionTilted(position, from, view, heights, markFill, style.land, tilt.facing)
                }
            }
        }
        if (interactive) {
            // Nothing to go back to before the first fix or position: the world view has no button.
            val fitLabel = when {
                bounds == null -> null
                fixes.isEmpty() -> stringResource(R.string.track_map_follow_here)
                else -> stringResource(R.string.track_map_fit)
            }
            MapControls(
                camera = camera,
                expanded = expanded,
                onToggle = onToggle,
                fitLabel = fitLabel,
                modifier = Modifier.align(Alignment.TopEnd).then(controlsInset),
            )
            // Zoomed about the middle of the map, as a pinch with its fingers there would be, and
            // standing on the edge of whatever covers the map's foot rather than under it.
            ZoomControls(
                onZoom = { factor -> camera.zoomBy(factor, frame) },
                modifier = Modifier.align(Alignment.BottomEnd).then(controlsInset).padding(bottom = fitBottomInset),
            )
        }
    }
}

/** [this] box grown to the square its diagonal spans: what stays framed whichever way the map is turned. */
private fun Rect.anyTurn(): Rect {
    val half = sqrt(width * width + height * height) / 2
    return Rect(center.x - half, center.y - half, center.x + half, center.y + half)
}

/**
 * The tilted map's hands: one finger drags the ground under it, two pinch to zoom about the middle,
 * twist to turn the map — unless the phone's compass is turning it — and, drawn up or down together, tip
 * the camera between nearly overhead and nearly level with the ground. A double tap zooms in.
 *
 * Which a two-finger move is, is told once, by its first stretch: mostly up or down with no pinch in
 * it, it tips; anything else pinches, twists and drags for the rest of the touch, as a map should.
 *
 * Both that stretch and a drag are measured along the way the fingers have come, not by one event's
 * step of it. A finger that only shifted as it tapped, or as its neighbour lifted off a pinch, is
 * not a drag — counted as one, it would stop the map following the walker — and two fingers drawn
 * slowly still tip: at a few pixels an event no single step is long enough to tell, and the move
 * would fall to the pinch and the twist. See [dragsMap] and [tipsCamera].
 */
private fun Modifier.tiltGestures(camera: MapCamera, tilt: MapTilt, covered: Float): Modifier =
    pointerInput(camera, tilt, covered) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var tipping: Boolean? = null
            // The two fingers' way and pinch since the second came down, until the move is told.
            var pairPath = Offset.Zero
            var pairPinch = 1f
            // One finger's way since it was last alone, until it is far enough to be a drag.
            var dragPath = Offset.Zero
            var dragging = false
            do {
                val event = awaitPointerEvent()
                val fingers = event.changes.count { it.pressed }
                val pan = event.calculatePan()
                val zoom = event.calculateZoom()
                if (fingers >= 2) {
                    // The finger left when one lifts starts its own way from there.
                    dragPath = Offset.Zero
                    dragging = false
                    if (tipping == null) {
                        pairPath += pan
                        pairPinch *= zoom
                        tipping = tipsCamera(pairPath, pairPinch, slop)
                    }
                } else if (!dragging) {
                    dragPath += pan
                    dragging = dragsMap(dragPath, slop)
                }
                if (fingers >= 2 && tipping == true) {
                    tilt.pitch = (tilt.pitch + pan.y * PITCH_PER_PX).coerceIn(MIN_PITCH, MAX_PITCH)
                } else {
                    // Only one finger drags: two pinch about the middle, and their drift is not a drag
                    // that would stop the map following and turning with the phone.
                    val drag = if (fingers == 1 && dragging) pan else Offset.Zero
                    moveTilted(camera, tilt, event.calculateCentroid(), drag, zoom, event.calculateRotation(), covered)
                }
                // A finger still within its slop is left to the tap detector below, which gives up
                // on a touch whose move was taken: a double tap that slid a pixel still zooms.
                val taken = fingers >= 2 || dragging
                event.changes.forEach { if (taken && it.positionChanged()) it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }.pointerInput(camera, covered) {
        detectTapGestures(onDoubleTap = { camera.zoomBy(MAP_ZOOM_STEP, framed(size.toSize(), covered)) })
    }

/**
 * Whether one finger has come far enough from where it landed — [travelled], against the system's
 * touch [slop] — to be dragging the map rather than tapping it, as the flat map's own detector waits.
 */
internal fun dragsMap(travelled: Offset, slop: Float): Boolean = travelled.getDistance() > slop

/**
 * What a two-finger move is, once its middle has come half the touch [slop] since the second finger
 * landed: true when it tips the camera — [travelled] runs [TIP_STEEPNESS] times as far up or down as
 * across, and the fingers have drawn no more than [TIP_PINCH] together or apart, [pinch] being the
 * share their spread has changed by — false when it pinches, twists and drags instead, and null
 * while it has not come far enough to say.
 */
internal fun tipsCamera(travelled: Offset, pinch: Float, slop: Float): Boolean? =
    if (travelled.getDistance() <= slop / 2) {
        null
    } else {
        abs(travelled.y) > abs(travelled.x) * TIP_STEEPNESS && abs(pinch - 1f) < TIP_PINCH
    }

/**
 * A pinch, a twist and a drag on the tilted map: the zoom about the middle, which keeps the map
 * following, the twist as a turn, and for a drag, [pan] not zero, the ground that lay under the finger
 * before it moved brought under it again — on the canvas the map is drawn on, which reaches down under
 * the covered strip.
 */
private fun PointerInputScope.moveTilted(
    camera: MapCamera,
    tilt: MapTilt,
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    rotation: Float,
    covered: Float,
) {
    val frame = framed(size.toSize(), covered)
    // The compass holds the heading only while the camera follows: dragged still, the map turns by hand.
    if (!tilt.compass || camera.manual) tilt.heading = (tilt.heading - rotation).mod(FULL_TURN_DEGREES)
    if (zoom != 1f) camera.zoomBy(zoom, frame)
    if (centroid == Offset.Unspecified || pan == Offset.Zero) return
    val canvas = Size(size.width.toFloat(), size.height + covered)
    val view =
        TiltCamera(camera.centerX, camera.centerY, camera.pixelsPerUnit, tilt.heading, tilt.pitch, canvas, 0f, 0f)
    val before = view.groundUnder(centroid.x - pan.x, centroid.y + covered - pan.y)
    val after = view.groundUnder(centroid.x, centroid.y + covered)
    if (before != null && after != null) camera.shift(before.x - after.x, before.y - after.y, frame)
}

/** A two-finger move tips the camera when its first stretch runs this many times as far up or down as across. */
private const val TIP_STEEPNESS = 2f

/** ...and pinches no more than this share. */
private const val TIP_PINCH = 0.02f

/** Degrees the camera tips for each pixel two fingers are drawn up or down. */
private const val PITCH_PER_PX = 0.15f

private const val FULL_TURN_DEGREES = 360f

/** [size] less the strip [covered] at its foot: the part of a map its route is framed in. */
private fun framed(size: Size, covered: Float): Size = Size(size.width, (size.height - covered).coerceAtLeast(0f))

/**
 * Pinch, drag and double-tap, measured against the same frame the camera fits the route to, so the
 * point under the fingers is the point that stays under them even with a strip covered.
 */
private fun Modifier.mapGestures(camera: MapCamera, covered: Float): Modifier =
    pointerInput(camera, covered) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            camera.transform(centroid, pan, zoom, framed(size.toSize(), covered))
        }
    }.pointerInput(camera, covered) {
        detectTapGestures(onDoubleTap = {
            camera.transform(it, Offset.Zero, MAP_ZOOM_STEP, framed(size.toSize(), covered))
        })
    }

/**
 * Each photograph's number, laid out once rather than on every frame a pinch draws: a disc's figure
 * in the bold small label the photographs' own tiles use.
 */
@Composable
private fun rememberPhotoLabels(count: Int): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    return remember(count, measurer, style) { List(count) { measurer.measure(photoNumber(it + 1), style) } }
}

/**
 * Back to following — the route, or the phone where no route is drawn, as [fitLabel] says, and
 * only once the view was moved by hand and there is something to follow — and in or out of full
 * screen where the map has a [onToggle] for it. The label promises the route and not the whole of
 * it: one wider than the farthest view ([MAP_FARTHEST_ZOOM]) comes back framed about its middle,
 * with its ends past the edges. Two buttons at most, and only when they mean
 * something. No gap is set between them: each button's 48dp target already stands a little
 * outside its drawn 40dp, and a gap on top of that would only push the second one further along the map.
 */
@Composable
private fun MapControls(
    camera: MapCamera,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    fitLabel: String?,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        if (camera.manual && fitLabel != null) {
            MapButton(Icons.Outlined.MyLocation, fitLabel, camera::follow)
        }
        onToggle?.let { toggle ->
            MapButton(
                if (expanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                stringResource(if (expanded) R.string.track_map_close else R.string.track_map_expand),
                toggle,
            )
        }
    }
}

/**
 * Minus and plus: the zoom a pinch gives, for one finger, a keyboard or a switch. Side by side rather
 * than stacked, so that in the shorter map a window on its side gets they stay clear of the buttons
 * in the top corner.
 */
@Composable
private fun ZoomControls(onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier) {
        MapButton(Icons.Outlined.Remove, stringResource(R.string.a11y_zoom_out)) { onZoom(1f / MAP_ZOOM_STEP) }
        MapButton(Icons.Outlined.Add, stringResource(R.string.a11y_zoom_in)) { onZoom(MAP_ZOOM_STEP) }
    }
}

/**
 * Material's small tonal icon button at its own size: a 40dp button with a 24dp icon inside a 48dp
 * target. Pinned to 36dp, the target would shrink with it, and on a map the Canvas under the
 * button takes every touch that misses.
 */
@Composable
private fun MapButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick) { Icon(icon, label, tint = PwnMuted) }
}

/** How far one tap of plus, minus or a double tap zooms: twice as close, or twice as far. */
private const val MAP_ZOOM_STEP = 2f

/** How much of the view's own size the relief is shaded past it on every side. */
private const val RELIEF_MARGIN = 0.2f

/** How much of the view's own size the ground under the dots is classified past it on every side. */
private const val GROUND_MARGIN = 0.25f

private fun DrawScope.drawRoute(
    points: List<TrackPointEntity>,
    route: DoubleArray,
    camera: MapCamera,
    path: Path,
    style: MapStyle,
    ink: RouteInk,
) {
    if (points.isEmpty() || camera.pixelsPerUnit <= 0.0) return
    fun at(index: Int) = camera.screenPosition(route[index * 2], route[index * 2 + 1], size)

    // The ground's own colour under the whole line, a little wider than it. The relief tints and
    // shades the land from cream to slate, and the line owes its 3:1 to whatever it is drawn on: on
    // its casing it is always drawn on the land colour RouteInkTest measures it against. Laid whole
    // before any of the line, so no stretch's casing cuts into the stretch before it.
    traceRoute(points, ::at, path, ink, casing = style.land)
    traceRoute(points, ::at, path, ink)
    drawRouteMarks(points, route, camera, style)
}

/**
 * The line through the fixes as one smooth curve: a quadratic from the middle of each stride to the
 * middle of the next, bending at the fix between them, so it has no corner anywhere and each piece
 * leaves in the direction the last one arrived. Each piece takes its fix's colour and width from
 * [ink], or [casing] and the casing's width for the ground under the line. Pieces of one ink are one
 * path; the ink steps only where [routeInk] fades it, and a step's round cap meets the next piece's
 * on the same middle, so the joins do not show.
 *
 * Nothing that falls outside the canvas, and no fix nearer than [ROUTE_STEP] to the last one kept: a
 * recording holds thousands of fixes, the whole line is replayed on every frame of a pinch or a pan,
 * and at the zoom that frames a walk most of them land on a pixel already drawn; leaving them out
 * also irons the receiver's jitter out of the curve. A pause ends the curve, and the next starts at
 * the fix after it: see [joinsPrevious].
 */
private fun DrawScope.traceRoute(
    points: List<TrackPointEntity>,
    at: (Int) -> Offset,
    path: Path,
    ink: RouteInk,
    casing: Color? = null,
) {
    val tracer = RouteTracer(this, path, ink, casing)
    var previous = Offset.Infinite
    for (index in points.indices) {
        val here = at(index)
        if (index > 0 &&
            drawsSegment(points, index, previous, here)
        ) {
            tracer.bendTo(here, index)
        } else {
            tracer.start(here, index)
        }
        previous = here
    }
    tracer.finish()
}

/**
 * The pen [traceRoute] draws with. It holds the fix the curve will bend at next — the corner — and
 * where the last piece ended, halfway to it; a piece is stroked once its ink changes or the line ends.
 */
private class RouteTracer(
    private val scope: DrawScope,
    private val path: Path,
    private val ink: RouteInk,
    private val casing: Color?,
) {
    private val step = with(scope) { ROUTE_STEP.toPx() }
    private val casingWidth = with(scope) { ROUTE_CASING_WIDTH.toPx() }
    private var color = 0
    private var width = 0f
    private var open = false
    private var pen = Offset.Zero
    private var corner = Offset.Zero
    private var cornerFix = -1
    private var curving = false

    /** A curve starts at [here], fix [fix]: at the walk's first fix, after a pause, or back on the canvas. */
    fun start(here: Offset, fix: Int) {
        straighten()
        if (open) path.moveTo(here.x, here.y)
        pen = here
        corner = here
        cornerFix = fix
    }

    /** The curve runs on toward [here], fix [fix], bending at the corner on the way. */
    fun bendTo(here: Offset, fix: Int) {
        if (abs(here.x - corner.x) < step && abs(here.y - corner.y) < step) return
        val middle = Offset((corner.x + here.x) / 2, (corner.y + here.y) / 2)
        inkFor(cornerFix)
        if (curving) path.quadraticTo(corner.x, corner.y, middle.x, middle.y) else path.lineTo(middle.x, middle.y)
        open = true
        curving = true
        pen = middle
        corner = here
        cornerFix = fix
    }

    fun finish() {
        straighten()
        flush()
    }

    // The curve's last half stride, straight into the last fix kept.
    private fun straighten() {
        if (curving) {
            inkFor(cornerFix)
            path.lineTo(corner.x, corner.y)
            open = true
        }
        curving = false
    }

    private fun inkFor(fix: Int) {
        val nextColor = casing?.toArgb() ?: ink.colors[fix]
        val nextWidth = if (casing != null) casingWidth else ink.widths[fix] * scope.density
        if (open && nextWidth == width && sameInk(nextColor, color)) return
        flush()
        color = nextColor
        width = nextWidth
        path.moveTo(pen.x, pen.y)
    }

    private fun flush() {
        if (open) {
            scope.drawPath(path, Color(color), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        path.rewind()
        open = false
    }
}

/**
 * Whether two inks are the same to the eye: no channel more than [ROUTE_INK_TOLERANCE] apart. A fade
 * moves every channel a little at each fix, and a path a fix would be a draw call a fix.
 */
private fun sameInk(a: Int, b: Int): Boolean {
    var shift = 0
    var same = true
    while (same && shift < Int.SIZE_BITS) {
        same = abs((a ushr shift and 0xFF) - (b ushr shift and 0xFF)) <= ROUTE_INK_TOLERANCE
        shift += Byte.SIZE_BITS
    }
    return same
}

private const val ROUTE_INK_TOLERANCE = 3

/**
 * The least a fix has to stand from the last one kept to bend the curve. At 2dp the receiver's few
 * metres of wander would still show as a tremor along a straight street at the zoom that frames a walk.
 */
private val ROUTE_STEP = 4.dp

/**
 * The dots over the line: every stop, every environment spike, and the two ends — a second walk
 * over the route, and its own function so that neither pass has to be read with the other in mind.
 * [points] is not empty and the camera has a scale; [drawRoute] checked both before calling.
 */
private fun DrawScope.drawRouteMarks(
    points: List<TrackPointEntity>,
    route: DoubleArray,
    camera: MapCamera,
    style: MapStyle,
) {
    fun at(index: Int) = camera.screenPosition(route[index * 2], route[index * 2 + 1], size)

    // A stop is one mark, not the hundred fixes it was recorded as: consecutive marks of a kind
    // landing on the same pixel are the same dot drawn again.
    var lastStop = Offset.Infinite
    var lastSpike = Offset.Infinite
    points.indices.forEach { index ->
        val center = at(index)
        if (offCanvas(center, center, size)) return@forEach
        if (points[index].movementType == RouteMovement.STOPPED.name && apart(lastStop, center)) {
            drawCircle(style.stopped, radius = MAP_STOP_RADIUS.toPx(), center = center)
            lastStop = center
        }
        if (points[index].environmentSpike && apart(lastSpike, center)) {
            drawCircle(style.spike, radius = 2.5.dp.toPx(), center = center)
            lastSpike = center
        }
    }
    drawCircle(style.start, radius = MAP_END_RADIUS.toPx(), center = at(0))
    if (points.size > 1) {
        drawCircle(style.end, radius = MAP_END_RADIUS.toPx(), center = at(points.lastIndex))
    }
}

/**
 * Whether the line runs into fix [index] from the fix before it: never into the first, and never
 * across a pause — the first fix after one starts the line again, since the walker may have gone
 * anywhere while the recording stood still.
 */
internal fun joinsPrevious(points: List<TrackPointEntity>, index: Int): Boolean =
    index > 0 && !points[index].segmentStart

/**
 * Whether the segment that ends at fix [index], from [start] to [end] on the canvas, is drawn at all:
 * not across a pause, whichever colours the line is drawn in, and not wholly off the canvas.
 */
private fun DrawScope.drawsSegment(points: List<TrackPointEntity>, index: Int, start: Offset, end: Offset): Boolean =
    joinsPrevious(points, index) && !offCanvas(start, end, size)

/**
 * Each photograph where it was taken: a pale wedge for the way the camera faced, then a numbered
 * disc ringed in the colour of the ground, so a disc lying on a line of its own colour still reads
 * as a disc. Every wedge is laid before any disc, so no wedge covers a neighbour's number.
 */
private fun DrawScope.drawPhotoMarks(
    marks: List<PhotoMark>,
    labels: List<TextLayoutResult>,
    camera: MapCamera,
    fill: Color,
    ink: Color,
    ring: Color,
) {
    if (marks.isEmpty() || camera.pixelsPerUnit <= 0.0) return
    val reach = PHOTO_VIEW_LENGTH.toPx()
    marks.forEach { mark ->
        val bearing = mark.bearingDeg ?: return@forEach
        drawArc(
            color = fill.copy(alpha = PHOTO_VIEW_ALPHA),
            startAngle = viewWedgeStart(bearing),
            sweepAngle = PHOTO_VIEW_SPREAD,
            useCenter = true,
            topLeft = camera.screenPosition(mark.x, mark.y, size) - Offset(reach, reach),
            size = Size(reach * 2, reach * 2),
        )
    }
    marks.forEach { mark ->
        val label = labels.getOrNull(mark.number - 1) ?: return@forEach
        val center = camera.screenPosition(mark.x, mark.y, size)
        // The disc grows with its figure, so a large font or a three-figure count still fits inside.
        val radius = maxOf(
            PHOTO_MARK_RADIUS.toPx(),
            maxOf(label.size.width, label.size.height) / 2f + PHOTO_MARK_PADDING.toPx(),
        )
        drawCircle(ring, radius = radius + PHOTO_MARK_RING.toPx(), center = center)
        drawCircle(fill, radius = radius, center = center)
        drawText(label, ink, center - Offset(label.size.width / 2f, label.size.height / 2f))
    }
}

/**
 * Where the phone is: a disc in the accent ringed in the colour of the ground, as a photograph's disc
 * is, inside a pale circle as wide as the fix's accuracy — the phone is somewhere in that circle.
 * [origin] is the longitude the route's own are unwrapped against.
 */
private fun DrawScope.drawPosition(fix: PhoneFix, origin: Double, camera: MapCamera, fill: Color, ring: Color) {
    if (camera.pixelsPerUnit <= 0.0) return
    val y = mercatorY(fix.lat)
    val center = camera.screenPosition(mercatorX(unwrapLongitude(fix.lon, origin)), y, size)
    fix.accuracyM?.let { metres ->
        val reach = mapUnitsFor(metres.toDouble(), y) * camera.pixelsPerUnit
        drawCircle(fill.copy(alpha = HERE_HALO_ALPHA), radius = reach.toFloat(), center = center)
    }
    drawCircle(ring, radius = (HERE_RADIUS + HERE_RING).toPx(), center = center)
    drawCircle(fill, radius = HERE_RADIUS.toPx(), center = center)
}

/**
 * Each town as a filled circle or ellipse of its own area, where the map is drawn in fills: from a
 * large country's view in. This map keeps north up, so a
 * town's turn on the map is its turn on the screen.
 */
private fun DrawScope.drawTowns(towns: WorldTowns, camera: MapCamera, color: Color) {
    if (camera.pixelsPerUnit <= 0.0 || camera.degreesPerPixel() > COUNTRY_ZOOM) return
    val view = camera.viewport(size)
    val pixelsPerUnit = camera.pixelsPerUnit.toFloat()
    // Past the antimeridian, as the land is: see [turnsInto].
    for (turn in turnsInto(view)) {
        for (town in 0 until towns.size) {
            val x = towns.x[town] + turn
            val y = towns.y[town]
            val reach = towns.reach[town]
            val onScreenX = x + reach >= view.left && x - reach <= view.right
            val onScreenY = y + reach >= view.top && y - reach <= view.bottom
            // A village seen from a country's width is under a pixel: thousands of them, drawn for nothing.
            val seen = reach * pixelsPerUnit >= MIN_TOWN_PX
            if (!seen || !onScreenX || !onScreenY) continue
            val center = camera.screenPosition(x.toDouble(), y.toDouble(), size)
            val long = reach * pixelsPerUnit
            val short = long * towns.narrowness(town)
            rotate(towns.turnDegrees(town), pivot = center) {
                drawOval(color, topLeft = Offset(center.x - long, center.y - short), size = Size(2 * long, 2 * short))
            }
        }
    }
}

private const val MIN_TOWN_PX = 0.5f
private val HERE_RADIUS = 7.dp
private val HERE_RING = 2.dp
private const val HERE_HALO_ALPHA = 0.16f

/** A photograph's place on the map, in the route's own unwrapped Mercator units, and its number. */
internal data class PhotoMark(val number: Int, val x: Double, val y: Double, val bearingDeg: Float?)

/**
 * Where each photograph goes on the map: projected as [projectRoute] projects the line, longitudes
 * unwrapped against the same first fix, so a photograph taken on a walk across the antimeridian lands
 * beside the line rather than on the far side of the world. Numbered by place in [photos], the ones
 * with no position counted too, so a number on the map is the number the list shows. Those have no
 * place to be drawn and are left off.
 */
internal fun projectPhotos(photos: List<TrackPhotoEntity>, points: List<TrackPointEntity>): List<PhotoMark> {
    val origin = points.firstOrNull()?.displayLon ?: 0.0
    return photos.mapIndexedNotNull { index, photo ->
        val lat = photo.lat
        val lon = photo.lon
        if (lat == null || lon == null) {
            null
        } else {
            PhotoMark(index + 1, mercatorX(unwrapLongitude(lon, origin)), mercatorY(lat), photo.bearingDeg)
        }
    }
}

/**
 * Where a camera's wedge starts, in the canvas's own angles — clockwise from three o'clock — for a
 * bearing clockwise from north: a quarter turn back, then half the spread, so the middle of the wedge
 * points along the bearing on a map with north at the top.
 */
internal fun viewWedgeStart(bearingDeg: Float): Float = bearingDeg - 90f - PHOTO_VIEW_SPREAD / 2

/**
 * A photograph's number as the person reads numbers: the same figures on the map's disc, on the
 * tile's badge and in what a screen reader says, whichever digits the language writes them in.
 */
internal fun photoNumber(number: Int): String = NumberFormat.getIntegerInstance().format(number)

/**
 * How wide a camera's wedge is drawn, in degrees. It says which way, not how much: the camera app
 * does not tell this one which lens took the picture.
 */
internal const val PHOTO_VIEW_SPREAD = 60f

/** How far the wedge reaches from the disc it comes out of. */
private val PHOTO_VIEW_LENGTH = 28.dp
private const val PHOTO_VIEW_ALPHA = 0.3f
private val PHOTO_MARK_RADIUS = 10.dp
private val PHOTO_MARK_PADDING = 3.dp
private val PHOTO_MARK_RING = 1.5.dp

/**
 * Trivial reject: a segment with both ends past the same edge of [bounds] cannot cross it. The
 * margin is the radius of the widest mark drawn, so one centred just outside still shows — read
 * from the same constant the mark is drawn with rather than restated as a number: a dp radius is
 * 12px at density 3, and any number written out here would go stale the next time a mark is resized.
 */
private fun DrawScope.offCanvas(from: Offset, to: Offset, bounds: Size): Boolean {
    val edge = MAP_END_RADIUS.toPx()
    return (from.x < -edge && to.x < -edge) ||
        (from.y < -edge && to.y < -edge) ||
        (from.x > bounds.width + edge && to.x > bounds.width + edge) ||
        (from.y > bounds.height + edge && to.y > bounds.height + edge)
}

/** The widest mark on the route: the dots at its two ends, drawn in [drawRouteMarks]. */
private val MAP_END_RADIUS = 4.dp

/** A stop's dot on the map, and the stops' key in the legend. */
private val MAP_STOP_RADIUS = 3.dp

/**
 * How wide the line is drawn where the walker moved as [movement].
 *
 * Walking, running and a ride differ in more than hue: in lightness several of the pairs are all but
 * equal, and red against green is the pair a red-green colour-blind eye merges. So a run is drawn
 * wider and a ride narrower, each one unbroken line, and the
 * legend's keys are drawn at the same widths, so they can be matched by shape as well as by colour.
 *
 * Widths in dp, converted at draw time, because the ground under them already is: WorldMap.kt
 * strokes every map pen at pen.width.toPx(). As a raw 5px the line would measure 1.90dp on a Pixel 9a
 * and 1.67dp at density 3; at 3dp it is the same line on every screen, and a ride's
 * 2dp still stands on the casing laid under the whole line.
 */
private fun routeWidth(movement: String?): Dp = when (movement) {
    RouteMovement.RUNNING.name -> ROUTE_RUN_WIDTH
    RouteMovement.VEHICLE.name -> ROUTE_RIDE_WIDTH
    else -> ROUTE_WIDTH
}

private val ROUTE_WIDTH = 3.dp
private val ROUTE_RUN_WIDTH = 5.dp
private val ROUTE_RIDE_WIDTH = 2.dp

/** A walking line's width and a dp and a half either side of it; a run's wider line keeps half a dp. */
private val ROUTE_CASING_WIDTH = 6.dp

/** Each fix's piece of line: its colour as ARGB, and its width in dp. */
internal class RouteInk(val colors: IntArray, val widths: FloatArray)

/**
 * The colour and width of the line at each fix: its movement's, faded into the next movement's over
 * the ground either side of a change, so a walk breaking into a run swells and shifts from green to
 * blue over a hundred metres or so rather than at one fix.
 *
 * Each fix's movement ink is blended along the line twice, forward and then back, by the length of
 * each stride against [ROUTE_BLEND_M]: the two passes centre the fade on the change instead of
 * dragging it after it. Colour is blended in Oklab, where halfway between two colours looks halfway
 * and the lightness does not sag in the middle. A stop has no ink of its own and adds no length, so it
 * leaves the line the colour around it — its dot marks it — however many fixes it held. Nor has a
 * stretch the classifier has not decided yet: drawn grey, the few seconds it takes at every change
 * would flash between the two colours, and the legend has no key for it; it is the fade between
 * them instead. Only a line with no movement told at all is drawn in [MapStyle.unknown]. A pause starts the
 * line afresh in the movement after it. Widths are kept to quarter dps, so the line steps in widths
 * the eye cannot tell apart while a fade stays a few dozen paths.
 */
internal fun routeInk(points: List<TrackPointEntity>, route: DoubleArray, style: MapStyle): RouteInk {
    val count = points.size
    if (count == 0) return RouteInk(IntArray(0), FloatArray(0))
    val reach = mapUnitsFor(ROUTE_BLEND_M, route[1])
    val target = Array(count) { index ->
        points[index].movementType.takeIf { it in INKED_MOVEMENTS }?.let { movement ->
            val lab = style.of(movement).convert(ColorSpaces.Oklab)
            floatArrayOf(lab.red, lab.green, lab.blue, routeWidth(movement).value)
        }
    }

    // How far the segment that ends at fix [index] carries the ink toward its own: all the way after
    // a pause, not at all through a stop.
    fun pull(index: Int): Float = when {
        !joinsPrevious(points, index) -> 1f

        points[index].movementType == RouteMovement.STOPPED.name -> 0f

        else -> {
            val length = hypot(route[index * 2] - route[index * 2 - 2], route[index * 2 + 1] - route[index * 2 - 1])
            (1 - exp(-length / reach)).toFloat()
        }
    }

    val blended = Array(count) { FloatArray(INK_CHANNELS) }
    var ink = target.firstNotNullOfOrNull { it } ?: style.unknown.convert(ColorSpaces.Oklab).let {
        floatArrayOf(it.red, it.green, it.blue, ROUTE_WIDTH.value)
    }
    for (index in 0 until count) {
        val own = target[index]
        if (index > 0 && own != null) ink = blend(ink, own, pull(index))
        ink.copyInto(blended[index])
    }
    ink = blended[count - 1]
    for (index in count - 2 downTo 0) {
        ink = blend(ink, blended[index], pull(index + 1))
        ink.copyInto(blended[index])
    }
    return RouteInk(
        IntArray(count) {
            val (l, a, b) = blended[it]
            Color(l, a, b, 1f, ColorSpaces.Oklab).convert(ColorSpaces.Srgb).toArgb()
        },
        FloatArray(count) { (blended[it][INK_CHANNELS - 1] * WIDTH_STEPS_PER_DP).roundToInt() / WIDTH_STEPS_PER_DP },
    )
}

private fun blend(from: FloatArray, to: FloatArray, share: Float): FloatArray =
    FloatArray(INK_CHANNELS) { from[it] + (to[it] - from[it]) * share }

/** The movements with an ink of their own; the rest take the ink around them. */
private val INKED_MOVEMENTS = setOf(RouteMovement.WALKING.name, RouteMovement.RUNNING.name, RouteMovement.VEHICLE.name)

/** Lightness, the two colour axes and the width. */
private const val INK_CHANNELS = 4
private const val WIDTH_STEPS_PER_DP = 4f

/**
 * The ground over which a change of movement fades, each pass. Two passes spread it over about three
 * times this, some hundred metres: at the zoom that frames a walk of a few kilometres, 15 m would
 * make a fade of a dozen pixels that reads as a step. A minute's jog still reaches its own colour.
 */
private const val ROUTE_BLEND_M = 30.0

/** Whether [to] is far enough from [from] to be worth another call into the canvas. */
private fun apart(from: Offset, to: Offset): Boolean =
    abs(to.x - from.x) >= MAP_STEP_PX || abs(to.y - from.y) >= MAP_STEP_PX

private const val MAP_STEP_PX = 1f

/**
 * What the line's colours and strokes stand for. It wraps rather than squeezing: four entries in a
 * long locale or at a large font do not fit one row, and in a Row the last of them would be the one
 * left without room.
 */
@Composable
internal fun RouteLegend(style: MapStyle) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem(style.walk, stringResource(R.string.track_walking_counted), RouteMovement.WALKING)
        LegendItem(style.run, stringResource(R.string.track_running), RouteMovement.RUNNING)
        LegendItem(style.vehicle, stringResource(R.string.track_vehicle), RouteMovement.VEHICLE)
        LegendItem(style.stopped, stringResource(R.string.track_stops), RouteMovement.STOPPED)
    }
}

/** One key and its word. The key is a piece of the line as the map draws it, or a stop's dot. */
@Composable
private fun LegendItem(color: Color, label: String, movement: RouteMovement) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(LEGEND_KEY_WIDTH, LEGEND_KEY_HEIGHT)) {
            if (movement == RouteMovement.STOPPED) {
                drawCircle(color, radius = MAP_STOP_RADIUS.toPx(), center = center)
            } else {
                val width = routeWidth(movement.name).toPx()
                // Inset by half the stroke, so a round end stays inside the key.
                val inset = width / 2
                drawLine(color, Offset(inset, center.y), Offset(size.width - inset, center.y), width, StrokeCap.Round)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private val LEGEND_KEY_WIDTH = 20.dp
private val LEGEND_KEY_HEIGHT = 8.dp

internal fun List<TrackPointEntity>.stopCount(): Int {
    var previousStopped = false
    var count = 0
    forEach {
        val stopped = it.movementType == RouteMovement.STOPPED.name
        if (stopped && !previousStopped) count++
        previousStopped = stopped
    }
    return count
}

/**
 * Everything the map panel is painted with, in one object because the whole set swaps together
 * when the theme does.
 *
 * The tones follow the atlas convention every phone map uses — water behind, land over it, built-up
 * ground a shade apart — so the panel reads as a map rather than as a diagram. The route sits on
 * top and has to stay legible on either ground, which is why its segment colours belong here too:
 * the daylight set is dark ink on paper, the night set is lifted so it does not disappear into the
 * land.
 */
internal class MapStyle(
    val ground: MapGround,
    val walk: Color,
    val run: Color,
    val vehicle: Color,
    val stopped: Color,
    val unknown: Color,
    val spike: Color,
    val start: Color,
    val end: Color,
) {
    /** The water the map is laid on where it is drawn in lines and fills. */
    val water: Color get() = ground.water

    /** The lines and fills, for a phone that will not run the dots' shader. */
    val pens: List<MapPen> get() = ground.pens

    /** The dots, as the map is drawn wherever the shader runs. */
    val dots: MapDots get() = ground.dots

    /** The rivers and streams across the dots. */
    val dotPens: List<MapPen> get() = ground.dots.pens

    /** A town, where the map is drawn in fills. */
    val town: Color get() = ground.town

    /** The ground itself: the land pen, layer 0 in the layers tools/world-map.py writes. */
    val land: Color = pens.first { it.layer == LAND_LAYER }.color

    /** How the relief over that ground is coloured, which the land pen carries. */
    val relief: ReliefPalette = requireNotNull(pens.first { it.layer == LAND_LAYER }.relief) { "the land pen's relief" }

    fun of(movement: String): Color = when (movement) {
        RouteMovement.WALKING.name -> walk
        RouteMovement.RUNNING.name -> run
        RouteMovement.VEHICLE.name -> vehicle
        RouteMovement.STOPPED.name -> stopped
        else -> unknown
    }
}

/**
 * The ground a [MapStyle] draws the route on: the [water] behind lines and fills and their [pens], a
 * [town]'s fill among them, and the [dots] the map is drawn in where the phone runs their shader.
 */
internal class MapGround(val water: Color, val pens: List<MapPen>, val town: Color, val dots: MapDots)

@Composable
internal fun mapStyle(): MapStyle = if (isSystemInDarkTheme()) NIGHT_MAP else DAY_MAP

// How far out each thing is still worth drawing, in degrees per pixel. A phone panel is about a
// thousand pixels wide, so 0.33 is the whole world across it, 0.05 a continent, 0.02 a large
// country, 0.003 a region, 0.0002 a city. Nothing that would turn a continent into scribble
// survives past its own scale — which is the difference between a map and a plot of every line in
// the file. tools/world-map.py leaves a layer out of the levels coarser than its pens' zoom, so a
// pen moved further out here must move there too.
private const val RIVER_ZOOM = 0.05
private const val COUNTRY_ZOOM = 0.02
private const val REGION_ZOOM = 0.006

/** The heights in metres the relief's tints are pinned to, by day and by night alike. Declared before
 * the two styles: a file's top-level values are set in the order they are written. */
private val RELIEF_HEIGHTS = floatArrayOf(-50f, 0f, 200f, 500f, 900f, 1_400f, 2_100f, 3_000f, 4_000f, 5_200f)

// Lowland sage, cream hills, tan mountains, grey rock and snow, shadowed toward slate. Tuned on
// previews drawn with WorldRelief's own shading from the shipped heights: the Alps read at a glance,
// a plain stays quiet under the line.
private val DAY_RELIEF = ReliefPalette(
    heights = RELIEF_HEIGHTS,
    colors = listOf(
        Color(0xFFE4EDD9), Color(0xFFE6EEDB), Color(0xFFE9EFD9), Color(0xFFEFEFD6), Color(0xFFEEE5CC),
        Color(0xFFE5D6BA), Color(0xFFDBC8AB), Color(0xFFD1C5B7), Color(0xFFDEDAD5), Color(0xFFF6F6F6),
    ),
    shadow = Color(0xFF6E7F96),
    light = Color(0xFFFFFFFF),
    shadowStrength = 1.6f,
    lightStrength = 0.7f,
    slopeStrength = 0.45f,
)

// The day's ramp at night: the same heights, dark and close together, lit from the same side.
private val NIGHT_RELIEF = ReliefPalette(
    heights = RELIEF_HEIGHTS,
    colors = listOf(
        Color(0xFF233243), Color(0xFF243344), Color(0xFF273545), Color(0xFF2C3946), Color(0xFF303A46),
        Color(0xFF353C46), Color(0xFF3A3E47), Color(0xFF40434B), Color(0xFF4A4D54), Color(0xFF595C62),
    ),
    shadow = Color(0xFF0A0F16),
    light = Color(0xFF6B7C96),
    shadowStrength = 1.5f,
    lightStrength = 0.6f,
    slopeStrength = 0.45f,
)

// Where the dots' shader runs, the map is dots on paper: the land tinted by the relief, sized by its
// shading, forests green, towns lavender, water blue, rivers and streams rows of dots of their own —
// see MapDots.kt. Without it, it is drawn in lines and fills: land over the water the panel is painted
// with, the relief over the land, then what covers the ground, then the rivers across it, and each
// town a filled circle. The layer numbers are the ones tools/world-map.py writes: land, lake, river,
// glacier, playa, stream. Ice and salt are drawn see-through, so the relief's shading still models
// the ice cap's dome and the basin a salt flat lies in. A great river is drawn from a continent's
// view, a stream only from a region's, and narrower.
internal val DAY_MAP = MapStyle(
    ground = MapGround(
        water = Color(0xFFBBD7EF),
        pens = listOf(
            MapPen(LAND_LAYER, Color(0xFFF7F7F5), fill = true, relief = DAY_RELIEF),
            MapPen(GLACIER_LAYER, Color(0xB8FAFCFF), fill = true),
            MapPen(SALT_LAYER, Color(0x99EFE4CC), fill = true),
            MapPen(LAKE_LAYER, Color(0xFFBBD7EF), fill = true),
            // A shade deeper than the lakes: a line this thin in the lakes' own blue would vanish into
            // the pale green of the lowlands it runs through.
            MapPen(STREAM_LAYER, Color(0xFFA9CCEC), 0.7.dp, upTo = REGION_ZOOM),
            MapPen(RIVER_LAYER, Color(0xFF9CC3EA), 1.3.dp, upTo = RIVER_ZOOM),
        ),
        town = Color(0xBFEDEDE8),
        dots = MapDots(
            paper = Color(0xFFF8F8F5),
            land = DotLand(
                flat = Color(0xFFE4EDD9),
                ink = Color(0xFF66768C),
                inkShare = 0.42f,
                sizes = DotSizes(at = 0.27f, lumAt = 0.9f, gain = 1.4f),
            ),
            // A full blue, a size up from the land's: pale, water would be the one thing on the map nobody sees.
            water = Color(0xFF2F7FD6),
            forest = Color(0xFF5FAE6E),
            town = Color(0xFFA3ABC8),
            ice = Color(0xFFC3D3E6),
            salt = Color(0xFFE0CFA6),
            pens = listOf(
                MapPen(STREAM_LAYER, Color(0xFF4A8FDC), 2.4.dp, upTo = REGION_ZOOM, dots = 3.8.dp),
                MapPen(RIVER_LAYER, Color(0xFF2F7FD6), 3.2.dp, upTo = RIVER_ZOOM, dots = 4.6.dp),
            ),
        ),
    ),
    walk = Color(0xFF1B5E20),
    run = Color(0xFF1565C0),
    vehicle = Color(0xFFC62828),
    // Amber at map weight, not at signal weight. #F9A825 measures 1.84:1 on the land it is drawn
    // on, 1.68:1 on built-up ground and 1.89:1 as the legend key on the page, against the 3:1 WCAG
    // 1.4.11 asks of a graphical object; #B26A00 is 3.95:1, 3.61:1 and 4.06:1 on those same three.
    stopped = Color(0xFFB26A00),
    unknown = Color(0xFF757575),
    spike = Color(0xFF254D9E),
    start = Color(0xFF2E7D32),
    end = Color(0xFF212121),
)

// The night values make a quiet dark map: the
// contrast lives in the water. At night the dots are lighter than the paper, and a lit slope is the
// bigger dot rather than a shadowed one.
internal val NIGHT_MAP = MapStyle(
    ground = MapGround(
        water = Color(0xFF17263C),
        pens = listOf(
            MapPen(LAND_LAYER, Color(0xFF242F3E), fill = true, relief = NIGHT_RELIEF),
            MapPen(GLACIER_LAYER, Color(0x8C4B576A), fill = true),
            MapPen(SALT_LAYER, Color(0x80403E3A), fill = true),
            MapPen(LAKE_LAYER, Color(0xFF17263C), fill = true),
            MapPen(STREAM_LAYER, Color(0xFF1F3858), 0.7.dp, upTo = REGION_ZOOM),
            MapPen(RIVER_LAYER, Color(0xFF26446C), 1.3.dp, upTo = RIVER_ZOOM),
        ),
        town = Color(0xBF2A3547),
        dots = MapDots(
            paper = Color(0xFF1A2230),
            land = DotLand(
                flat = Color(0xFF243344),
                ink = Color(0xFF8D9BB0),
                inkShare = 0.4f,
                sizes = DotSizes(at = 0.26f, lumAt = 0.22f, gain = -1.2f),
            ),
            water = Color(0xFF4A8FE0),
            forest = Color(0xFF3F8F5A),
            town = Color(0xFF6E79A4),
            ice = Color(0xFF8FA2BC),
            salt = Color(0xFF8C7D5E),
            pens = listOf(
                MapPen(STREAM_LAYER, Color(0xFF3F7FCB), 2.4.dp, upTo = REGION_ZOOM, dots = 3.8.dp),
                MapPen(RIVER_LAYER, Color(0xFF4A8FE0), 3.2.dp, upTo = RIVER_ZOOM, dots = 4.6.dp),
            ),
        ),
    ),
    walk = Color(0xFF66BB6A),
    run = Color(0xFF64B5F6),
    vehicle = Color(0xFFEF5350),
    stopped = Color(0xFFFFCA28),
    unknown = Color(0xFFBDBDBD),
    spike = Color(0xFF9BB9F6),
    start = Color(0xFF81C784),
    end = Color(0xFFFAFAFA),
)

/**
 * Mercator once per point, not once per frame; a recording redraws far more often than it grows.
 *
 * Longitudes are unwrapped against the first fix — see [unwrapLongitude] — so a route across the
 * antimeridian is drawn as the few metres it is rather than as a leg around the world, and its box,
 * taken from these values, frames a walk rather than the planet. Distance, splits and simplification
 * unwrap the same way.
 */
internal fun projectRoute(points: List<TrackPointEntity>): DoubleArray {
    val projected = DoubleArray(points.size * 2)
    val origin = points.firstOrNull()?.displayLon ?: 0.0
    points.forEachIndexed { index, point ->
        projected[index * 2] = mercatorX(unwrapLongitude(point.displayLon, origin))
        projected[index * 2 + 1] = mercatorY(point.displayLat)
    }
    return projected
}

/** The box the whole route fits in, or null when there is no route to frame yet. */
internal fun routeBounds(route: DoubleArray): Rect? {
    if (route.isEmpty()) return null
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (index in route.indices step 2) {
        minX = minOf(minX, route[index])
        maxX = maxOf(maxX, route[index])
        minY = minOf(minY, route[index + 1])
        maxY = maxOf(maxY, route[index + 1])
    }
    return Rect(minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat())
}

private val TrackPointEntity.displayLat: Double get() = correctedLat ?: lat
private val TrackPointEntity.displayLon: Double get() = correctedLon ?: lon

private fun Context.sendToService(action: String) =
    ContextCompat.startForegroundService(this, Intent(this, StepService::class.java).setAction(action))
