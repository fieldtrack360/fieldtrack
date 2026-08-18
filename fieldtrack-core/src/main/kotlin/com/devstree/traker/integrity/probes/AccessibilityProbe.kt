package com.devstree.traker.integrity.probes

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.devstree.traker.SecurityConfig
import com.devstree.traker.integrity.IntegrityFinding
import com.devstree.traker.integrity.IntegritySignal
import com.devstree.traker.integrity.internal.IntegrityEnvironment
import com.devstree.traker.integrity.internal.IntegrityObservation
import com.devstree.traker.integrity.internal.IntegrityProbe

/**
 * Non-system accessibility services that are currently enabled.
 *
 * An accessibility service can read and drive the host app's UI, which is how click-farm
 * and attendance-spoofing tooling automates "being at work". It is also how a blind user
 * operates their phone — so this probe filters hard, and its default policy is `WARN`
 * rather than `BLOCK` (see `SecurityConfig.accessibility`).
 *
 * Filtered out, always:
 * - services installed as part of the system image (TalkBack, Switch Access, Voice Access,
 *   Select to Speak and every OEM equivalent),
 * - the host's own package — an app is allowed to automate itself,
 * - anything in `SecurityConfig.accessibilityAllowlist`.
 *
 * Two sources are consulted because neither is reliable alone: [AccessibilityManager]
 * answers what the framework has bound, while `Settings.Secure` answers what the user has
 * switched on. Some OEM ROMs under-report the first for services that are enabled but not
 * yet bound, and the settings string is the only place they appear.
 */
internal class AccessibilityProbe(
    private val context: Context,
) : IntegrityProbe {

    override fun observe(config: SecurityConfig): List<IntegrityObservation> {
        val packages = (fromManager() + fromSettings())
            .filterNot { it.isBlank() }
            .distinct()
            .filterNot { it == context.packageName }
            .filterNot { it in config.accessibilityAllowlist }
            .filterNot { isSystem(it) }

        if (packages.isEmpty()) return emptyList()

        return listOf(
            IntegrityObservation(
                signal = IntegritySignal.ACCESSIBILITY_SERVICE_ACTIVE,
                detail = packages.take(IntegrityFinding.MAX_DETAIL_ITEMS).joinToString(", "),
            ),
        )
    }

    private fun fromManager(): List<String> {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return emptyList()
        if (!manager.isEnabled) return emptyList()
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
    }

    /**
     * `enabled_accessibility_services` is a `:`-separated list of `package/ServiceClass`.
     * Read only when the master `accessibility_enabled` toggle is on — the list keeps its
     * entries after the user switches accessibility off wholesale.
     */
    private fun fromSettings(): List<String> {
        val resolver = context.contentResolver
        val master = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        if (master == 0) return emptyList()

        val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return emptyList()

        return raw.split(':').mapNotNull { entry ->
            entry.substringBefore('/').takeIf { it.isNotBlank() }
        }
    }

    private fun isSystem(packageName: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        IntegrityEnvironment.isSystemPackage(info)
    }.getOrDefault(false)
}
