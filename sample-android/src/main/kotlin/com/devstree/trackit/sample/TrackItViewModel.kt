package com.devstree.trackit.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.devstree.trackit.RawFix
import com.devstree.trackit.TrackIt
import com.devstree.trackit.domain.model.ErrorCode
import com.devstree.trackit.domain.model.PermissionTier
import com.devstree.trackit.domain.model.PointQuery
import com.devstree.trackit.domain.model.TrackItEvent
import com.devstree.trackit.domain.model.TrackItGeofence
import com.devstree.trackit.domain.model.TrackItResult
import com.devstree.trackit.domain.model.TrackSession
import com.devstree.trackit.geo.model.FixDecision
import com.devstree.trackit.geo.math.Geodesy
import com.devstree.trackit.geo.model.GeoPoint
import com.devstree.trackit.geo.model.MotionState
import com.devstree.trackit.domain.model.ProviderState
import com.devstree.trackit.geo.model.TrackPoint
import com.devstree.trackit.geo.plot.model.Track
import com.devstree.trackit.geo.plot.model.TrackOptions
import com.devstree.trackit.permission.PermissionManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One view model over the whole SDK surface.
 *
 * Deliberately thin: the sample exists to exercise `TrackIt`, not to demonstrate app
 * architecture. Everything interesting lives behind the SDK boundary.
 */
class TrackItViewModel(
    private val trackIt: TrackIt,
    private val captureLog: CaptureLog,
) : ViewModel() {

    /**
     * [PermissionManager.BackgroundRequest] flattened for the UI. The SDK's version
     * carries a Settings `Intent`, which has no business in a state holder — the host
     * already knows how to open its own settings page.
     */
    enum class BackgroundStep {
        GRANTED,
        NOT_APPLICABLE,
        NEEDS_FOREGROUND_FIRST,

        /** API 29 only — a runtime prompt still works. */
        PROMPT,

        /** API 30+ — Settings is the only route (EC-05). */
        SETTINGS,
    }

    data class UiState(
        val isTracking: Boolean = false,
        val sessionId: String? = null,
        val motionState: MotionState = MotionState.STOPPED,
        val providerState: ProviderState = ProviderState(),
        val pointCount: Int = 0,
        val lastEvent: String = "",
        val lastHeartbeatAtMs: Long? = null,
        val error: String? = null,
        val points: List<TrackPoint> = emptyList(),
        val rawFixes: List<RawFix> = emptyList(),
        val decisions: List<FixDecision> = emptyList(),
        val track: Track? = null,
        val log: List<String> = emptyList(),
        val permissionTier: PermissionTier = PermissionTier.NONE,
        val backgroundStep: BackgroundStep = BackgroundStep.NOT_APPLICABLE,
        val backgroundAttempts: Int = 0,
        val showBackgroundDialog: Boolean = false,
        val licenseStatus: String = "",
        val logPath: String = "",
        val logSizeBytes: Long = 0,
        /** Newest first. Every session ever recorded, for the Home list. */
        val sessions: List<TrackSession> = emptyList(),
        /** Which session the Track/Debug/Decisions tabs are showing. */
        val selectedSessionId: String? = null,
        /**
         * Whether to ask the installed `RoadSnapProvider` for road geometry.
         *
         * Inert with no provider installed — `buildTrack` never leaves the device and the
         * flag changes nothing. With one installed it is the comparison that matters:
         * the same fixes drawn against the road network and drawn against themselves.
         */
        val snapToRoad: Boolean = true,
        /** `snap_unavailable` and friends, straight off the built track (EC-100). */
        val trackWarnings: List<String> = emptyList(),
        /** Result of the latest manually triggered SDK API check. */
        val apiCheckResult: String = "No API check run yet",
        val apiCheckRunning: Boolean = false,
        val registeredGeofenceCount: Int = 0,
        val geofenceEventCount: Int = 0,
        val geofences: List<TrackItGeofence> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * One-shot toasts, for watching capture happen without staring at the log.
     *
     * A [SharedFlow] rather than a state field because a toast is an event: replaying it
     * on the next recomposition — which is what state would do — would show the same
     * point again every time the screen rotated.
     */
    private val _toasts = MutableSharedFlow<String>(
        extraBufferCapacity = TOAST_BUFFER,
        // A dense stretch of capture outruns any consumer. Dropping the backlog is right:
        // the newest point is the interesting one, and a queue of stale toasts would
        // still be draining long after the drive ended.
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private var lastToastAtMs = 0L

    init {
        refreshPermissions()
        refreshGeofenceCounts()
        writeRunHeader()
        loadSessions()
        viewModelScope.launch {
            // Application-scoped in the SDK, lifecycle-scoped here: the collector dies
            // with the view model and native state stays the truth (EC-113).
            trackIt.events.collect { event -> onEvent(event) }
        }
        viewModelScope.launch {
            trackIt.state.collect { sdk ->
                _state.update {
                    it.copy(
                        isTracking = sdk.isTracking,
                        sessionId = sdk.currentSessionId,
                        motionState = sdk.motionState,
                        providerState = sdk.providerState,
                    )
                }
            }
        }
    }

    /**
     * Written once per view-model creation. Without it a file spanning several days of
     * field testing is unreadable — you cannot tell which device, build or permission
     * tier produced a given run.
     */
    private fun writeRunHeader() {
        captureLog.runHeader(
            sensors = runCatching { trackIt.getSensors() }.getOrNull(),
            tier = trackIt.permissionTier(),
            accuracy = trackIt.permissions().accuracy(),
            provider = runCatching { trackIt.providerState().value }.getOrNull(),
            mapsKeyPresent = BuildConfig.MAPS_API_KEY.isNotEmpty(),
            licensePresent = BuildConfig.TRACKIT_LICENSE.isNotEmpty(),
        )
        _state.update {
            it.copy(
                licenseStatus = when {
                    BuildConfig.TRACKIT_LICENSE.isNotEmpty() -> "configured from local.properties"
                    else -> "debug installs waived; add TRACKIT_LICENSE for release builds"
                },
            )
        }
        refreshLogStats()
    }

    private fun refreshLogStats() {
        _state.update { it.copy(logPath = captureLog.path, logSizeBytes = captureLog.sizeBytes()) }
    }

    /** Wipes the capture file. Testing runs otherwise accumulate across days. */
    fun clearLog() {
        captureLog.clear()
        captureLog.note("CLEARED", "log truncated by user")
        refreshLogStats()
    }

    private fun onEvent(event: TrackItEvent) {
        // Every capture goes to the file first, before any UI-shaped summarising. The
        // in-memory `log` below is capped at LOG_LIMIT lines for the screen; the file is
        // the complete record.
        captureLog.event(event)

        val line = when (event) {
            is TrackItEvent.Location ->
                "ACCEPT  ${event.point.acceptReason}  acc=${"%.0f".format(event.point.accuracy)}m"
            is TrackItEvent.LocationRejected ->
                "${verdictOf(event.decision)}  ${event.decision.reason}"
            is TrackItEvent.MotionChange -> "MOTION  ${event.state}"
            is TrackItEvent.ActivityChange -> "ACTIVITY ${event.activity}"
            is TrackItEvent.ProviderChange -> "PROVIDER gps=${event.state.gpsEnabled} tier=${event.state.permission}"
            is TrackItEvent.Error -> "ERROR   ${event.code}: ${event.message}"
            is TrackItEvent.Diagnostic -> "DIAG    ${event.message}"
            is TrackItEvent.SessionInterrupted -> "SESSION interrupted ${event.session.id.take(8)}"
            is TrackItEvent.EnabledChange -> "ENABLED ${event.enabled}"
            is TrackItEvent.PowerSaveChange -> "POWER   saver=${event.enabled}"
            is TrackItEvent.GeofenceAdded -> "GEOFENCE added ${event.geofence.id}"
            is TrackItEvent.GeofenceRemoved -> "GEOFENCE removed ${event.geofenceId}"
            is TrackItEvent.GeofenceEntered -> "GEOFENCE enter ${event.geofence.id}"
            is TrackItEvent.GeofenceExited -> "GEOFENCE exit ${event.geofence.id}"
            is TrackItEvent.Heartbeat -> "HEARTBEAT ${event.atMs}"
        }

        if (event is TrackItEvent.Location) onPointCollected(event.point)
        if (event is TrackItEvent.GeofenceAdded ||
            event is TrackItEvent.GeofenceRemoved ||
            event is TrackItEvent.GeofenceEntered ||
            event is TrackItEvent.GeofenceExited
        ) {
            refreshGeofenceCounts()
        }

        _state.update { current ->
            current.copy(
                lastEvent = line,
                lastHeartbeatAtMs = (event as? TrackItEvent.Heartbeat)?.atMs ?: current.lastHeartbeatAtMs,
                error = (event as? TrackItEvent.Error)?.let { "${it.code}: ${it.message}" } ?: current.error,
                pointCount = current.pointCount + if (event is TrackItEvent.Location) 1 else 0,
                log = (listOf(line) + current.log).take(LOG_LIMIT),
            )
        }

        // The SDK degrades to foreground-only rather than refusing (A16, EC-03), so this
        // arrives as a non-fatal event on start() and again if the grant is revoked
        // mid-session. An error string alone is useless here — from Android 11 there is
        // no prompt the user could have missed, so the steps have to be shown.
        if (event is TrackItEvent.Error && event.code == ErrorCode.BACKGROUND_PERMISSION_MISSING) {
            showBackgroundRationale()
        }
    }

    /**
     * A point made it to storage: logcat line always, toast at a rate a human can read.
     *
     * Logcat is unthrottled on purpose — it is the record you grep afterwards, and a
     * dropped line there is a hole in the evidence. The toast is the opposite: it exists
     * to answer "is it capturing right now" at a glance, so one every couple of seconds
     * says as much as sixty would.
     */
    private fun onPointCollected(point: TrackPoint) {
        val collected = _state.value.pointCount + 1
        Log.i(
            TAG,
            "point #$collected reason=${point.acceptReason} " +
                "lat=${point.latitude} lng=${point.longitude} " +
                "acc=${"%.1f".format(point.accuracy)}m spd=${"%.1f".format(point.speedMps)}m/s " +
                "odo=${"%.0f".format(point.odometerMeters)}m session=${point.sessionId.take(SHORT_ID)}",
        )

        val now = System.currentTimeMillis()
        // The first point of a session always shows: it is the one that answers whether
        // capture started at all, which is exactly the question the reboot defect raised.
        if (collected > 1 && now - lastToastAtMs < TOAST_MIN_INTERVAL_MS) return
        lastToastAtMs = now
        _toasts.tryEmit(
            "Point $collected · ${point.acceptReason} · ${"%.0f".format(point.accuracy)}m",
        )
    }

    fun start() = viewModelScope.launch {
        captureLog.note("START", "requested tag=sample tier=${trackIt.permissionTier()}")
        when (val result = trackIt.start(tag = "sample")) {
            is TrackItResult.Ok -> {
                captureLog.note("START", "ok session=${result.value.id}")
                // A new session means new counters. Carrying the previous run's totals
                // over is how a "why does it say 400 points" question starts.
                loadSessions()
                _state.update {
                    it.copy(
                        error = null,
                        sessionId = result.value.id,
                        // Follow the live session, not whatever was last browsed.
                        selectedSessionId = result.value.id,
                        pointCount = 0,
                        points = emptyList(),
                        rawFixes = emptyList(),
                        decisions = emptyList(),
                        track = null,
                    )
                }
            }
            is TrackItResult.Error -> {
                captureLog.note("START", "failed code=${result.code} message=${result.message}")
                _state.update { it.copy(error = "${result.code}: ${result.message}") }
            }
        }
        refreshLogStats()
    }

    fun stop() = viewModelScope.launch {
        // Capture the id before stopping — afterwards there is no current session to ask.
        val sessionId = trackIt.currentSession()?.id ?: _state.value.sessionId
        trackIt.stop()
        captureLog.note("STOP", "session=${sessionId ?: "-"}")
        dumpSession(sessionId)
        loadSessions()
        refresh()
    }

    /** Requests a snapshot without starting or modifying a tracking session. */
    fun testCurrentLocation() = runApiCheck("CURRENT") {
        when (val result = trackIt.getCurrentLocation()) {
            is TrackItResult.Ok -> {
                val fix = result.value
                "OK lat=${"%.6f".format(fix.latitude)} lng=${"%.6f".format(fix.longitude)} " +
                    "acc=${"%.1f".format(fix.accuracy)}m provider=${fix.provider}"
            }
            is TrackItResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    /** Requests a snapshot and registers/replaces a stable test fence at that position. */
    fun addTestGeofence() = runApiCheck("GEOFENCE_ADD") {
        when (val location = trackIt.getCurrentLocation()) {
            is TrackItResult.Error ->
                "FAILED location ${location.code}: ${location.message}"
            is TrackItResult.Ok -> {
                val fix = location.value
                val fence = TrackItGeofence(
                    id = TEST_GEOFENCE_ID,
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                    radiusM = TEST_GEOFENCE_RADIUS_M,
                    onEnterEvent = "sample_test_enter",
                    onExitEvent = "sample_test_exit",
                )
                when (val added = trackIt.addGeofence(fence)) {
                    is TrackItResult.Ok -> {
                        refreshGeofenceCounts()
                        "OK id=${added.value.id} radius=${added.value.radiusM}m"
                    }
                    is TrackItResult.Error -> "FAILED ${added.code}: ${added.message}"
                }
            }
        }
    }

    /** Registers ten stable test fences in a ring around one current-location fix. */
    fun addTenTestGeofences() = runApiCheck("GEOFENCE_ADD_10") {
        when (val location = trackIt.getCurrentLocation()) {
            is TrackItResult.Error ->
                "FAILED location ${location.code}: ${location.message}"
            is TrackItResult.Ok -> {
                val origin = GeoPoint(location.value.latitude, location.value.longitude)
                var addedCount = 0
                var firstFailure: String? = null
                repeat(TEST_GEOFENCE_BATCH_SIZE) { index ->
                    val angle = 2.0 * PI * index / TEST_GEOFENCE_BATCH_SIZE
                    val center = Geodesy.offsetMeters(
                        origin = origin,
                        northM = cos(angle) * TEST_GEOFENCE_RING_RADIUS_M,
                        eastM = sin(angle) * TEST_GEOFENCE_RING_RADIUS_M,
                    )
                    val id = "$TEST_GEOFENCE_BATCH_PREFIX${index + 1}"
                    when (
                        val result = trackIt.addGeofence(
                            TrackItGeofence(
                                id = id,
                                latitude = center.latitude,
                                longitude = center.longitude,
                                radiusM = TEST_GEOFENCE_RADIUS_M,
                                onEnterEvent = "${id}_enter",
                                onExitEvent = "${id}_exit",
                            ),
                        )
                    ) {
                        is TrackItResult.Ok -> addedCount++
                        is TrackItResult.Error -> if (firstFailure == null) {
                            firstFailure = "${result.code}: ${result.message}"
                        }
                    }
                }
                refreshGeofenceCounts()
                if (addedCount == TEST_GEOFENCE_BATCH_SIZE) {
                    "OK added=$addedCount/$TEST_GEOFENCE_BATCH_SIZE"
                } else {
                    "FAILED added=$addedCount/$TEST_GEOFENCE_BATCH_SIZE first=$firstFailure"
                }
            }
        }
    }

    fun listTestGeofences() = runApiCheck("GEOFENCE_LIST") {
        val fences = trackIt.getGeofences()
        refreshGeofenceCounts()
        if (fences.isEmpty()) "OK no registered geofences" else {
            "OK count=${fences.size} ids=${fences.joinToString { it.id }}"
        }
    }

    fun getTestGeofence() = runApiCheck("GEOFENCE_GET") {
        val fence = trackIt.getGeofence(TEST_GEOFENCE_ID)
        if (fence == null) "OK test fence not registered" else {
            "OK id=${fence.id} lat=${"%.6f".format(fence.latitude)} " +
                "lng=${"%.6f".format(fence.longitude)} radius=${fence.radiusM}m"
        }
    }

    fun removeTestGeofence() = runApiCheck("GEOFENCE_REMOVE") {
        when (val result = trackIt.removeGeofence(TEST_GEOFENCE_ID)) {
            is TrackItResult.Ok -> {
                refreshGeofenceCounts()
                "OK removed=${result.value}"
            }
            is TrackItResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    fun removeAllTestGeofences() = runApiCheck("GEOFENCE_REMOVE_ALL") {
        when (val result = trackIt.removeAllGeofences()) {
            is TrackItResult.Ok -> {
                refreshGeofenceCounts()
                "OK removed=${result.value}"
            }
            is TrackItResult.Error -> "FAILED ${result.code}: ${result.message}"
        }
    }

    fun readTestGeofenceHistory() = runApiCheck("GEOFENCE_HISTORY") {
        val events = trackIt.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS)
        refreshGeofenceCounts()
        val latest = events.firstOrNull()
        if (latest == null) "OK no crossing events" else {
            "OK count=${events.size} latest=${latest.transition}:${latest.geofence.id}"
        }
    }

    fun clearTestGeofenceHistory() = runApiCheck("GEOFENCE_HISTORY_CLEAR") {
        val deleted = trackIt.deleteGeofenceEvents()
        refreshGeofenceCounts()
        "OK deleted=$deleted"
    }

    private fun runApiCheck(kind: String, block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.apiCheckRunning) return@launch
        _state.update { it.copy(apiCheckRunning = true, apiCheckResult = "$kind running...") }
        val result = runCatching { block() }
            .getOrElse { error -> "FAILED INTERNAL: ${error.message ?: error::class.simpleName}" }
        captureLog.note(kind, result)
        Log.i(TAG, "$kind $result")
        _state.update {
            it.copy(
                apiCheckRunning = false,
                apiCheckResult = "$kind $result",
                error = if (result.startsWith("FAILED")) result else null,
            )
        }
        _toasts.tryEmit("$kind ${if (result.startsWith("OK")) "passed" else "failed"}")
        refreshLogStats()
    }

    private fun refreshGeofenceCounts() {
        val geofences = trackIt.getGeofences()
        _state.update {
            it.copy(
                registeredGeofenceCount = geofences.size,
                geofenceEventCount = trackIt.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS).size,
                geofences = geofences,
            )
        }
    }

    /**
     * Everything the database holds for one session, appended on stop.
     *
     * The event stream only carries what happened while a collector was alive. This is
     * what actually got persisted — including fixes rejected before any event fired.
     */
    private suspend fun dumpSession(sessionId: String?) {
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)
        val raw = sessionId?.let { runCatching { trackIt.getRawFixes(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        captureLog.sessionDump(
            sessionId = sessionId,
            rawFixes = raw,
            decisions = trackIt.getDecisions(sessionId, limit = MAX_DECISIONS),
            points = trackIt.getPoints(query),
        )
        refreshLogStats()
    }

    /** Every session ever recorded, newest first. */
    fun loadSessions() = viewModelScope.launch {
        val all = trackIt.getSessions().sortedByDescending { it.startedAtMs }
        _state.update { it.copy(sessions = all) }
    }

    /**
     * Show a past session on the Track/Debug/Decisions tabs.
     *
     * Loads the same four layers `refresh()` does, but pinned to the chosen id rather
     * than to whatever session is currently open — otherwise tapping a session from
     * yesterday would draw today's track.
     */
    fun openSession(sessionId: String) = viewModelScope.launch {
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)
        val points = trackIt.getPoints(query)
        val track = trackIt.buildTrack(query, trackOptions())
        val decisions = trackIt.getDecisions(sessionId, limit = MAX_DECISIONS)
        val raw = runCatching { trackIt.getRawFixes(sessionId) }.getOrDefault(emptyList())

        captureLog.note("OPEN", "session=$sessionId points=${points.size} segments=${track.segments.size}")
        // Dump on open, not only on stop. Diagnosing a drive means looking at it *after*
        // the drive, often days later, and without this the only session you could ever
        // export was the one you had just finished. `Clear` on the Home tab is the
        // pressure valve for the file growth this costs.
        captureLog.sessionDump(sessionId, raw, decisions, points)
        captureLog.trackSummary(track)

        _state.update {
            it.copy(
                selectedSessionId = sessionId,
                points = points,
                rawFixes = raw,
                decisions = decisions,
                track = track,
                trackWarnings = track.warnings,
                pointCount = points.size,
            )
        }
    }

    private fun trackOptions() = TrackOptions(zoom = 15f, snapToRoad = _state.value.snapToRoad)

    /**
     * Toggle map-matching and rebuild.
     *
     * Worth having in the sample rather than as a config constant, because the honest
     * comparison is the two lines side by side: everything the SDK does offline is an
     * approximation of a road it cannot see, and this is the only way to look at how close
     * that approximation got.
     */
    fun setSnapToRoad(enabled: Boolean) {
        _state.update { it.copy(snapToRoad = enabled) }
        refresh()
    }

    /** Pulls all three overlay layers plus the built track for the selected session. */
    fun refresh() = viewModelScope.launch {
        // A session picked from the Home list wins: without this, switching tabs while
        // viewing an old session would silently snap back to the live one.
        val sessionId = _state.value.selectedSessionId
            ?: trackIt.currentSession()?.id
            ?: _state.value.sessionId
        val query = PointQuery(sessionId = sessionId, limit = MAX_POINTS)

        val points = trackIt.getPoints(query)
        val decisions = trackIt.getDecisions(sessionId, limit = MAX_DECISIONS)
        val raw = sessionId?.let { runCatching { trackIt.getRawFixes(it) }.getOrDefault(emptyList()) }
            ?: emptyList()
        val track = trackIt.buildTrack(query, trackOptions())
        val geofences = trackIt.getGeofences()

        _state.update {
            it.copy(
                points = points,
                decisions = decisions,
                rawFixes = raw,
                track = track,
                trackWarnings = track.warnings,
                pointCount = points.size,
                logPath = captureLog.path,
                logSizeBytes = captureLog.sizeBytes(),
                geofences = geofences,
                registeredGeofenceCount = geofences.size,
                geofenceEventCount = trackIt.getGeofenceEvents(limit = MAX_GEOFENCE_EVENTS).size,
            )
        }
    }

    /**
     * Re-read the ladder. Cheap, and the only correct thing to do on resume: a grant can
     * change in Settings while this process is alive, and the Settings route is the whole
     * point of the background step (EC-05).
     */
    fun refreshPermissions() {
        val step = when (trackIt.permissions().backgroundRequest()) {
            PermissionManager.BackgroundRequest.AlreadyGranted -> BackgroundStep.GRANTED
            PermissionManager.BackgroundRequest.NotApplicable -> BackgroundStep.NOT_APPLICABLE
            PermissionManager.BackgroundRequest.NeedsForegroundFirst -> BackgroundStep.NEEDS_FOREGROUND_FIRST
            is PermissionManager.BackgroundRequest.Prompt -> BackgroundStep.PROMPT
            is PermissionManager.BackgroundRequest.NeedsSettings -> BackgroundStep.SETTINGS
        }
        _state.update {
            it.copy(
                permissionTier = trackIt.permissionTier(),
                backgroundStep = step,
                // Granted while we were away — close the dialog instead of asking again.
                showBackgroundDialog = it.showBackgroundDialog && step.isActionable(),
            )
        }
    }

    /**
     * Open the rationale. Never the request itself: Play policy wants the user to
     * understand *why* before the background step, and on Android 11+ the OS shows no
     * prompt at all, so the dialog is the only place the Settings detour can be
     * explained.
     */
    fun showBackgroundRationale() {
        refreshPermissions()
        val current = _state.value
        if (!current.backgroundStep.isActionable()) return

        // Attempt cap: a "Don't ask again" user must never be prompt-looped, so once the
        // runtime prompt is spent the Settings route is all that is offered (EC-14).
        val step = if (current.backgroundStep == BackgroundStep.PROMPT &&
            trackIt.permissions().shouldStopAsking(current.backgroundAttempts)
        ) {
            BackgroundStep.SETTINGS
        } else {
            current.backgroundStep
        }

        _state.update { it.copy(backgroundStep = step, showBackgroundDialog = true) }
    }

    fun dismissBackgroundRationale() {
        _state.update { it.copy(showBackgroundDialog = false) }
    }

    /** The user agreed; the host performs the actual request or Settings jump. */
    fun onBackgroundRationaleConfirmed() {
        _state.update {
            it.copy(showBackgroundDialog = false, backgroundAttempts = it.backgroundAttempts + 1)
        }
    }

    private fun BackgroundStep.isActionable(): Boolean =
        this != BackgroundStep.GRANTED && this != BackgroundStep.NOT_APPLICABLE

    private fun verdictOf(decision: FixDecision): String =
        decision.verdict::class.simpleName.orEmpty().uppercase().padEnd(VERDICT_WIDTH)

    companion object {
        /**
         * Constructed from the `Application` rather than by a DI framework.
         *
         * `AndroidViewModelFactory.APPLICATION_KEY` is how a `ViewModel` reaches the
         * `Application` without being handed a `Context` — and this is the whole of the
         * sample's wiring. That is the part worth copying: the SDK asks the host for
         * nothing, so the host's DI story stays entirely the host's business.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SampleApplication
                TrackItViewModel(app.trackIt, app.captureLog)
            }
        }

        private const val LOG_LIMIT = 300
        private const val MAX_POINTS = 5_000
        private const val MAX_DECISIONS = 500
        private const val MAX_GEOFENCE_EVENTS = 5_000
        private const val TEST_GEOFENCE_ID = "sample-api-test"
        private const val TEST_GEOFENCE_BATCH_PREFIX = "sample-batch-"
        private const val TEST_GEOFENCE_BATCH_SIZE = 10
        private const val TEST_GEOFENCE_RADIUS_M = 100f
        private const val TEST_GEOFENCE_RING_RADIUS_M = 400.0
        private const val VERDICT_WIDTH = 7

        /** Matches the SDK's own `TrackIt/<tag>` logcat convention, so one grep finds both. */
        private const val TAG = "TrackIt/Sample"
        private const val SHORT_ID = 8
        private const val TOAST_BUFFER = 4

        /** Slightly longer than `Toast.LENGTH_SHORT`, so toasts never queue up behind each other. */
        private const val TOAST_MIN_INTERVAL_MS = 2_500L
    }
}
