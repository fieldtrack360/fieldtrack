package com.devstree.trackit.sync

import com.devstree.trackit.sync.internal.executeOkHttpUpload
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The convenience transport.
 *
 * OkHttp is `compileOnly` in this module, so it is only linked if the host already has
 * it or adds it. Supply your own [SyncTransport] and this class is never touched.
 *
 * Timeouts match the reference (spec §22.1): connect 5 s, read 30 s, write 20 s.
 */
public class OkHttpSyncTransport(
    private val client: OkHttpClient = defaultClient(),
) : SyncTransport {

    override suspend fun upload(request: SyncRequest): SyncResponse =
        executeOkHttpUpload(client, request)

    public companion object {
        public fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
private const val CONNECT_SECONDS = 5L
private const val READ_SECONDS = 30L
private const val WRITE_SECONDS = 20L
