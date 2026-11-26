package com.pwnall.health

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The three files a route leaves the phone as, each said once: its name and the line under it in the
 * menu that offers it, the file's extension and type, what goes into it, and whether a route has
 * anything to put there. GPX and GeoJSON carry the accepted line for maps; the CSV carries every fix.
 */
internal enum class RouteFormat(
    @StringRes val title: Int,
    @StringRes val hint: Int,
    val extension: String,
    val mimeType: String,
    /** Whether the file holds every fix, the rejected ones too, rather than the line drawn from them. */
    private val everyFix: Boolean,
    val write: (TrackEntity, List<TrackPointEntity>) -> String,
) {
    GPX(
        title = R.string.track_export_gpx,
        hint = R.string.route_format_gpx_hint,
        extension = "gpx",
        mimeType = "application/gpx+xml",
        everyFix = false,
        write = ::gpxOf,
    ),
    GEO_JSON(
        title = R.string.track_export_geojson,
        hint = R.string.route_format_geojson_hint,
        extension = "geojson",
        mimeType = "application/geo+json",
        everyFix = false,
        write = ::geoJsonOf,
    ),
    CSV(
        title = R.string.track_export_csv,
        hint = R.string.route_format_csv_hint,
        extension = "csv",
        mimeType = "text/csv",
        everyFix = true,
        write = { _, points -> csvOf(points) },
    ),
    ;

    /**
     * Whether [points] give this file anything to hold: a line needs an accepted fix to be drawn from,
     * and the CSV, which carries the rejected fixes too, needs any fix at all.
     */
    fun availableFor(points: List<TrackPointEntity>): Boolean =
        if (everyFix) points.isNotEmpty() else points.any { it.accepted }
}

/**
 * The menu the walk page's share button and the track page's save button both open: each format by
 * its name, with the line under it that says what the file is for, and a format [points] give nothing
 * to write greyed out. Picking one closes the menu and hands the format to [onPick].
 */
@Composable
internal fun RouteFormatMenu(
    expanded: Boolean,
    points: List<TrackPointEntity>,
    onDismiss: () -> Unit,
    onPick: (RouteFormat) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RouteFormat.entries.forEach { format ->
            val available = format.availableFor(points)
            DropdownMenuItem(
                text = {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(stringResource(format.title))
                        // Muted while the row can be picked; greyed out with it when it cannot, in the
                        // colour the menu gives a disabled row's words.
                        Text(
                            stringResource(format.hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (available) PwnMuted else LocalContentColor.current,
                        )
                    }
                },
                onClick = {
                    onDismiss()
                    onPick(format)
                },
                enabled = available,
            )
        }
    }
}

/**
 * The control that opens a menu, with the menu's state said as the pair of actions a screen reader
 * offers on it — expand while it is shut, collapse while it is open — as the calendar's filter says
 * its own.
 */
internal fun Modifier.opensMenu(open: Boolean, onOpen: (Boolean) -> Unit): Modifier = semantics {
    if (open) {
        collapse {
            onOpen(false)
            true
        }
    } else {
        expand {
            onOpen(true)
            true
        }
    }
}
