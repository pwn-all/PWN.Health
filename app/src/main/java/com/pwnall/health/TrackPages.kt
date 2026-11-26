package com.pwnall.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * What the screen knows right now, opened from the signal pill over the map: the GPS first — how
 * good the position is, or why there is none — and then the walk, the one recording or else the
 * last one taken, with its details: the tiles, the key to
 * the line, the telemetry, the splits and the export. [state] is the walk's own: idle while [route]
 * is the last one taken rather than one still open. [points] and [rejected] are [allPoints] parted
 * by whether the filter accepted them.
 *
 * [onSaved] and [onFailed] carry the words an export ended on up to the screen, which shows them
 * over the middle of whatever is standing.
 */
@Composable
internal fun TrackInfoPage(
    signal: GpsSignal,
    route: TrackEntity?,
    state: RecordState,
    allPoints: List<TrackPointEntity>,
    points: List<TrackPointEntity>,
    rejected: List<TrackPointEntity>,
    kcal: Double,
    imperial: Boolean,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PwnTopBar(stringResource(R.string.track_info_title), onBack)
        PageColumn {
            GpsSection(signal, waiting = state == RecordState.RECORDING && points.isEmpty(), imperial)
            route?.let { RouteFigures(it, state, allPoints, points, rejected, kcal, imperial, onSaved, onFailed) }
        }
    }
}

/**
 * The position in words beside its bars, and how close it is — or, with location off or not
 * granted, why there is none and the way to the switch: the device's for location turned off, the
 * app's own page in Settings for the permission. [waiting] is a walk recording with no fix
 * on its line yet; a paused one is waiting for nothing, since it keeps no fix until it is resumed.
 */
@Composable
private fun GpsSection(signal: GpsSignal, waiting: Boolean, imperial: Boolean) {
    val context = LocalContext.current

    // A device whose Settings has no such page is a device, not a bug: the tap does nothing rather
    // than taking the screen down with it.
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { if (it !is ActivityNotFoundException) throw it }
    }
    // The note names the accuracy each count of bars stands for, in the unit the pill speaks.
    val accuracy = if (imperial) R.string.track_accuracy_value_ft else R.string.track_accuracy_value
    TrackSectionTitle(
        stringResource(R.string.track_signal_title),
        stringResource(
            R.string.track_signal_note,
            stringResource(accuracy, heightValue(GOOD_ACCURACY_M.toDouble(), imperial)),
            stringResource(accuracy, heightValue(FAIR_ACCURACY_M.toDouble(), imperial)),
        ),
    )
    Spacer(Modifier.height(4.dp))
    when {
        !signal.locationOn -> {
            Message(stringResource(R.string.track_location_off))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.track_location_settings)) }
        }

        !signal.granted -> {
            Message(stringResource(R.string.track_permission_needed))
            Spacer(Modifier.height(8.dp))
            // Refused twice, Android answers every later request at once without showing its
            // question, and the app's page in Settings is the only place left to allow it.
            OutlinedButton(
                onClick = {
                    open(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.permission_intro_settings)) }
        }

        else -> {
            // One stop for a screen reader: the bars are paint, and the words beside them say it all.
            Row(
                Modifier.semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SignalBars(signal.level, Modifier.size(30.dp, 24.dp))
                Column {
                    Text(signalWords(signal), style = MaterialTheme.typography.titleMedium)
                    signalAccuracy(signal, imperial)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = PwnMuted)
                    }
                }
            }
            if (waiting) {
                Spacer(Modifier.height(8.dp))
                Message(stringResource(R.string.waiting_for_fix))
            }
        }
    }
}

/** The signal in words: how good, or in two words why there is none. */
@Composable
internal fun signalWords(signal: GpsSignal): String = stringResource(
    when {
        !signal.locationOn -> R.string.track_signal_location_off
        !signal.granted -> R.string.track_signal_no_access
        signal.level >= SIGNAL_BARS -> R.string.track_signal_good
        signal.level == SIGNAL_BARS - 1 -> R.string.track_signal_fair
        signal.level == 1 -> R.string.track_signal_poor
        else -> R.string.track_signal_searching
    },
)

/** How close the fix behind the bars is, while there is one to read; null otherwise. */
@Composable
internal fun signalAccuracy(signal: GpsSignal, imperial: Boolean): String? {
    val metres = signal.accuracyM?.takeIf { !signal.blocked && signal.level > 0 } ?: return null
    return stringResource(
        if (imperial) R.string.track_accuracy_value_ft else R.string.track_accuracy_value,
        heightValue(metres.toDouble(), imperial),
    )
}

/**
 * The walk's own figures, under its name. A walk already over is the last one, shown until the next
 * starts, and the circle beside its name says so. While the walk stands paused, what it is doing now
 * is the pause: its last fix is from before it, and no other is kept until it is resumed. Over, it is
 * doing nothing now: see [standingWord].
 */
@Composable
private fun RouteFigures(
    route: TrackEntity,
    state: RecordState,
    allPoints: List<TrackPointEntity>,
    points: List<TrackPointEntity>,
    rejected: List<TrackPointEntity>,
    kcal: Double,
    imperial: Boolean,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    val resources = LocalResources.current
    val title = route.title(resources)
    val standing = standingWord(state)
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (state == RecordState.IDLE) HelpDot(title) { Text(stringResource(R.string.track_showing_last)) }
    }
    Spacer(Modifier.height(8.dp))
    RouteTiles(route, points, standing, kcal, imperial)
    RouteLegend(mapStyle())
    Spacer(Modifier.height(16.dp))
    RouteTelemetry(allPoints, points, rejected, standing, imperial)
    Spacer(Modifier.height(16.dp))
    val splitMetres = if (imperial) METRES_IN_A_MILE else METRES_IN_A_KILOMETRE
    SplitsTable(remember(points, splitMetres) { routeSplits(points, splitMetres) }, imperial)
    RouteExportButton(route, allPoints, onSaved = onSaved, onFailed = onFailed)
}

/**
 * What the current speed and the movement say instead of the last fix's, or null while the walk
 * records and that fix is now. Paused, it is from before the pause; over, from the walk's end, and the
 * page then shows the last walk taken, under "Current speed" and "Movement" all the same.
 */
internal fun standingWord(state: RecordState): Int? = when (state) {
    RecordState.RECORDING -> null
    RecordState.PAUSED -> R.string.track_value_paused
    RecordState.IDLE -> R.string.track_value_ended
}

/**
 * Nine tiles, two to a row and the energy across the last. Speed borrows the distance conversion
 * rather than owning one: metres a second times the seconds in an hour is metres an hour, and
 * kilometres or miles out of that is the same question the distance tiles ask. The current speed is
 * the last fix's, or [standing] where that fix is not now.
 */
@Composable
private fun RouteTiles(
    route: TrackEntity,
    points: List<TrackPointEntity>,
    standing: Int?,
    kcal: Double,
    imperial: Boolean,
) {
    val resources = LocalResources.current
    // Held against the fixes rather than walked again on every recomposition: a long recording is
    // tens of thousands of them.
    val stopCount = remember(points) { points.stopCount() }
    val speed = if (imperial) R.string.track_speed_value_mph else R.string.track_speed_value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackStat(
            stringResource(R.string.track_total_route),
            formatDistance(resources, route.distanceMeters, imperial),
            Modifier.weight(1f),
        )
        TrackStat(
            stringResource(R.string.track_walking_counted),
            formatDistance(resources, route.walkingDistanceMeters, imperial),
            Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackStat(
            stringResource(R.string.track_running),
            formatDistance(resources, route.runningDistanceMeters, imperial),
            Modifier.weight(1f),
        )
        TrackStat(
            stringResource(R.string.track_vehicle),
            formatDistance(resources, route.vehicleDistanceMeters, imperial),
            Modifier.weight(1f),
            warning = route.vehicleDistanceMeters > 0,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackStat(
            stringResource(R.string.track_speed_now),
            if (standing != null) {
                stringResource(standing)
            } else {
                val metresAnHour = (points.lastOrNull()?.speedMps ?: 0f) * SECONDS_PER_HOUR
                stringResource(speed, distanceValue(metresAnHour, imperial))
            },
            Modifier.weight(1f),
        )
        TrackStat(
            stringResource(R.string.track_speed_max),
            stringResource(speed, distanceValue(route.maxSpeedMps * SECONDS_PER_HOUR, imperial)),
            Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TrackStat(
            stringResource(R.string.track_stops),
            stringResource(R.string.track_stops_value, stopCount, route.stoppedMillis / 60_000),
            Modifier.weight(1f),
        )
        TrackStat(
            stringResource(R.string.track_elevation_gain),
            stringResource(
                if (imperial) R.string.track_elevation_gain_value_ft else R.string.track_elevation_gain_value,
                heightValue(route.elevationGainM, imperial),
                heightValue(route.elevationLossM, imperial),
            ),
            Modifier.weight(1f),
        )
    }
    // By [walkCalories]: the pace, the height and weight on the profile, and the climb and descent.
    // Through a template, as every tile above it: the figure in the digits the language writes.
    TrackStat(
        stringResource(R.string.track_burned),
        stringResource(R.string.entry_energy, kcal),
        Modifier.fillMaxWidth(),
    )
}

/**
 * The fixes and the sensors behind the line. The altitudes are the last, lowest and highest of the
 * fixes that carried one at all; held against the fixes, as the tiles' counts are. The movement is
 * the last fix's, as the current speed is, and says [standing] instead for the same reason.
 */
@Composable
private fun RouteTelemetry(
    allPoints: List<TrackPointEntity>,
    points: List<TrackPointEntity>,
    rejected: List<TrackPointEntity>,
    standing: Int?,
    imperial: Boolean,
) {
    val altitude = remember(points) {
        val values = points.mapNotNull { it.barometricAltitudeM?.toDouble() ?: it.altitudeM }
        if (values.isEmpty()) null else Triple(values.last(), values.min(), values.max())
    }
    val spikes = remember(points) { points.count(TrackPointEntity::environmentSpike) }
    val current = points.lastOrNull()
    val unavailable = stringResource(R.string.track_sensor_unavailable)
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TrackSectionTitle(stringResource(R.string.track_telemetry), stringResource(R.string.track_environment_note))
            TrackDetail(stringResource(R.string.track_gps_points), points.size.toLong().formatted())
            TrackDetail(stringResource(R.string.track_raw_gps_points), allPoints.size.toLong().formatted())
            TrackDetail(stringResource(R.string.track_rejected_points), rejected.size.toLong().formatted())
            TrackDetail(
                stringResource(R.string.track_last_filter_reason),
                stringResource(filterReasonLabel(rejected.lastOrNull()?.filterReason) ?: R.string.track_filter_none),
            )
            TrackDetail(
                stringResource(R.string.track_gps_accuracy),
                current?.accuracyM?.let {
                    stringResource(
                        if (imperial) R.string.track_accuracy_value_ft else R.string.track_accuracy_value,
                        heightValue(it.toDouble(), imperial),
                    )
                } ?: unavailable,
            )
            TrackDetail(
                stringResource(R.string.track_movement),
                stringResource(standing ?: movementLabel(current?.movementType)),
            )
            TrackDetail(
                stringResource(R.string.track_altitude),
                altitude?.let { (last, low, high) ->
                    stringResource(
                        if (imperial) R.string.track_altitude_value_ft else R.string.track_altitude_value,
                        heightValue(last, imperial),
                        heightValue(low, imperial),
                        heightValue(high, imperial),
                    )
                } ?: unavailable,
            )
            TrackDetail(
                stringResource(R.string.track_pressure),
                current?.pressureHpa?.let { stringResource(R.string.track_pressure_value, it) } ?: unavailable,
            )
            TrackDetail(
                stringResource(R.string.track_temperature),
                current?.ambientTemperatureC?.let {
                    stringResource(
                        if (imperial) R.string.track_temperature_value_f else R.string.track_temperature_value,
                        temperatureValue(it.toDouble(), imperial),
                    )
                } ?: unavailable,
            )
            TrackDetail(stringResource(R.string.track_environment_changes), spikes.toLong().formatted())
        }
    }
}

/**
 * The walks already taken, newest first, each opening its own page through [onOpenWalk], on a page
 * of its own behind the history disc.
 * [tracks] is every route there is, in whatever order it was read; see [savedRoutes].
 */
@Composable
internal fun TrackHistoryPage(
    tracks: List<TrackEntity>,
    imperial: Boolean,
    onBack: () -> Unit,
    onOpenWalk: (Long) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val title = stringResource(R.string.track_history)
    val saved = remember(tracks) { savedRoutes(tracks) }
    Column(Modifier.fillMaxSize()) {
        PwnTopBar(title, onBack) { HelpDot(title) { Text(stringResource(R.string.track_history_note)) } }
        PageColumn {
            if (saved.isEmpty()) Message(stringResource(R.string.track_history_empty))
            saved.forEach { route ->
                // onClick rather than .clickable: both draw the same ripple, but only the card
                // overload announces the row as a button, which is what a row that opens a walk's
                // own page is.
                PwnCard(
                    onClick = { onOpenWalk(route.id) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // The name and the line under it yield; the distance keeps its intrinsic
                        // width. Unweighted, this Column is measured against the whole row first, so
                        // whatever a long locale or a large font scale leaves the distance is what
                        // the distance gets.
                        Column(Modifier.weight(1f, fill = false).padding(end = 12.dp)) {
                            Text(route.title(resources), style = MaterialTheme.typography.titleSmall)
                            Text(
                                routeDate(context, route.startedAt) + " · " +
                                    pluralStringResource(R.plurals.track_history_points, route.points, route.points),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatDistance(resources, route.distanceMeters, imperial))
                    }
                }
            }
        }
    }
}

/**
 * The routes the history lists out of [tracks]: every one that has ended, newest first. Every one,
 * not only the latest twenty rows the track screen keeps for its map: a walk before those could
 * otherwise not be opened, shared or saved to a file, though it is still in the database and in
 * every backup.
 */
internal fun savedRoutes(tracks: List<TrackEntity>): List<TrackEntity> =
    tracks.filter { it.endedAt != null }.sortedByDescending { it.startedAt }

/**
 * Kilometre splits — mile splits on an imperial profile, because the tiles above this table already
 * read in miles and mph: the table every running app opens with.
 */
@Composable
private fun SplitsTable(splits: List<RouteSplit>, imperial: Boolean) {
    if (splits.isEmpty()) return
    Text(
        stringResource(if (imperial) R.string.track_splits_miles else R.string.track_splits),
        Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(6.dp))
    splits.forEach { split ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                if (split.partial) {
                    stringResource(
                        if (imperial) R.string.track_split_partial_miles else R.string.track_split_partial,
                        split.index,
                        distanceValue(split.metres, imperial),
                    )
                } else {
                    stringResource(
                        if (imperial) R.string.track_split_index_miles else R.string.track_split_index,
                        split.index,
                    )
                },
                Modifier.weight(1f, fill = false).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (imperial) R.string.track_split_pace_miles else R.string.track_split_pace,
                    formatPace(split.paceSeconds),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * The JSON backup is for this app; these three are what everything else on earth reads. Written
 * straight to the place the person picks — no upload, no share sheet, no intermediary. One button,
 * and the format picked from the menu it opens, as the walk page's share button offers the same ones.
 *
 * [onSaved] and [onFailed] carry the words the write ended on up to the screen, which shows them over
 * the middle of the page rather than at the foot of a scroll the eye is nowhere near a second after
 * tapping.
 */
@Composable
private fun RouteExportButton(
    track: TrackEntity,
    points: List<TrackPointEntity>,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
) {
    TrackSectionTitle(stringResource(R.string.track_export_title), stringResource(R.string.track_export_note))
    Spacer(Modifier.height(6.dp))
    // A save picker for each format, all registered up front: the picker's file type is fixed when it
    // is registered, and the answer can come back to a screen composed afresh.
    val saves = RouteFormat.entries.associateWith { rememberRouteSave(it, track, onSaved, onFailed) }
    val enabled = RouteFormat.entries.any { it.availableFor(points) }
    var choosing by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        PwnOutlinedButton(
            onClick = { choosing = true },
            modifier = Modifier.fillMaxWidth().opensMenu(choosing) { choosing = it },
            enabled = enabled,
        ) {
            // The resting slate, as the calendar's filter button draws its glyph; greyed out with the
            // button, which a fixed tint would not be.
            Icon(
                Icons.Outlined.FileDownload,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
                tint = if (enabled) PwnMuted else LocalContentColor.current,
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.track_export_save))
        }
        RouteFormatMenu(choosing, points, onDismiss = { choosing = false }) { format ->
            saves.getValue(format).launch("route-${track.id}.${format.extension}")
        }
    }
}

/**
 * The save picker for one [format]: the route is written into whatever document the person names
 * ([writeRouteDocument]), or the words of what went wrong are handed to [onFailed].
 */
@Composable
private fun rememberRouteSave(
    format: RouteFormat,
    track: TrackEntity,
    onSaved: (String) -> Unit,
    onFailed: (String) -> Unit,
): ManagedActivityResultLauncher<String, Uri?> {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.track_exported)
    // A long recording is tens of thousands of fixes to read and serialise, and the chosen document
    // provider may be cloud-backed: none of it belongs on the thread that draws the screen.
    return rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { writeRouteDocument(context, uri, format, track) }
            }.onFailure { if (it is CancellationException) throw it }.fold(
                onSuccess = { onSaved(savedText) },
                onFailure = { onFailed(resources.getString(R.string.profile_export_error, it.message ?: "")) },
            )
        }
    }
}

/**
 * Writes [format]'s file of [track] into the document at [uri], from the fixes the database holds
 * as it does it ([routeFileText]). A failure takes the document away where this write spoiled it —
 * the empty one the picker just made, or the part that got across once it was opened for writing —
 * as [exportSpooled] does a backup's; one that still holds an earlier file, because the failure came
 * before it was opened, keeps it. Blocking: on the IO dispatcher.
 */
private suspend fun writeRouteDocument(context: Context, uri: Uri, format: RouteFormat, track: TrackEntity) {
    val resolver = context.contentResolver
    var opened = false
    runCatching {
        val dao = StepsDb.get(context).dao()
        // A route with nothing for this file fails here, as a provider with no stream to give does on
        // the next line: neither is a route exported.
        val text = routeFileText(format, track) { dao.trackPointsNow(it) }!!
        resolver.openOutputStream(uri, "wt")!!.use { out ->
            opened = true
            out.write(text.toByteArray())
        }
    }.onFailure {
        val spoiled = opened || runCatching { resolver.openInputStream(uri)?.use { s -> s.read() } }.getOrNull() == -1
        if (spoiled) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }.getOrThrow()
}

/**
 * What a save writes into its document: the route as [format] writes it, from the fixes [fixesOf]
 * reads for it at that moment, or null when they give the file nothing to hold.
 *
 * Not from the fixes the page has in hand. The picker's answer can come back to a screen composed
 * afresh — the phone turned under the picker, or the process started again behind it — and it is
 * then delivered as the launcher is registered, in the same pass that first asks Room for the
 * route's fixes. The page's list is still the empty one it starts from, and a file written from it
 * would be a GPX with no point in it, reported as the route exported.
 */
internal suspend fun routeFileText(
    format: RouteFormat,
    track: TrackEntity,
    fixesOf: suspend (trackId: Long) -> List<TrackPointEntity>,
): String? = fixesOf(track.id).takeIf(format::availableFor)?.let { format.write(track, it) }

/**
 * A section's name, marked as a heading so a screen reader can walk the page by them, with what the
 * section's figures mean — [note] — behind the circle beside it rather than printed under them.
 */
@Composable
private fun TrackSectionTitle(title: String, note: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        HelpDot(title) { Text(note) }
    }
}

/**
 * A page's scrolling column. The panel it stands in runs on under the navigation bar — see
 * TrackPageSheet — so the column's foot is padded clear of the bar, and its last row can be
 * scrolled up out from under it.
 */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .panelVerticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 24.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        content = content,
    )
}

@Composable
private fun TrackStat(
    label: String,
    value: String,
    modifier: Modifier,
    warning: Boolean = false,
) {
    PwnCard(modifier.padding(bottom = 8.dp)) {
        Column(Modifier.padding(PwnDimensions.cardPadding)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TrackDetail(label: String, value: String) {
    // The label yields, the reading does not. With neither child weighted, Row would measure the label
    // against the whole 288dp a 360dp screen leaves inside this card and hand the value
    // maxWidth = 0; the Russian "Скорректированные/отброшенные точки" is 314dp on its own at
    // bodyLarge 16sp, so the number the row exists to show would be clipped away entirely. Weighted but
    // not filling, the label wraps and the value keeps its intrinsic width in every locale.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            Modifier.weight(1f, fill = false).padding(end = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value)
    }
}

/** What turns metres a second into metres an hour, for [distanceValue] to finish. */
private const val SECONDS_PER_HOUR = 3_600.0

private fun movementLabel(type: String?): Int = when (type) {
    RouteMovement.WALKING.name -> R.string.track_movement_walking
    RouteMovement.RUNNING.name -> R.string.track_movement_running
    RouteMovement.VEHICLE.name -> R.string.track_movement_vehicle
    RouteMovement.STOPPED.name -> R.string.track_movement_stopped
    else -> R.string.track_movement_unknown
}

/**
 * Why the filter threw a fix out, in the app's words; null for no reason, or for one this build does
 * not know, never the stored name itself — UNCONFIRMED_JUMP between two translated
 * lines. The two jumps read as one: a position the fixes after it did not bear out, whether or not
 * the line came straight back from it. The reasons a fix is kept with never reach the row, which
 * shows the last rejected fix's.
 */
internal fun filterReasonLabel(reason: String?): Int? = when (reason) {
    RouteFixReason.LOW_ACCURACY.name -> R.string.track_filter_low_accuracy
    RouteFixReason.STALE_FIX.name -> R.string.track_filter_stale_fix
    RouteFixReason.UNCONFIRMED_JUMP.name, RouteFixReason.RETURN_SPIKE.name -> R.string.track_filter_jump
    RouteFixReason.IMPOSSIBLE_SPEED.name -> R.string.track_filter_speed
    RouteFixReason.NON_MONOTONIC_TIME.name -> R.string.track_filter_time_order
    else -> null
}

/**
 * When a route started, as the phone writes a date and a time: its order of day, month and year and
 * the digits the language writes — the walk page's own date, with the time beside it. The time is
 * on the 24-hour clock whatever the phone is set to, as every other time in the app is: see
 * [clockText].
 */
private fun routeDate(context: Context, startedAt: Long): String =
    skeletonDateText("yMMMdHm", context.resources.configuration.locales[0], startedAt, ZoneId.systemDefault())
