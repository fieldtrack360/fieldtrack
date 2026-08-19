package com.field360.traker.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.XmlContext
import com.android.tools.lint.detector.api.XmlScanner
import org.w3c.dom.Attr

/**
 * `android:debuggable="true"` written into the manifest.
 *
 * The platform's own `HardcodedDebugMode` check covers this at `WARNING`. FieldTrack raises
 * it to `FATAL` for a reason specific to this SDK: `debuggable` is exactly what the license
 * gate and the device-integrity layer waive themselves on. A release APK carrying the flag
 * is not merely debuggable — it is a release APK with both security layers switched off, and
 * nothing at runtime can report that, because the reporting is part of what was disabled.
 *
 * The flag belongs to the build type, never to the manifest: AGP sets it for you on debug
 * builds and clears it on release ones.
 */
class DebuggableReleaseDetector : Detector(), XmlScanner {

    override fun getApplicableAttributes(): Collection<String> = listOf(ATTR_DEBUGGABLE)

    override fun visitAttribute(context: XmlContext, attribute: Attr) {
        if (attribute.namespaceURI != ANDROID_NS) return
        if (attribute.ownerElement?.tagName != TAG_APPLICATION) return
        if (!attribute.value.equals("true", ignoreCase = true)) return
        if (context.file.path.replace('\\', '/').contains("/src/debug/")) return

        context.report(
            DEBUGGABLE_RELEASE,
            attribute,
            context.getLocation(attribute),
            "`android:debuggable=\"true\"` waives the FieldTrack license gate and the entire " +
                "device-integrity layer. Remove it — AGP sets the flag per build type.",
        )
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val ATTR_DEBUGGABLE = "debuggable"
        private const val TAG_APPLICATION = "application"

        @JvmField
        val DEBUGGABLE_RELEASE: Issue = Issue.create(
            id = "FieldTrackDebuggableRelease",
            briefDescription = "Debuggable flag waives FieldTrack security",
            explanation = """
                FieldTrack waives its license check and its whole device-integrity layer \
                when the host application is debuggable — that is the deliberate development \
                escape hatch.

                Hardcoding `android:debuggable="true"` in the manifest carries that waiver \
                into every build, including the one on the Play Store. Let AGP set the flag \
                from the build type instead.
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.FATAL,
            implementation = Implementation(
                DebuggableReleaseDetector::class.java,
                Scope.MANIFEST_SCOPE,
            ),
        )
    }
}
