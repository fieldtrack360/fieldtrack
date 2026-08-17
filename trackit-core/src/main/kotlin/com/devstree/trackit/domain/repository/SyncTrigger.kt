package com.devstree.trackit.domain.repository

/**
 * How core asks for an upload without knowing what an upload is.
 *
 * The mirror of [PendingUploadStore]: that seam lets `trackit-sync` read the queue, this
 * one lets core say "there is something to send" — and neither carries a network type, so
 * core stays genuinely offline-first and a host that skips the sync artifact links no
 * network code at all.
 *
 * Registered by `trackit-sync` when `SyncConfig.autoSync` is on, and cleared when it is
 * off or when a 401/403 tears the configuration down. Null means nobody is listening, and
 * core does nothing — which is the correct behaviour for an SDK whose upload half is
 * optional (spec §3.4, §12.2).
 *
 * Implementations must be cheap and must not block: this is called from the ingest path.
 */
public fun interface SyncTrigger {

    /** Ask for a drain. Called often; expected to coalesce rather than queue up work. */
    public fun requestSync()
}
