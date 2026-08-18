package com.devstree.traker.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.devstree.traker.domain.model.LocationAccuracy
import com.devstree.traker.domain.model.PermissionTier

/**
 * Permission state, and the ladder needed to improve it.
 *
 * **The SDK shows no UI.** No dialogs, no activities, no full-screen intents. This
 * class answers questions and hands back the permission arrays and the Settings
 * intent; the host owns every prompt. The reference fired a full-screen `CATEGORY_CALL`
 * notification when tracking looked dead — an application decision with real Play
 * policy risk, and not an SDK's to make (PERMISSIONS.md §5).
 *
 * That is a deliberate deviation from the `suspend fun request(activity, level)` sketch
 * in PERMISSIONS.md §3: owning an `ActivityResultLauncher` from inside a library means
 * registering against a host lifecycle before RESUMED, which is fragile and forces the
 * host to hand over its Activity. Query + arrays + intent achieves the same ladder with
 * none of that coupling.
 */
public class PermissionManager internal constructor(
    private val context: Context,
) {

    /**
     * The tier decides what `start()` may do.
     *
     * The reference treated background location as a hard gate and stopped the service
     * outright without it, so a user who chose "While using the app" got *zero*
     * tracking — not even in the foreground. Defensible for an attendance product,
     * wrong for a general SDK (SOURCE-AUDIT A16, EC-03).
     */
    public fun tier(): PermissionTier = when {
        !hasAny(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) ->
            PermissionTier.NONE
        hasBackgroundLocation() -> PermissionTier.FULL
        else -> PermissionTier.FOREGROUND_ONLY
    }

    /**
     * Orthogonal to [tier] and always surfaced. A 1–3 km error circle defeats every gate
     * in the pipeline, so `CONTINUOUS`/`ADAPTIVE` refuse to start on approximate-only
     * unless the host explicitly opts in (EC-02, EC-12).
     */
    public fun accuracy(): LocationAccuracy =
        if (has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            LocationAccuracy.PRECISE
        } else {
            LocationAccuracy.APPROXIMATE
        }

    public fun hasActivityRecognition(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            has(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }

    public fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

    /**
     * Step 1 of the ladder. Fine and coarse go together in one request; asking for
     * background in the same array makes Android deny it silently (EC-04).
     */
    public fun foregroundPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Ask **first** on API 33+. An invisible foreground-service notification is a
     * transparency failure and an OEM-kill risk (EC-08).
     */
    public fun notificationPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyArray()
        }

    /** Optional. Denial degrades motion detection to speed + displacement, never fatal (EC-09). */
    public fun activityRecognitionPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            emptyArray()
        }

    /**
     * Step 2, and only after fine is granted and the host has shown a rationale.
     *
     * From Android 11 the OS will not show a background-location prompt at all, so
     * [BackgroundRequest.NeedsSettings] is the only honest answer — a runtime request
     * there appears to do nothing (EC-05).
     */
    public fun backgroundRequest(): BackgroundRequest = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> BackgroundRequest.NotApplicable
        hasBackgroundLocation() -> BackgroundRequest.AlreadyGranted
        // Background is only grantable AFTER fine. Asking before is a silent denial.
        !has(Manifest.permission.ACCESS_FINE_LOCATION) -> BackgroundRequest.NeedsForegroundFirst
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> BackgroundRequest.NeedsSettings(appSettingsIntent())
        else -> BackgroundRequest.Prompt(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    /**
     * Hard cap so a "Don't ask again" user is never prompt-looped; after this only the
     * Settings route is offered. The reference caps at 3 for the same reason (EC-14).
     */
    public fun shouldStopAsking(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    public fun appSettingsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun hasBackgroundLocation(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Before Android 10 there was no separate background permission.
            has(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasAny(vararg permissions: String): Boolean = permissions.any(::has)

    /** What the host must do next to reach [PermissionTier.FULL]. */
    public sealed interface BackgroundRequest {
        public data object AlreadyGranted : BackgroundRequest
        public data object NotApplicable : BackgroundRequest
        public data object NeedsForegroundFirst : BackgroundRequest

        /** API 29 only — a runtime prompt still works. */
        public data class Prompt(val permissions: Array<String>) : BackgroundRequest {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Prompt && permissions.contentEquals(other.permissions))

            override fun hashCode(): Int = permissions.contentHashCode()
        }

        /** API 30+ — deep-link to Settings and explain "Allow all the time". */
        public data class NeedsSettings(val intent: Intent) : BackgroundRequest
    }

    internal companion object {
        const val MAX_ATTEMPTS = 3
    }
}
