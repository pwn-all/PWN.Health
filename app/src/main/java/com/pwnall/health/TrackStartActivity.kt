package com.pwnall.health

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * The door the track widget's Start goes through: no window, no layout, one decision.
 *
 * A widget tap is on both of the platform's exemption lists, so the recording it starts is entitled
 * to while-in-use location without ACCESS_BACKGROUND_LOCATION. What it is not entitled to is a
 * leisurely route: the privilege reaches a Glance callback as a background-start token that expires
 * about ten seconds after the tap, and that path — widget to broadcast to service — is one no
 * open-source location tracker ships, so it has no history on the OEM builds that rewrite this
 * corner of the framework. Where it fails, it fails the way worth fearing: a track created, a
 * notification that flashes, and not a single fix recorded. No exception, nothing in the log, and
 * a route the person believes they are recording.
 *
 * An activity removes the question. The system sees a component of this app starting, which is the
 * plainest form of the grant there is, and the same shape Glance itself falls back to for
 * widget-to-service actions and OpenTracks uses for its public start API.
 *
 * Nothing is drawn. The theme is `Theme.NoDisplay` and [finish] is called from [onCreate], so the
 * only thing the person sees is the widget taking its recording face, once the first fix or the
 * first steps of the walk have been written.
 */
class TrackStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (canRecordRoute()) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, StepService::class.java).setAction(StepService.ACTION_START_TRACK),
            )
        } else {
            // A widget cannot ask for a permission, and a recording without one is an empty file
            // with a notification over it. The track screen is where the request already lives, so
            // the tap goes there instead of starting something that cannot work.
            startActivity(openAppIntent(this, WidgetRoute.TRACK))
        }
        finish()
    }
}

/**
 * Whether a recording could actually record. Shared with the track screen, which asks the same
 * question before it offers the same button.
 *
 * Not `hasLocationPermission`: StepService already has a private member of that name, and an
 * extension that shadows it in some files and not others is the kind of ambiguity lint refuses on
 * this project's behalf.
 */
internal fun android.content.Context.canRecordRoute() =
    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
