package com.devstree.traker.sync

/**
 * What the upload half is doing, as it happens.
 *
 * [TrakerSync.syncNow] already returns the outcome of a drain the host asked for. This
 * exists for the drains it did not: [TrakerSync.requestSync] hands the work to WorkManager,
 * which may run it minutes later in a process the host is not watching, and until now the
 * only trace of what the server said was a debug log.
 *
 * Deliberately **not** a case on `TrakerEvent`. That flow belongs to `trackit-core`, which
 * never opens a socket; putting HTTP status codes in it would mean a host with no upload
 * module compiling against events it can never receive.
 */
public sealed interface SyncEvent {

    /**
     * One per completed exchange — so a three-batch drain emits three.
     *
     * @property statusCode what the server answered, or `null` when no HTTP response arrived
     *   at all (a dead network, a DNS failure, a timeout). The distinction matters: a `null`
     *   is a device problem and a 500 is a server problem, and a host showing "last upload"
     *   in a diagnostics screen should not report them the same way.
     * @property count rows in that batch. On a failure they are still queued — a count here
     *   is what was *attempted*, not what was stored.
     *
     * The response body is deliberately absent. It can be megabytes, and a host that needs
     * it implements [SyncTransport] and sees the whole exchange.
     */
    public data class HttpResponse(val statusCode: Int?, val count: Int) : SyncEvent
}
