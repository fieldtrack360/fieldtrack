package com.devstree.traker.sync

import com.devstree.traker.sync.internal.clientFor
import com.devstree.traker.sync.internal.executeOkHttpUpload
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The convenience transport.
 *
 * OkHttp is `compileOnly` in this module, so it is only linked if the host already has
 * it or adds it. Supply your own [SyncTransport] and this class is never touched.
 *
 * Timeouts match the reference (spec §22.1): connect 5 s, read 30 s, write 20 s. Overriding
 * them no longer means building a whole [OkHttpClient] — set [SyncConfig.timeouts] and the
 * values arrive on each [SyncRequest], applied here against a derived client.
 */
public class OkHttpSyncTransport(
    private val client: OkHttpClient = defaultClient(),
) : SyncTransport {

    /**
     * One derived client per distinct timeout set. Bounded in practice by how many timeout
     * combinations a host configures, which is one.
     */
    private val derived = ConcurrentHashMap<SyncTimeouts, OkHttpClient>()

    override suspend fun upload(request: SyncRequest): SyncResponse =
        executeOkHttpUpload(
            client = clientFor(client, request.timeouts, derived),
            request = request,
            nowMs = System.currentTimeMillis(),
        )

    public companion object {
        public fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(SyncTimeouts.DEFAULT_CONNECT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SyncTimeouts.DEFAULT_READ_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(SyncTimeouts.DEFAULT_WRITE_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
