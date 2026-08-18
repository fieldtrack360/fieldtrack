package com.devstree.traker.data.repository

import com.devstree.traker.TrakerConfig
import com.devstree.traker.data.db.FixDecisionDao
import com.devstree.traker.data.db.TrackPointDao
import com.devstree.traker.data.db.TrackSessionDao
import com.devstree.traker.data.db.toDomain
import com.devstree.traker.data.db.toEntity
import com.devstree.traker.domain.model.PointQuery
import com.devstree.traker.domain.model.TrackSession
import com.devstree.traker.domain.repository.ConfigRepository
import com.devstree.traker.domain.repository.DecisionRepository
import com.devstree.traker.domain.repository.PendingUploadStore
import com.devstree.traker.domain.repository.SessionRepository
import com.devstree.traker.domain.repository.TrackPointRepository
import com.devstree.traker.geo.model.FixDecision
import com.devstree.traker.geo.model.MotionState
import com.devstree.traker.geo.model.TrackFix
import com.devstree.traker.geo.model.TrackPoint
import com.devstree.traker.geo.model.Verdict
import com.devstree.traker.geo.port.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

internal class TrackPointRepositoryImpl(
    private val dao: TrackPointDao,
) : TrackPointRepository {

    override suspend fun query(query: PointQuery): List<TrackPoint> =
        dao.query(query.sessionId, query.fromMs, query.toMs, query.limit, query.offset)
            .map { it.toDomain() }

    override fun observe(sessionId: String): Flow<List<TrackPoint>> =
        dao.observe(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun count(query: PointQuery): Int =
        dao.count(query.sessionId, query.fromMs, query.toMs)

    override suspend fun insert(point: TrackPoint): Long = dao.insert(point.toEntity())

    override suspend fun delete(query: PointQuery): Int =
        query.sessionId?.let { dao.deleteSession(it) } ?: 0

    override suspend fun odometerMeters(): Double = dao.odometer()

    override suspend fun prune(cutoffMs: Long): Int = dao.prune(cutoffMs)
}

/**
 * Drains through the public [PendingUploadStore] seam, so `trackit-sync` never sees a
 * Room type and core never sees a socket.
 */
internal class PendingUploadStoreImpl(
    private val dao: TrackPointDao,
) : PendingUploadStore {

    override suspend fun pending(limit: Int): List<TrackPoint> =
        dao.pendingUpload(limit).map { it.toDomain() }

    override suspend fun pendingCount(): Int = dao.pendingUploadCount()

    override suspend fun markSynced(uuids: List<String>, syncedAtMs: Long) {
        if (uuids.isEmpty()) return
        dao.markSynced(uuids, syncedAtMs)
    }

    override suspend fun clearQueue() {
        dao.clearQueue()
    }
}

internal class SessionRepositoryImpl(
    private val dao: TrackSessionDao,
    private val clock: Clock,
) : SessionRepository {

    override suspend fun open(tag: String?, configSnapshot: String?): TrackSession {
        // start() is idempotent: an already-open session is returned rather than a
        // second one being created alongside it (EC-72).
        dao.openSession()?.let { return it.toDomain() }

        val session = TrackSession(
            id = UUID.randomUUID().toString(),
            startedAtMs = clock.wallTimeMs(),
            startedAtElapsedNanos = clock.elapsedRealtimeNanos(),
            tag = tag,
            configSnapshot = configSnapshot,
        )
        dao.upsert(session.toEntity())
        return session
    }

    override suspend fun close(sessionId: String): TrackSession? {
        val existing = dao.byId(sessionId) ?: return null
        val closed = existing.copy(endedAtMs = clock.wallTimeMs())
        dao.upsert(closed)
        return closed.toDomain()
    }

    override suspend fun current(): TrackSession? = dao.openSession()?.toDomain()

    override fun observeCurrent(): Flow<TrackSession?> =
        dao.observeOpenSession().map { it?.toDomain() }

    override suspend fun range(fromMs: Long?, toMs: Long?): List<TrackSession> =
        dao.range(fromMs, toMs).map { it.toDomain() }
}

internal class DecisionRepositoryImpl(
    private val dao: FixDecisionDao,
) : DecisionRepository {

    override suspend fun query(sessionId: String?, limit: Int, offset: Int): List<FixDecision> =
        dao.query(sessionId, limit, offset).map { row ->
            FixDecision(
                fix = TrackFix(
                    timeMs = row.timeMs,
                    elapsedRealtimeNanos = row.elapsedRealtimeNanos,
                    receivedAtElapsedNanos = row.elapsedRealtimeNanos,
                    latitude = row.latitude,
                    longitude = row.longitude,
                    accuracy = row.accuracy,
                    bearingDeg = row.bearingDeg,
                    hasSpeed = row.hasSpeed,
                    hasBearing = row.hasBearing,
                ),
                verdict = when (row.verdict) {
                    "ACCEPT" -> Verdict.Accept(row.reason)
                    "SKIP" -> Verdict.Skip(row.reason)
                    else -> Verdict.Reject(row.reason)
                },
                filterLat = row.filterLat,
                filterLng = row.filterLng,
                sigma = row.sigma,
                threshold = row.threshold,
                distanceMovedM = row.distanceMovedM,
                effectiveSpeedMps = row.effectiveSpeedMps,
                motionState = runCatching { MotionState.valueOf(row.motionState) }
                    .getOrDefault(MotionState.STOPPED),
            )
        }

    /** Capped by age AND count, so a long trip cannot bloat the database (EC-87). */
    override suspend fun prune(cutoffMs: Long, maxRows: Int) {
        dao.pruneOlderThan(cutoffMs)
        if (maxRows > 0) dao.pruneToCount(maxRows)
    }
}

/** Config persistence. Unknown keys are dropped so a downgrade cannot brick startup (EC-124). */
internal class ConfigRepositoryImpl(
    private val store: ConfigStore,
) : ConfigRepository {
    override suspend fun load(): TrakerConfig? = store.load()
    override suspend fun save(config: TrakerConfig) = store.save(config)
    override suspend fun clear() = store.clear()
}
