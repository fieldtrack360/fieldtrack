package com.devstree.traker.di

import android.annotation.SuppressLint
import android.content.Context
import com.devstree.traker.Traker
import com.devstree.traker.TrakerConfig
import com.devstree.traker.capture.FixIngestor
import com.devstree.traker.capture.LiveTrackFeed
import com.devstree.traker.capture.LocationStreamController
import com.devstree.traker.capture.OneShotProvider
import com.devstree.traker.data.db.ActivitySegmentDao
import com.devstree.traker.data.db.FilterStateDao
import com.devstree.traker.data.db.FixDecisionDao
import com.devstree.traker.data.db.RawFixDao
import com.devstree.traker.data.db.RawPointDao
import com.devstree.traker.data.db.TrakerDatabase
import com.devstree.traker.data.db.TrackPointDao
import com.devstree.traker.data.db.TrackSessionDao
import com.devstree.traker.data.location.AccuracyTuning
import com.devstree.traker.data.location.FixMapper
import com.devstree.traker.data.location.FusedLocationSource
import com.devstree.traker.data.location.LocationSource
import com.devstree.traker.data.location.PlatformLocationSource
import com.devstree.traker.data.location.RoutingLocationSource
import com.devstree.traker.data.platform.AndroidBatteryProbe
import com.devstree.traker.data.platform.AndroidClock
import com.devstree.traker.data.platform.BatteryMonitor
import com.devstree.traker.data.platform.AndroidLogger
import com.devstree.traker.data.repository.ConfigRepositoryImpl
import com.devstree.traker.data.repository.ConfigStore
import com.devstree.traker.data.repository.DecisionRepositoryImpl
import com.devstree.traker.data.repository.PendingUploadStoreImpl
import com.devstree.traker.data.repository.RoomPointStore
import com.devstree.traker.data.repository.SessionRepositoryImpl
import com.devstree.traker.data.repository.TrackPointRepositoryImpl
import com.devstree.traker.domain.model.TrakerEvent
import com.devstree.traker.domain.repository.ConfigRepository
import com.devstree.traker.domain.repository.DecisionRepository
import com.devstree.traker.domain.repository.PendingUploadStore
import com.devstree.traker.domain.repository.SessionRepository
import com.devstree.traker.domain.repository.TrackPointRepository
import com.devstree.traker.domain.usecase.ResolveConfigUseCase
import com.devstree.traker.domain.usecase.StartTrackingUseCase
import com.devstree.traker.domain.usecase.StopTrackingUseCase
import com.devstree.traker.geo.filter.AcceptancePipeline
import com.devstree.traker.geo.filter.TrakerConstants
import com.devstree.traker.geo.motion.MotionStateMachine
import com.devstree.traker.geo.motion.TurnDetector
import com.devstree.traker.geo.port.Clock
import com.devstree.traker.geo.port.PointStore
import com.devstree.traker.geo.port.TrackLogger
import com.devstree.traker.integrity.IntegrityEnvironment
import com.devstree.traker.integrity.IntegrityEvaluator
import com.devstree.traker.integrity.IntegrityFeed
import com.devstree.traker.integrity.IntegrityMonitor
import com.devstree.traker.integrity.probes.AccessibilityProbe
import com.devstree.traker.integrity.probes.ClockIntegrityProbe
import com.devstree.traker.integrity.probes.DeveloperModeProbe
import com.devstree.traker.integrity.probes.HookingProbe
import com.devstree.traker.integrity.probes.MockLocationProbe
import com.devstree.traker.motion.ActivityRecognizer
import com.devstree.traker.motion.CaptureStream
import com.devstree.traker.motion.GeofenceRegistrar
import com.devstree.traker.motion.MotionController
import com.devstree.traker.motion.MotionWakeSource
import com.devstree.traker.motion.SensorProbe
import com.devstree.traker.motion.SignificantMotionWake
import com.devstree.traker.motion.StationaryFence
import com.devstree.traker.motion.StepCorroborator
import com.devstree.traker.permission.PermissionManager
import com.devstree.traker.permission.ProviderStateMonitor
import com.devstree.traker.service.HealthLoop
import com.devstree.traker.work.DaoUploadQueueStats
import com.devstree.traker.work.SyncScheduler
import com.devstree.traker.work.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * The SDK's object graph, wired by hand.
 *
 * This replaces Hilt, and the reversal is deliberate. Hilt inside `fieldtrack-core` forced
 * every consuming app to apply the Hilt Gradle plugin and annotate its `Application`
 * with `@HiltAndroidApp` — an integration tax on every host, and a hard blocker for any
 * host whose `Application` class is not its own to annotate (a React Native template's
 * `MainApplication`, a Unity or Flutter shell, a modular app whose `Application` lives in
 * another team's module). An SDK should absorb its own wiring; see CROSS-PLATFORM.md B-1.
 *
 * What is lost is compile-time graph verification. What replaces it is that the whole
 * graph is 60 readable lines in one file — a missing edge is a Kotlin compile error here
 * rather than a KSP error somewhere else, and a cycle is a `StackOverflowError` on first
 * touch rather than a build failure. Both are caught by simply constructing the graph,
 * which [TrakerGraphTest] does.
 *
 * Every member is `by lazy`, so nothing is built until something asks for it: touching
 * [permissions] does not open the database, and `Traker.getInstance()` does not do disk
 * I/O on the caller's thread.
 *
 * Scoping matches the Hilt graph it replaces exactly: everything here was `@Singleton`,
 * and the DAO providers were unscoped only because they delegate to a `@Singleton`
 * database. One process, one graph, one [FixIngestor] — two would mean two filter states
 * writing one table.
 */
internal class TrakerGraph private constructor(
    @JvmField val context: Context,
) {

    // ── ports and primitives ────────────────────────────────────────────────

    /**
     * Replay 0, unlimited subscribers, and a buffer so a slow collector cannot stall the
     * ingestor. Never a `var callback` — the second registrant would silently replace
     * the first, and the host UI plus a background collector is the normal case (EC-112).
     */
    val events: MutableSharedFlow<TrakerEvent> by lazy {
        MutableSharedFlow(
            replay = 0,
            extraBufferCapacity = EVENT_BUFFER,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    }

    /** The SDK's own application-scoped coroutine scope; outlives any Activity. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    val clock: Clock by lazy { AndroidClock() }

    /**
     * Battery state: cached for the ingest path, broadcast-driven for the transitions.
     * Also what [Traker.batteryInfo] and [Traker.batteryState] read from, so a host and a
     * stored point can never disagree about what the charge was.
     */
    val batteryMonitor: BatteryMonitor by lazy {
        BatteryMonitor(context, AndroidBatteryProbe(context), clock, events)
    }
    val logger: TrackLogger by lazy { AndroidLogger() }

    /**
     * Every decision constant lives in this one object, which is what makes PLAN.md §3
     * invariant 1 ("no algorithm above fieldtrack-geo") mechanically checkable.
     */
    val constants: TrakerConstants by lazy { TrakerConstants.Default }
    val pipeline: AcceptancePipeline by lazy { AcceptancePipeline(constants) }
    val motionStateMachine: MotionStateMachine by lazy { MotionStateMachine() }
    val turnDetector: TurnDetector by lazy { TurnDetector(constants) }

    // ── storage ─────────────────────────────────────────────────────────────

    val database: TrakerDatabase by lazy { TrakerDatabase.build(context) }

    val pointDao: TrackPointDao by lazy { database.points() }
    val sessionDao: TrackSessionDao by lazy { database.sessions() }
    val decisionDao: FixDecisionDao by lazy { database.decisions() }
    val filterStateDao: FilterStateDao by lazy { database.filterState() }
    val activitySegmentDao: ActivitySegmentDao by lazy { database.activity() }
    val rawFixDao: RawFixDao by lazy { database.rawFixes() }
    val rawPointDao: RawPointDao by lazy { database.rawPoints() }

    val configStore: ConfigStore by lazy { ConfigStore(context) }

    val pointStore: PointStore by lazy { roomPointStore }
    val roomPointStore: RoomPointStore by lazy {
        RoomPointStore(pointDao, filterStateDao, decisionDao, rawFixDao, rawPointDao, events)
    }

    // ── repositories: domain declares the interface, data supplies the impl ──

    val trackPoints: TrackPointRepository by lazy { TrackPointRepositoryImpl(pointDao) }
    val sessions: SessionRepository by lazy { SessionRepositoryImpl(sessionDao, clock) }
    val decisions: DecisionRepository by lazy { DecisionRepositoryImpl(decisionDao) }
    val config: ConfigRepository by lazy { ConfigRepositoryImpl(configStore) }

    /** The one public door fieldtrack-sync uploads through. */
    val pendingUploads: PendingUploadStore by lazy { PendingUploadStoreImpl(pointDao) }

    /** The door in the other direction: core asking for a drain (G-4). */
    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(DaoUploadQueueStats(pointDao), clock, logger)
    }

    // ── platform seams ──────────────────────────────────────────────────────

    val fusedLocationSource: LocationSource by lazy { FusedLocationSource(context) }

    /**
     * What every capture path talks to. Routes to fused or to the platform
     * `LocationManager` per `GeolocationConfig.providerType`.
     */
    val locationSource: RoutingLocationSource by lazy {
        RoutingLocationSource(fusedLocationSource) { type -> PlatformLocationSource(context, type) }
    }

    val fixMapper: FixMapper by lazy { FixMapper(clock) }
    val permissions: PermissionManager by lazy { PermissionManager(context) }
    val sensorProbe: SensorProbe by lazy { SensorProbe(context, permissions) }

    /**
     * Deliberately the **fused** source, not the router. `ProviderState.fusedAvailable`
     * answers "is Play Services here", which is a fact about the device that a host uses to
     * decide whether to switch providers — routing it would make the field self-fulfilling
     * and report `true` for a host that had already given up on fused.
     */
    val providerStateMonitor: ProviderStateMonitor by lazy {
        ProviderStateMonitor(context, permissions, fusedLocationSource, events)
    }

    // ── motion seams — see MotionPorts.kt ───────────────────────────────────

    val significantMotion: SignificantMotionWake by lazy { SignificantMotionWake(context) }
    val motionWakeSource: MotionWakeSource by lazy { significantMotion }
    val stationaryFence: StationaryFence by lazy { StationaryFence(context, events, logger) }
    val geofenceRegistrar: GeofenceRegistrar by lazy { stationaryFence }
    val stepCorroborator: StepCorroborator by lazy { StepCorroborator(context) }
    val activityRecognizer: ActivityRecognizer by lazy {
        ActivityRecognizer(context, permissions, events, logger, scope)
    }

    // ── device integrity ────────────────────────────────────────────────────

    /**
     * Per-fix evidence for the integrity layer: mock fixes seen, GNSS-vs-system clock skew.
     * Written by the ingest path, read by the probes.
     */
    val integrityFeed: IntegrityFeed by lazy { IntegrityFeed(clock) }

    /**
     * The probe list, in signal order. Constructed eagerly inside the lazy so the platform
     * lookups happen once, not per evaluation.
     */
    val integrityEvaluator: IntegrityEvaluator by lazy {
        IntegrityEvaluator(
            probes = listOf(
                AccessibilityProbe(context),
                DeveloperModeProbe(context),
                HookingProbe(),
                ClockIntegrityProbe(context, integrityFeed),
                MockLocationProbe(context, integrityFeed),
            ),
            clock = clock,
            isWaived = { IntegrityEnvironment.isWaived(context) },
        )
    }

    val integrityMonitor: IntegrityMonitor by lazy {
        IntegrityMonitor(integrityEvaluator, events, integrityFeed)
    }

    // ── capture ─────────────────────────────────────────────────────────────

    val watchdog: Watchdog by lazy { Watchdog(clock, events) }
    val liveTrackFeed: LiveTrackFeed by lazy { LiveTrackFeed(trackPoints) }

    val ingestor: FixIngestor by lazy {
        FixIngestor(
            store = roomPointStore,
            pipeline = pipeline,
            turnDetector = turnDetector,
            constants = constants,
            clock = clock,
            watchdog = watchdog,
            events = events,
            liveTrack = liveTrackFeed,
            battery = batteryMonitor,
            integrityFeed = integrityFeed,
            integrityFlags = { integrityMonitor.flags },
        )
    }

    val streamController: LocationStreamController by lazy {
        LocationStreamController(locationSource, fixMapper, ingestor, logger, scope)
    }
    val captureStream: CaptureStream by lazy { streamController }

    val oneShotProvider: OneShotProvider by lazy {
        OneShotProvider(locationSource, fixMapper, ingestor, events, logger, providerStateMonitor)
    }

    val motionController: MotionController by lazy {
        MotionController(
            machine = motionStateMachine,
            streamController = captureStream,
            significantMotion = motionWakeSource,
            stationaryFence = geofenceRegistrar,
            clock = clock,
            events = events,
            logger = logger,
            scope = scope,
        )
    }

    val healthLoop: HealthLoop by lazy {
        HealthLoop(
            context, sessions, clock, events, watchdog, motionController, providerStateMonitor,
            syncScheduler, logger, integrityMonitor, stopTracking,
        )
    }

    // ── use cases ───────────────────────────────────────────────────────────

    val startTracking: StartTrackingUseCase by lazy {
        StartTrackingUseCase(
            sessions = sessions,
            ingestor = ingestor,
            locationSource = locationSource,
            streamController = streamController,
            motionController = motionController,
            oneShotProvider = oneShotProvider,
            configStore = configStore,
            permissions = permissions,
            stepCorroborator = stepCorroborator,
            activityRecognizer = activityRecognizer,
            watchdog = watchdog,
            syncScheduler = syncScheduler,
            context = context,
            events = events,
            scope = scope,
            applyConfig = ::applyConfig,
        )
    }

    /**
     * The two config values that are wired rather than passed: the provider the router
     * sends to, and the engine constants the accuracy meter moves.
     *
     * Applied per `start()` rather than at `ready()` because `ready()` only resolves the
     * config — a host may call it, read the resolved value, and start with something else.
     * Everything downstream is session-scoped anyway.
     */
    private fun applyConfig(config: TrakerConfig) {
        locationSource.select(config.geolocation.providerType)
        ingestor.retune(AccuracyTuning.apply(constants, config.geolocation))
    }

    val stopTracking: StopTrackingUseCase by lazy {
        StopTrackingUseCase(
            sessions = sessions,
            ingestor = ingestor,
            streamController = streamController,
            motionController = motionController,
            stepCorroborator = stepCorroborator,
            activityRecognizer = activityRecognizer,
            significantMotion = significantMotion,
            watchdog = watchdog,
            context = context,
            events = events,
        )
    }

    val resolveConfig: ResolveConfigUseCase by lazy {
        ResolveConfigUseCase(config, sensorProbe, events)
    }

    // ── the public surface ──────────────────────────────────────────────────

    val trackIt: Traker by lazy {
        Traker(
            startTracking = startTracking,
            stopTracking = stopTracking,
            resolveConfig = resolveConfig,
            sessions = sessions,
            points = trackPoints,
            decisions = decisions,
            rawFixes = rawFixDao,
            rawPoints = rawPointDao,
            ingestor = ingestor,
            oneShotProvider = oneShotProvider,
            liveTrackFeed = liveTrackFeed,
            clock = clock,
            providerStateMonitor = providerStateMonitor,
            batteryMonitor = batteryMonitor,
            sensorProbe = sensorProbe,
            integrityMonitor = integrityMonitor,
            stationaryFence = stationaryFence,
            permissions = permissions,
            context = context,
            scope = scope,
            eventSink = events,
        )
    }

    internal companion object {
        private const val EVENT_BUFFER = 64

        @SuppressLint("StaticFieldLeak") // get() stores only context.applicationContext.
        @Volatile
        private var instance: TrakerGraph? = null

        /**
         * The one graph for this process.
         *
         * Double-checked rather than `by lazy` on the object because it takes a
         * [Context]: the first caller supplies it, everyone after gets the same graph
         * whatever they pass. Always stored against the **application** context — a
         * graph holding an Activity would leak it for the process lifetime.
         */
        fun get(context: Context): TrakerGraph {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: TrakerGraph(context.applicationContext).also { instance = it }
            }
        }

        /** Test seam only. Drops the graph so the next [get] rebuilds it. */
        fun resetForTest() {
            synchronized(this) { instance = null }
        }
    }
}
