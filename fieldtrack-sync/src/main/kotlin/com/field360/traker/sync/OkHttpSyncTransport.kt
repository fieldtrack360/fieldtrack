package com.field360.traker.sync

import com.field360.traker.sync.internal.SyncService
import com.field360.traker.sync.internal.executeUpload
import com.field360.traker.sync.internal.serviceFor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The convenience transport.
 *
 * Retrofit and OkHttp are both `compileOnly` in this module, so neither is linked unless
 * the host already has them or adds them. Supply your own [SyncTransport] and this class is
 * never touched — which is the whole reason the dependency policy is what it is.
 *
 * The name is unchanged on purpose. It still takes an [OkHttpClient], because that is what a
 * host configures — proxies, certificate pinning, interceptors — and Retrofit runs on top of
 * exactly that client rather than replacing it.
 *
 * Timeouts match the reference (spec §22.1): connect 5 s, read 30 s, write 20 s. Overriding
 * them no longer means building a whole [OkHttpClient] — set [SyncConfig.timeouts] and the
 * values arrive on each [SyncRequest], applied here against a derived client.
 */
public class OkHttpSyncTransport(
    private val client: OkHttpClient = defaultClient(),
) : SyncTransport {

    /**
     * One service per distinct timeout set. Bounded in practice by how many timeout
     * combinations a host configures, which is one.
     */
    private val services = ConcurrentHashMap<SyncTimeouts, SyncService>()

    override suspend fun upload(request: SyncRequest): SyncResponse =
        executeUpload(
            service = serviceFor(client, request.timeouts, services),
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
