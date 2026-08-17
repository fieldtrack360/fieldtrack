package com.devstree.trackit.domain.repository

import com.devstree.trackit.TrackItConfig
import com.devstree.trackit.domain.model.PointQuery
import com.devstree.trackit.domain.model.TrackSession
import com.devstree.trackit.geo.model.FixDecision
import com.devstree.trackit.geo.model.TrackPoint
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
    public suspend fun load(): TrackItConfig?
    public suspend fun save(config: TrackItConfig)
    public suspend fun clear()
}

public interface DecisionRepository {
    public suspend fun query(sessionId: String?, limit: Int, offset: Int): List<FixDecision>
    public suspend fun prune(cutoffMs: Long, maxRows: Int)
}
