package com.devstree.traker.domain.repository

import com.devstree.traker.TrackerConfig
import com.devstree.traker.domain.model.PointQuery
import com.devstree.traker.domain.model.TrackSession
import com.devstree.traker.geo.model.FixDecision
import com.devstree.traker.geo.model.TrackPoint
import kotlinx.coroutines.flow.Flow

/** Reads are paged by contract — a long session holds hundreds of thousands of rows (EC-80). */
public interface TrackPointRepository {
    public suspend fun query(query: PointQuery): List<TrackPoint>
    public fun observe(sessionId: String): Flow<List<TrackPoint>>
    public suspend fun count(query: PointQuery): Int
    public suspend fun insert(point: TrackPoint): Long
    public suspend fun delete(query: PointQuery): Int
    public suspend fun odometerMeters(): Double
    public suspend fun prune(cutoffMs: Long): Int
}

public interface SessionRepository {
    public suspend fun open(tag: String?, configSnapshot: String?): TrackSession
    public suspend fun close(sessionId: String): TrackSession?
    public suspend fun current(): TrackSession?
    public fun observeCurrent(): Flow<TrackSession?>
    public suspend fun range(fromMs: Long?, toMs: Long?): List<TrackSession>
}

public interface ConfigRepository {
    public suspend fun load(): TrackerConfig?
    public suspend fun save(config: TrackerConfig)
    public suspend fun clear()
}

public interface DecisionRepository {
    public suspend fun query(sessionId: String?, limit: Int, offset: Int): List<FixDecision>
    public suspend fun prune(cutoffMs: Long, maxRows: Int)
}
