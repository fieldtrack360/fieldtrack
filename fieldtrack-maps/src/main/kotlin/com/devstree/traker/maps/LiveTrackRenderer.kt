package com.devstree.traker.maps

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.LinearInterpolator
import com.devstree.traker.geo.math.Bearing
import com.devstree.traker.geo.math.Geodesy
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.plot.PolylineCodec
import com.devstree.traker.geo.plot.PuckAnimation
import com.devstree.traker.geo.plot.model.LiveTrackUpdate
import com.devstree.traker.geo.plot.model.PuckState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * Draws the live surface — [LiveTrackUpdate] frames from `Traker.liveTrack()` — the
 * way navigation apps do (SMOOTH-NAV-PLAN Phase 3).
 *
 * Everything [TrackRenderer] is not:
 * - **Incremental.** One mutable tail polyline, grown via `setPoints`; the frozen
 *   tail's suffix is decoded with a streaming [PolylineCodec.Decoder], never the whole
 *   string. No map object is ever removed and re-added on the per-fix path, so there
 *   is nothing to flicker.
 * - **Animated.** The puck eases between fixes with a great-circle interpolation
 *   toward a dead-reckoned target ([PuckAnimation.target]) — moving, not teleporting —
 *   and rotates the short way round with an EMA-smoothed heading.
 * - **Following.** Optional camera modes chain `animateCamera` per fix: each new frame
 *   starts the next ease immediately rather than waiting for the last to finish, which
 *   is what makes the camera feel continuous at 1 Hz.
 *
 * Main-thread only, like every GoogleMap object. Construct where the map lives, feed
 * it frames, [clear] when the map goes away. Stale frames (sequence not newer than the
 * last drawn) are dropped, as the feed's contract requires.
 */
public class LiveTrackRenderer(
    private val map: GoogleMap,
    private val options: Options = Options(),
) {

    /** How the camera relates to the puck. */
    public enum class CameraFollowMode {
        /** Camera untouched — the host owns it. */
        NONE,

        /** Centre on the puck, north-up, keeping the user's zoom. */
        FOLLOW,

        /** Navigation look: puck-centred, heading-up, tilted. */
        FOLLOW_BEARING,
    }

    public data class Options(
        val tailColor: Int = Color.argb(230, 26, 115, 232),
        val tailWidth: Float = 14f,
        val headColor: Int = Color.argb(230, 26, 115, 232),
        val headWidth: Float = 14f,
        val puckSizePx: Int = 56,
        val puckColor: Int = Color.rgb(26, 115, 232),
        val showAccuracyHalo: Boolean = true,
        val haloFillColor: Int = Color.argb(26, 26, 115, 232),
        val haloStrokeColor: Int = Color.argb(90, 26, 115, 232),
        /** Ease duration ≈ the fix interval, so one ease ends as the next fix lands. */
        val animationDurationMs: Long = 1_000,
        /** Dead-reckoning horizon; match [animationDurationMs] (PuckAnimation.target). */
        val lookaheadMs: Long = 1_000,
        val cameraFollow: CameraFollowMode = CameraFollowMode.NONE,
        /** Zoom applied on the first followed frame only; afterwards the user's zoom wins. */
        val followZoom: Float = 17f,
        val followTilt: Float = 50f,
    )

    /** Switchable at runtime — a nav UI toggles this when the user pans away. */
    public var cameraFollow: CameraFollowMode = options.cameraFollow

    private var sessionId: String? = null
    private var lastSequence = Long.MIN_VALUE
    private var decoder = PolylineCodec.Decoder()
    private val tailPoints = mutableListOf<LatLng>()
    private var tailLine: Polyline? = null
    private var headLine: Polyline? = null
    private var puckMarker: Marker? = null
    private var halo: Circle? = null
    private var animator: ValueAnimator? = null
    private var shownPosition: LatLng? = null
    private var shownHeading: Double? = null
    private var puckIcon: BitmapDescriptor? = null

    public fun render(update: LiveTrackUpdate) {
        if (update.sessionId != sessionId) reset(update)
        // Flows crossing dispatchers can deliver an old frame after a newer one; the
        // feed numbers frames precisely so this class can refuse to draw backwards.
        if (update.sequence <= lastSequence) return
        lastSequence = update.sequence

        drawTail(update)
        drawHead(update)
        update.puck?.let { animatePuck(it) }
    }

    public fun clear() {
        animator?.cancel()
        animator = null
        tailLine?.remove()
        tailLine = null
        headLine?.remove()
        headLine = null
        puckMarker?.remove()
        puckMarker = null
        halo?.remove()
        halo = null
        tailPoints.clear()
        decoder = PolylineCodec.Decoder()
        sessionId = null
        lastSequence = Long.MIN_VALUE
        shownPosition = null
        shownHeading = null
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun reset(update: LiveTrackUpdate) {
        clear()
        sessionId = update.sessionId
        decoder = PolylineCodec.Decoder(update.precision)
    }

    private fun drawTail(update: LiveTrackUpdate) {
        val appended = decoder.drain(update.frozenTailPolyline)
        if (appended.isEmpty()) return

        tailPoints += appended.map { LatLng(it.latitude, it.longitude) }
        if (tailPoints.size < 2) return

        val line = tailLine ?: map.addPolyline(
            PolylineOptions()
                .color(options.tailColor)
                .width(options.tailWidth)
                .zIndex(Z_TAIL),
        ).also { tailLine = it }
        // setPoints copies the list — O(tail) in the SDK, but no remove/re-add, no
        // flicker, and no per-fix object churn. The GMS API offers nothing cheaper.
        line.points = tailPoints
    }

    private fun drawHead(update: LiveTrackUpdate) {
        val head = update.liveHead
        if (head.size < 2) {
            headLine?.isVisible = false
            return
        }

        val line = headLine ?: map.addPolyline(
            PolylineOptions()
                .color(options.headColor)
                .width(options.headWidth)
                .zIndex(Z_HEAD),
        ).also { headLine = it }
        line.isVisible = true
        line.points = head.map { LatLng(it.latitude, it.longitude) }
    }

    private fun animatePuck(puck: PuckState) {
        val firstFrame = shownPosition == null
        val start = shownPosition ?: LatLng(puck.latitude, puck.longitude)
        val targetPoint = PuckAnimation.target(puck, options.lookaheadMs)
        val target = LatLng(targetPoint.latitude, targetPoint.longitude)

        val targetHeading = PuckAnimation.smoothedHeading(shownHeading, puck)
        val startHeading = shownHeading ?: targetHeading
        val headingDelta = if (startHeading != null && targetHeading != null) {
            Bearing.signedDifference(startHeading, targetHeading)
        } else {
            0.0
        }

        val marker = puckMarker ?: map.addMarker(
            MarkerOptions()
                .position(start)
                .icon(puckIcon())
                .anchor(CENTRE, CENTRE)
                .flat(true)
                .zIndex(Z_PUCK),
        )?.also { puckMarker = it } ?: return

        if (options.showAccuracyHalo) {
            val circle = halo ?: map.addCircle(
                CircleOptions()
                    .center(start)
                    .radius(puck.accuracyM.toDouble())
                    .fillColor(options.haloFillColor)
                    .strokeColor(options.haloStrokeColor)
                    .strokeWidth(HALO_STROKE_WIDTH)
                    .zIndex(Z_HALO),
            ).also { halo = it }
            circle.radius = puck.accuracyM.toDouble()
        }

        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = options.animationDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val eased = Geodesy.interpolate(
                    GeoPoint(start.latitude, start.longitude),
                    GeoPoint(target.latitude, target.longitude),
                    fraction.toDouble(),
                )
                val position = LatLng(eased.latitude, eased.longitude)
                marker.position = position
                halo?.center = position
                if (startHeading != null) {
                    val heading = (startHeading + headingDelta * fraction).mod(360.0)
                    marker.rotation = heading.toFloat()
                    shownHeading = heading
                }
                shownPosition = position
            }
            start()
        }

        followCamera(target, targetHeading, firstFrame)
    }

    /**
     * Chained easing: each frame starts the next camera animation immediately. Waiting
     * for completion would open a still gap at the end of every fix interval.
     */
    private fun followCamera(target: LatLng, heading: Double?, firstFrame: Boolean) {
        val durationMs = options.animationDurationMs.toInt().coerceAtLeast(1)
        if (cameraFollow == CameraFollowMode.NONE) return
        if (cameraFollow == CameraFollowMode.FOLLOW) {
            map.animateCamera(
                CameraUpdateFactory.newLatLng(target),
                durationMs,
                null,
            )
            return
        }

        val zoom = if (firstFrame) options.followZoom else map.cameraPosition.zoom
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(zoom)
                    .tilt(options.followTilt)
                    .bearing((heading ?: map.cameraPosition.bearing.toDouble()).toFloat())
                    .build(),
            ),
            durationMs,
            null,
        )
    }

    private fun puckIcon(): BitmapDescriptor =
        puckIcon ?: ArrowIcons.puck(options.puckSizePx, options.puckColor).also { puckIcon = it }

    private companion object {
        const val Z_HALO = 0.5f
        const val Z_TAIL = 1f
        const val Z_HEAD = 1.1f
        const val Z_PUCK = 5f
        const val CENTRE = 0.5f
        const val HALO_STROKE_WIDTH = 2f
    }
}
