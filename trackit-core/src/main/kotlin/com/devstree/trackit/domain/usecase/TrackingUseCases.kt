package com.devstree.trackit.domain.usecase

import android.content.Context
import com.devstree.trackit.LocationProviderType
import com.devstree.trackit.TrackItConfig
import com.devstree.trackit.TrackingMode
import com.devstree.trackit.capture.FixIngestor
import com.devstree.trackit.capture.LocationStreamController
import com.devstree.trackit.capture.OneShotProvider
import com.devstree.trackit.data.location.LocationSource
import com.devstree.trackit.data.repository.ConfigStore
import com.devstree.trackit.domain.model.ErrorCode
import com.devstree.trackit.domain.model.LocationAccuracy
import com.devstree.trackit.domain.model.PermissionTier
import com.devstree.trackit.domain.model.TrackItEvent
import com.devstree.trackit.domain.model.TrackItResult
import com.devstree.trackit.domain.model.TrackSession
import com.devstree.trackit.domain.repository.ConfigRepository
import com.devstree.trackit.domain.repository.SessionRepository
import com.devstree.trackit.motion.DeviceSensors
import com.devstree.trackit.motion.MotionQuality
import com.devstree.trackit.motion.SensorProbe
import com.devstree.trackit.motion.ActivityRecognizer
import com.devstree.trackit.motion.MotionController
import com.devstree.trackit.motion.SignificantMotionWake
import com.devstree.trackit.motion.StepCorroborator
import com.devstree.trackit.permission.PermissionManager
import com.devstree.trackit.service.TrackingService
import com.devstree.trackit.work.BackstopWorker
import com.devstree.trackit.work.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Opens a session and starts the capture pipeline.
 *
 * **Every start is a new session.** A session left open by process death is closed
 * first, so two separate runs never share an id.
 *
 * Still idempotent while tracking: calling it twice with the pipeline live returns the
 * existing session rather than starting a second service and a second stream (EC-72).
 */
public class StartTrackingUseCase internal constructor(
    private val sessions: SessionRepository,
    private val ingestor: FixIngestor,
    private val locationSource: LocationSource,
    private val streamController: LocationStreamController,
    private val motionController: MotionController,
    private val oneShotProvider: OneShotProvider,
    private val configStore: ConfigStore,
    private val permissions: PermissionManager,
    private val stepCorroborator: StepCorroborator,
    private val activityRecognizer: ActivityRecognizer,
    private val watchdog: Watchdog,
    private val context: Context,
    private val events: MutableSharedFlow<TrackItEvent>,
    private val scope: CoroutineScope,
    /**
     * Applies the parts of the config that are wired rather than passed: the provider
     * selection [LocationSource] routes on, and the engine constants the accuracy meter
     * moves. A lambda supplied by the graph, because both targets sit in layers this use
     * case must not import.
     *
     * Runs first, before the availability check — "is a provider available" is a question
     * about the provider the host chose, and answering it about the previous one is how a
     * GPS-only host on a Play-Services-free device gets told to install Play Services.
     */
    private val applyConfig: (TrackItConfig) -> Unit,
) {

    public suspend operator fun invoke(
        config: TrackItConfig,
        tag: String? = null,
    ): TrackItResult<TrackSession> {
        applyConfig(config)

        // EC-01: no permission means a typed error, never a SecurityException and never
        // a service that starts and silently produces nothing.
        when (permissions.tier()) {
            PermissionTier.NONE ->
                return TrackItResult.Error(ErrorCode.PERMISSION_DENIED, "Location permission not granted")

            PermissionTier.FOREGROUND_ONLY -> {
                // Degrade rather than refuse (A16, EC-03). The session opens and tracks
                // while the app is visible; the host is told why background is missing.
                events.tryEmit(
                    TrackItEvent.Error(
                        ErrorCode.BACKGROUND_PERMISSION_MISSING,
                        "Tracking foreground-only; grant \"Allow all the time\" for background",
                    ),
                )
            }

            PermissionTier.FULL -> Unit
        }

        // EC-02: a 1-3 km error circle defeats every gate in the pipeline.
        if (permissions.accuracy() == LocationAccuracy.APPROXIMATE &&
            config.geolocation.trackingMode != TrackingMode.MOTION_ONLY
        ) {
            return TrackItResult.Error(
                ErrorCode.COARSE_ONLY,
                "Approximate location cannot support ${config.geolocation.trackingMode}",
            )
        }

        if (!locationSource.isAvailable()) {
            // EC-19: no Play Services (Huawei, AOSP). A host on such a device now has a
            // real remedy — `LocationProviderType.GPS_ONLY` and friends run on the platform
            // LocationManager and need no Play Services at all — so the message names it
            // rather than reporting a dead end.
            val message = when (config.geolocation.providerType) {
                LocationProviderType.FUSED ->
                    "Fused provider unavailable; set geolocation.providerType to GPS_ONLY " +
                        "or NETWORK_ONLY to track without Play Services"

                else ->
                    "${config.geolocation.providerType} provider is not present on this device"
            }
            events.tryEmit(TrackItEvent.Error(ErrorCode.PLAY_SERVICES_UNAVAILABLE, message))
            return TrackItResult.Error(ErrorCode.PLAY_SERVICES_UNAVAILABLE, message)
        }

        // A start() while the pipeline is already live is idempotent — `sessions.open()`
        // hands back the session being written to, so a double tap cannot split a drive
        // in two (EC-72).
        //
        // A start() while it is NOT live is a genuinely new run. Any session still marked
        // open at this point is a crash leftover the host was already told about via
        // `SessionInterrupted` (EC-66); closing it first means today's drive gets its own
        // session id instead of being appended to one from days ago.
        if (!ingestor.isRunning) {
            sessions.current()?.let { sessions.close(it.id) }
        }
        val session = sessions.open(tag, configStore.encode(config))
        ingestor.mockPolicy = config.geolocation.mockLocationPolicy
        ingestor.persistRawFixes = config.persistence.persistRawFixes
        ingestor.rawRingCapacity = config.persistence.rawRingCapacity
        ingestor.persistRawPoints = config.persistence.persistRawPoints
        ingestor.rawPointCapacity = config.persistence.rawPointRingCapacity
        ingestor.bearingChangeCaptureDeg = config.motion.bearingChangeCaptureDeg

        // Session-scoped sensor registration. Started here, torn down in stop() — a
        // pedometer left registered after a session is battery drain with nothing to
        // show for it, and that is the failure users blame the SDK for (EC-138).
        if (config.sensors.useStepCorroboration) {
            stepCorroborator.start(config.sensors)
            ingestor.stepsSinceLastPoint = stepCorroborator::consumeSteps
        }

        // Motion transitions drive cadence and the wake paths, but never gate capture.
        ingestor.onAcceptedPoint = motionController::onAcceptedPoint
        // The third cadence tier: raw fixes feed turn detection, turn detection feeds the
        // sampling rate. A callback rather than an injected dependency because the stream
        // controller already depends on the ingestor (EC-45).
        ingestor.onTurnBurst = streamController::setTurning
        ingestor.start(session, scope)
        watchdog.reset()
        oneShotProvider.resetFailures()

        motionController.start(config)
        streamController.start(config, vehicular = false)

        // Enrichment only: a label on the point and one extra fix at a motion change.
        // Denial degrades to speed + displacement rather than failing start() (EC-09).
        if (config.motion.activityRecognition) activityRecognizer.register()

        // The 15-minute safety net feeds the SAME ingestor, so it can never disagree
        // with the stream about where the user was last seen (SOURCE-AUDIT A3).
        BackstopWorker.enqueue(context, config.service.backstopIntervalMin)

        TrackingService.start(context, config.service)

        events.tryEmit(TrackItEvent.EnabledChange(enabled = true))
        return TrackItResult.Ok(session)
    }
}

/** Closes the session and tears the pipeline down. No-op when never started (EC-74). */
public class StopTrackingUseCase internal constructor(
    private val sessions: SessionRepository,
    private val ingestor: FixIngestor,
    private val streamController: LocationStreamController,
    private val motionController: MotionController,
    private val stepCorroborator: StepCorroborator,
    private val activityRecognizer: ActivityRecognizer,
    private val significantMotion: SignificantMotionWake,
    private val watchdog: Watchdog,
    private val context: Context,
    private val events: MutableSharedFlow<TrackItEvent>,
) {
    public suspend operator fun invoke(): TrackItResult<TrackSession?> {
        val current = sessions.current() ?: return TrackItResult.Ok(null)

        // Order matters: stop feeding the channel, then close the session, so no point
        // is written after the session it belongs to has ended (EC-73).
        streamController.release()
        motionController.stop()
        ingestor.onAcceptedPoint = null
        ingestor.onTurnBurst = null
        ingestor.stop()

        // Sensors and system-registered wakes come down in the SAME teardown as the
        // location stream. Anything left armed here is silent battery drain (EC-138).
        stepCorroborator.stop()
        activityRecognizer.unregister()
        significantMotion.disarm()
        watchdog.reset()
        BackstopWorker.cancel(context)
        TrackingService.stop(context)

        val closed = sessions.close(current.id)

        events.tryEmit(TrackItEvent.EnabledChange(enabled = false))
        return TrackItResult.Ok(closed)
    }
}

/**
 * Resolves the effective config at startup and reports where each value came from.
 *
 * The `reset` flag is the classic footgun: with `reset = false` the supplied config is
 * ignored after the first launch, so a developer edits constants, rebuilds, and nothing
 * changes. Logging the effective source makes that answerable from logcat instead of a
 * support thread (SDK-COMPARISON §5).
 */
public class ResolveConfigUseCase internal constructor(
    private val repository: ConfigRepository,
    private val sensorProbe: SensorProbe,
    private val events: MutableSharedFlow<TrackItEvent>,
) {
    public suspend operator fun invoke(supplied: TrackItConfig): Result {
        val persisted = repository.load()
        val resolved = when {
            supplied.reset -> supplied
            persisted != null -> persisted
            else -> supplied
        }

        val errors = resolved.validate()
        if (errors.isNotEmpty()) return Result(resolved, errors, ConfigSource.SUPPLIED, null)

        // Act on the hardware, don't merely report it. Running a motion-gated design on
        // a device that cannot support motion detection produces gaps the user blames on
        // the SDK, so degrade the mode and say which sensors are missing (EC-137).
        val sensors = sensorProbe.probe()
        val effective = if (sensors.motionQuality == MotionQuality.POOR &&
            resolved.geolocation.trackingMode != TrackingMode.CONTINUOUS
        ) {
            events.tryEmit(
                TrackItEvent.Error(
                    ErrorCode.MOTION_DETECTION_DEGRADED,
                    "motionQuality=POOR (accelerometer=${sensors.accelerometer}, " +
                        "gyroscope=${sensors.gyroscope}, significantMotion=${sensors.significantMotion}, " +
                        "stepDetector=${sensors.stepDetector}); forcing CONTINUOUS",
                ),
            )
            resolved.copy(
                geolocation = resolved.geolocation.copy(trackingMode = TrackingMode.CONTINUOUS),
            )
        } else {
            resolved
        }

        repository.save(effective)
        val source = when {
            supplied.reset -> ConfigSource.SUPPLIED
            persisted != null -> ConfigSource.PERSISTED
            else -> ConfigSource.DEFAULT
        }
        return Result(effective, emptyList(), source, sensors)
    }

    public data class Result(
        val config: TrackItConfig,
        val validationErrors: List<String>,
        val source: ConfigSource,
        val sensors: DeviceSensors?,
    )

    public enum class ConfigSource { DEFAULT, PERSISTED, SUPPLIED }
}
