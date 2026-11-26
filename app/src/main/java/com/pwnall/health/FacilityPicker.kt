package com.pwnall.health

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * The list while the app is on the screen. It is read when a Place page is first opened and kept,
 * so the second appointment of a sitting does not wait for forty thousand rows again, and it is let
 * go when the app leaves the screen ([ForgetFacilitiesWhenHidden]): tens of megabytes of heap kept
 * for a process in the background, which is where Android looks for one to end.
 *
 * One read, whoever asks. Otherwise a Place page left before its read is done and opened again
 * would read all eight megabytes again beside the first: two copies of the list on the heap at the
 * moment it can least afford them. A read ends in [loaded] whether or not anybody is still waiting
 * for it, and whoever asks meanwhile waits for it instead of starting another.
 */
internal class FacilityList {
    @Volatile
    var loaded: List<Facility>? = null
        private set

    private val reading = Mutex()

    /**
     * The list, read with [read] the first time it is asked for. Empty where [read] fails, and not
     * kept: a read that ran out of memory once may not the next time, and a failure kept would hide
     * the list until the app was restarted.
     */
    suspend fun get(read: () -> List<Facility>): List<Facility> =
        loaded ?: withContext(Dispatchers.IO) {
            reading.withLock { loaded ?: runCatching(read).getOrNull()?.also { loaded = it }.orEmpty() }
        }

    /** Lets the list go; whoever asks next reads it again. A page holding it keeps its own copy. */
    fun forget() {
        loaded = null
    }
}

private val shippedFacilities = FacilityList()

/**
 * Lets the list go once none of the app's windows is shown: [ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN]
 * is what Android sends then, and the deeper levels come after it.
 */
private object ForgetFacilitiesWhenHidden : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) shippedFacilities.forget()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Superseded by onTrimMemory, which is all this listens to.")
    override fun onLowMemory() = Unit
}

private val forgettingWhenHidden = AtomicBoolean(false)

/**
 * The places of care, read off the main thread. Null while the file is being read; empty when it
 * could not be read at all, in which case the page simply offers what it offered before there was
 * a list — the person's own places and a field to type in.
 */
@Composable
internal fun rememberFacilities(): List<Facility>? {
    val app = LocalContext.current.applicationContext
    return produceState(shippedFacilities.loaded) {
        if (forgettingWhenHidden.compareAndSet(false, true)) app.registerComponentCallbacks(ForgetFacilitiesWhenHidden)
        if (value == null) {
            value = shippedFacilities.get {
                app.assets.open(FACILITIES_ASSET).bufferedReader().useLines(::parseFacilities)
            }
        }
    }.value
}

/** Either location grant will do: approximate is all a list sorted by distance needs. */
internal fun Context.hasApproximateLocation(): Boolean = listOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Where the phone is, roughly, asked of Android's own location service — no Play services.
 *
 * One fresh fix is asked for, from the platform's fused provider where the phone has one and the
 * network or satellite one where it does not, and waited for [LOCATION_TIMEOUT_MS]. Failing that,
 * the newest fix Android already holds will do, if it is under [LAST_KNOWN_MAX_AGE_MS] old: an
 * older one may be from another town, and a list sorted around last week's trip is worse than none.
 *
 * The answer is [roughly] the fix, rounded to about a kilometre: with precise location already
 * granted for route recording, Android would otherwise hand over a satellite-grade position that
 * the page promised not to ask for. Nothing here keeps it. It goes to the caller, who holds it in
 * memory for the page.
 */
@SuppressLint("MissingPermission") // guarded by hasApproximateLocation()
internal suspend fun approximateLocation(context: Context): GeoPoint? {
    if (!context.hasApproximateLocation()) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    // A page left while the phone was being asked is cancelled, not answered: caught with the rest,
    // its coroutine would go on to write "no position" into a holder nobody was reading.
    return runCatching {
        val providers = LOCATION_PROVIDERS.filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        val fresh = providers.firstOrNull()?.let { provider ->
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
            }
        }
        val now = SystemClock.elapsedRealtimeNanos()
        val fix = fresh ?: providers.mapNotNull(manager::getLastKnownLocation)
            .filter { now - it.elapsedRealtimeNanos <= LAST_KNOWN_MAX_AGE_MS * NANOS_PER_MILLI }
            .maxByOrNull(Location::getElapsedRealtimeNanos)
        fix?.let { roughly(GeoPoint(it.latitude, it.longitude)) }
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()
}

private val LOCATION_PROVIDERS = listOf(
    LocationManager.FUSED_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
    LocationManager.GPS_PROVIDER,
)
private const val LOCATION_TIMEOUT_MS = 10_000L
private const val LAST_KNOWN_MAX_AGE_MS = 60 * 60 * 1000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * One Place page's "near me": where the phone is once it has been asked, how the asking went, and
 * what the field held at the time.
 *
 * Held in a plain [remember] on purpose. A saveable one would ride in the activity's saved state,
 * which Android may write to disk; this one is gone when the page is left, and is never written to
 * the database, a backup or anywhere else.
 */
@Stable
internal class NearMe {
    var here by mutableStateOf<GeoPoint?>(null)
    var status by mutableStateOf(NearStatus.IDLE)

    /**
     * What the field held when the nearest places were last asked for: until a key is pressed, the
     * nearest list is shown over the matches for that text.
     */
    var askedFor by mutableStateOf<String?>(null)

    /** Asks Android for the permission first when the app has neither grant; set by [rememberNearMe]. */
    internal var request: () -> Unit = {}

    /** A second tap while the phone is still being asked is the same question, not a new one. */
    fun ask(typed: String) {
        if (status == NearStatus.LOCATING) return
        askedFor = typed
        request()
    }

    /**
     * What the field holds now. Any change since the tap ends it, even one taken back: a letter typed
     * and rubbed out again is a key pressed, and the text decides the list from then on
     * ([facilitySuggestions]) — where comparing the text alone would bring the nearest places back.
     */
    fun typed(text: String) {
        if (text != askedFor) askedFor = null
    }
}

@Composable
internal fun rememberNearMe(): NearMe {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val near = remember { NearMe() }

    fun locate() {
        near.status = NearStatus.LOCATING
        scope.launch {
            val fix = approximateLocation(context)
            near.here = fix
            near.status =
                nearStatusOf(fix, context.getSystemService(LocationManager::class.java)?.isLocationEnabled != false)
        }
    }
    near.request = rememberLocationGate(onDenied = { near.status = nearStatusOf(granted = false) }) { locate() }
    return near
}

/** Why a tap on a location button could not go ahead. */
private enum class LocationBlock { OFF, DENIED }

/**
 * A location button's tap: asks Android for the permission where the app has neither grant, then
 * runs [onReady] only once the app may read the location and the phone has it switched on. Either
 * one missing is said in a dialog with the way to its setting — going ahead regardless, the tap
 * would leave one grey line on the next page, which reads as a button that did nothing.
 * [onDenied] hears a refusal as well, for a page that keeps a line about it.
 */
@Composable
internal fun rememberLocationGate(onDenied: () -> Unit = {}, onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val denied by rememberUpdatedState(onDenied)
    var block by remember { mutableStateOf<LocationBlock?>(null) }
    fun goIfOn() {
        if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled == false) {
            block = LocationBlock.OFF
        } else {
            ready()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            goIfOn()
        } else {
            denied()
            block = LocationBlock.DENIED
        }
    }
    block?.let { shown ->
        val off = shown == LocationBlock.OFF
        AlertDialog(
            onDismissRequest = { block = null },
            icon = { Icon(Icons.Outlined.LocationOff, null, tint = PwnMuted) },
            title = { Text(stringResource(R.string.facility_near_me)) },
            text = { Text(stringResource(if (off) R.string.facility_location_off else R.string.facility_denied)) },
            confirmButton = {
                TextButton(onClick = {
                    block = null
                    // A refusal Android no longer asks about can only be undone on the app's own page.
                    val intent = if (off) {
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    } else {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    }
                    runCatching { context.startActivity(intent) }
                }) {
                    Text(
                        stringResource(
                            if (off) R.string.facility_location_settings else R.string.permission_intro_settings,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { block = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    return {
        if (context.hasApproximateLocation()) {
            goIfOn()
        } else {
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

/**
 * The location button in the end corner of the Place field: one tap lists the nearest places that
 * see this kind of doctor. While the phone is being asked it is a small spinner, which says the tap
 * was heard. A file that could not be read leaves the field its magnifier: there would be nothing
 * to sort.
 */
@Composable
internal fun NearMeIcon(near: NearMe, facilities: List<Facility>?, typed: String) {
    when {
        facilities?.isEmpty() == true -> Icon(Icons.Outlined.Search, null, tint = PwnMuted)

        near.status == NearStatus.LOCATING -> {
            val locating = stringResource(R.string.facility_locating)
            CircularProgressIndicator(
                Modifier.size(20.dp).semantics { contentDescription = locating },
                color = PwnMuted,
                strokeWidth = 2.dp,
            )
        }

        else -> IconButton(
            onClick = { near.ask(typed) },
            colors = IconButtonDefaults.iconButtonColors(contentColor = PwnMuted),
        ) {
            Icon(Icons.Outlined.MyLocation, stringResource(R.string.facility_near_me))
        }
    }
}

/** The "?" that says where the list comes from and what becomes of the location, for the page's top bar. */
@Composable
internal fun FacilityHelpDot() {
    HelpDot(stringResource(R.string.facility_help_title)) { FacilityHelp() }
}

/**
 * The Place page's own part of the picker, under its field: how the last "near me" went when there
 * is something to say about it, the person's saved places ([content], drawn where they always
 * were), and the places on the list that answer what was typed or, before anything is typed and
 * once the phone's position is known, the nearest ones that see this kind of doctor.
 */
@Composable
internal fun FacilityFinder(
    facilities: List<Facility>?,
    near: NearMe,
    query: String,
    specialty: String,
    imperial: Boolean,
    onPick: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val kinds = remember(specialty) { careTypesFor(specialty) }
    val typed = query.trim()
    LaunchedEffect(typed) { near.typed(typed) }
    val nearAsked = near.askedFor == typed
    val here = near.here
    // The list and what it is are worked out together, so a title or a "nothing near" line never
    // describes the list from before the last change.
    val shown by produceState(Suggestions(emptyList(), nearest = false), facilities, typed, here, kinds, nearAsked) {
        val all = facilities.orEmpty()
        value = withContext(Dispatchers.Default) {
            Suggestions(facilitySuggestions(all, typed, kinds, here, nearAsked), showsNearest(typed, here, nearAsked))
        }
    }
    val hits = shown.hits

    if (facilities?.isEmpty() != true) {
        NearMeStatus(
            status = near.status,
            // As in the location dialog: a phone without the settings screen leaves the button doing nothing.
            onSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } },
            nothingNear = shown.nearest && near.status == NearStatus.FOUND && hits.isEmpty(),
            imperial = imperial,
        )
    }
    content()
    if (hits.isNotEmpty()) {
        val department = nearListKind(specialty)
        val title = when {
            !shown.nearest -> stringResource(R.string.facility_list_title)
            kinds.isEmpty() -> stringResource(R.string.facility_near_title)
            department != null -> stringResource(departmentTitle(department))
            else -> stringResource(R.string.facility_near_kind, specialtyLabel(specialty))
        }
        FacilityList(title, hits, typed, imperial, onPick, announce = shown.nearest)
    }
}

/** What the Place page lists, and whether that is the nearest places rather than matches for the text. */
private class Suggestions(val hits: List<FacilityHit>, val nearest: Boolean)

/** The near list's title for a screening, named by the department the list was narrowed to. */
private fun departmentTitle(kind: CareType): Int = when (kind) {
    CareType.GYNAECOLOGY -> R.string.facility_near_gynaecology
    CareType.GASTROENTEROLOGY -> R.string.facility_near_gastroenterology
    else -> R.string.facility_near_imaging
}

/**
 * One line on how the last "near me" went when there is something to say about it, and the way to
 * the location settings when location is what stopped it.
 */
@Composable
private fun NearMeStatus(status: NearStatus, onSettings: () -> Unit, nothingNear: Boolean, imperial: Boolean) {
    val line = when {
        status == NearStatus.DENIED -> stringResource(R.string.facility_denied)
        status == NearStatus.LOCATION_OFF -> stringResource(R.string.facility_location_off)
        status == NearStatus.NO_FIX -> stringResource(R.string.facility_no_fix)
        nothingNear -> stringResource(R.string.facility_nothing_near, distanceText(FACILITY_NEAR_METRES, imperial))
        else -> null
    }
    // Always there, so that a line appearing in it is a change TalkBack reads out: the spinner only
    // turns back into the button, which says nothing of how it went.
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
        if (status == NearStatus.LOCATION_OFF) {
            TextButton(onClick = onSettings) { Text(stringResource(R.string.facility_location_settings)) }
        }
    }
}

/**
 * The source lines behind the rows of the list, by the prefix of the ids each register's rows carry.
 *
 * Kept by prefix so that a register cannot be on the list without a line here: a test reads the
 * shipped file and asks for a prefix for every row. Without its line, the two thirds of the
 * Emirates' places that come from the Dubai register would stand under "Sources" with no source
 * at all.
 */
internal val FACILITY_SOURCES: Map<String, List<Int>> = mapOf(
    "CZ:" to listOf(R.string.facility_source_cz, R.string.facility_source_cz_coordinates),
    "UA:" to listOf(R.string.facility_source_ua),
    "AE:DHA-" to listOf(R.string.facility_source_ae_dubai),
    "AE:DOH-" to listOf(R.string.facility_source_ae_mohap),
    "AE:MOHAP-" to listOf(R.string.facility_source_ae_mohap, R.string.facility_source_ae_bayanat),
)

/**
 * What is behind the "?": what the list is, what it covers and what it leaves out, what is done
 * with the location, and the registers it was made from with their licences — the attribution
 * CC BY asks for, in the registers' own words, which is why those lines are not translated.
 */
@Composable
private fun FacilityHelp() {
    val body = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    listOf(
        R.string.facility_help_what,
        R.string.facility_help_screening,
        R.string.facility_help_coverage,
        R.string.facility_help_location,
    )
        .forEach { Text(stringResource(it), style = body, color = muted) }
    Text(stringResource(R.string.facility_help_sources), style = MaterialTheme.typography.titleSmall)
    val sources = FACILITY_SOURCES.values.flatten().distinct() + R.string.facility_source_licence
    sources.forEach { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = muted) }
}

/**
 * The places as rows to pick from: the name in full weight, where it is underneath, and the
 * distance at the end when the phone's position is known. Picking one fills the appointment's place
 * with the name and the address, which is what a person will want to read on the day. When the
 * list is an answer to "near me" its title is a polite live region, so TalkBack says that it came.
 */
@Composable
private fun FacilityList(
    title: String,
    hits: List<FacilityHit>,
    typed: String,
    imperial: Boolean,
    onPick: (String) -> Unit,
    announce: Boolean,
) {
    PwnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup()) {
            Text(
                title,
                if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                style = MaterialTheme.typography.labelLarge,
                color = PwnMuted,
            )
            hits.forEachIndexed { index, hit ->
                if (index > 0) HorizontalDivider(color = PwnBorder)
                val place = hit.facility.placeText
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = place == typed, role = Role.RadioButton) { onPick(place) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PwnGlyph(R.drawable.ic_art_place, Icons.Outlined.Place, PwnIndigoSoft, null, Modifier.size(40.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        // Three lines, not one: a Ukrainian register name carries the legal
                        // entity after the department, and two clinics of one hospital differ
                        // only past the first line.
                        Text(
                            hit.facility.name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            hit.facility.where,
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    hit.metres?.let {
                        Text(distanceText(it, imperial), style = MaterialTheme.typography.bodySmall, color = PwnMuted)
                    }
                    if (place == typed) SelectedTick()
                }
            }
        }
    }
}

/**
 * "1.4 km", "12 km": a tenth under ten and whole beyond, because the position is approximate and
 * a second decimal would claim a precision the fix does not have.
 */
@Composable
private fun distanceText(metres: Double, imperial: Boolean): String {
    val value = distanceValue(metres, imperial)
    val number = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = if (value < 10) 1 else 0
    }.format(value)
    return stringResource(if (imperial) R.string.facility_distance_miles else R.string.facility_distance, number)
}
