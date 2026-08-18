package com.devstree.trackit.sample

import android.app.Application
import com.devstree.fieldtrack.sample.BuildConfig
import com.devstree.fieldtrack.sample.CaptureLog
import com.devstree.trackit.AccuracyProfile
import com.devstree.trackit.LocationProviderType
import com.devstree.trackit.TrackIt
import com.devstree.trackit.TrackItConfig
import com.devstree.trackit.snap.OsrmSnapProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * A plain `Application` — no DI framework, no annotations, nothing the SDK requires.
 *
 * That is the point of the sample: `TrackIt.getInstance(this)` is the entire integration.
 * An earlier revision had to be `@HiltAndroidApp` because Hilt shipped inside the SDK;
 * it no longer does (see `TrackIt`'s KDoc).
 *
 * `ready()` is called once here rather than from an Activity: it restores persisted
 * filter state and reports an interrupted session, and both should happen before any
 * UI exists to observe them.
 */
class SampleApplication : Application() {

    val trackIt: TrackIt by lazy { TrackIt.getInstance(this) }

    val captureLog: CaptureLog by lazy { CaptureLog(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installRoadSnapping()
        scope.launch {
            // reset = true (the default) during development, so edited config actually
            // takes effect. Flipping it to false is the classic "my config changes do
            // nothing" bug (SDK-COMPARISON §5).
            trackIt.ready(
                // The builder rather than the constructor here on purpose: it is the
                // surface a Java host has, so the sample exercises it.
                TrackItConfig.builder()
                    .license(BuildConfig.TRACKIT_LICENSE.takeIf { it.isNotBlank() })
                    // Fused by default. Switch to GPS_ONLY on a device with no Play
                    // Services, or when a Wi-Fi centroid must never reach the record.
                    .provider(LocationProviderType.FUSED)
                    // The accuracy meter. BALANCED is the engine's own 30 m moving ceiling;
                    // STRICT (20 m) trades points for a line that never zigzags.
                    .accuracyProfile(AccuracyProfile.BALANCED)
                    // Raw fixes are layer 1 of the debug overlay. Off by default in the
                    // SDK because it is a diagnostic, not production behaviour — but the
                    // sample exists precisely to diagnose (spec §8.4).
                    .persistRawFixes(true)
                    // Layer 3: every judged fix in point form, so a missing point can
                    // be compared against the ones that made it (v6).
                    .persistRawPoints(true)
                    .build(),
            )
        }
    }

    /**
     * Road snapping, if `OSRM_BASE_URL` is set in `local.properties`.
     *
     * Blank is the default and a perfectly good configuration: no provider is installed,
     * `buildTrack` never leaves the device, and the polyline is drawn from the fixes that
     * were captured. Everything up to this point — the acceptance pipeline, cornering
     * process noise, the spline — makes that line as good as it can be *without a road
     * network*. This is the step that gives it one.
     *
     * Installed before `ready()` because a provider set after the first `buildTrack` would
     * leave that track unsnapped and every later one snapped, which looks like a bug in
     * the SDK rather than a race in the host.
     */
    private fun installRoadSnapping() {
        val baseUrl = BuildConfig.OSRM_BASE_URL
        if (baseUrl.isBlank()) return

        trackIt.setRoadSnapProvider(
            OsrmSnapProvider(
                baseUrl = baseUrl,
                client = OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(10))
                    .build(),
            ),
        )
    }
}
