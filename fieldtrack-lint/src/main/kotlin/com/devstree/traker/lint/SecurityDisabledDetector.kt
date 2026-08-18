package com.devstree.traker.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UReferenceExpression

/**
 * Flags code that switches the FieldTrack device-integrity layer off, or lets mock
 * locations through, outside a debug source set.
 *
 * Both checks are `FATAL`, which is what makes them more than advice: AGP runs `lintVital`
 * as part of `assembleRelease`, so a host cannot ship a release APK with the layer disabled
 * without explicitly suppressing the check — a visible, reviewable act rather than a line
 * that slipped through in a hurry.
 *
 * Code under `src/debug/` is exempt. That exemption is the point of the design, not a hole
 * in it: the runtime layer already waives itself in a debuggable build, so a debug-only
 * override changes nothing that ships.
 */
class SecurityDisabledDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf(
        "securityEnabled",
        "hookingPolicy",
        "mockLocationIntegrityPolicy",
        "accessibilityPolicy",
        "developerModePolicy",
        "clockPolicy",
        "mockLocationPolicy",
    )

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (isDebugSourceSet(context)) return
        if (!context.evaluator.isMemberInSubClassOf(method, BUILDER_CLASS, false)) return

        val argument = node.valueArguments.firstOrNull() ?: return

        when (method.name) {
            "securityEnabled" -> {
                if (ConstantEvaluator.evaluate(context, argument) == false) {
                    context.report(
                        SECURITY_DISABLED,
                        node,
                        context.getLocation(node),
                        "Device-integrity checks are disabled in a release source set. " +
                            "The layer already waives itself in debuggable builds — move this " +
                            "to `src/debug/` or delete it.",
                    )
                }
            }

            "mockLocationPolicy" -> {
                if (argument.resolvesToEnumConstant(MOCK_POLICY_CLASS, "ALLOW")) {
                    context.report(
                        MOCK_LOCATION_ALLOWED,
                        node,
                        context.getLocation(node),
                        "`MockPolicy.ALLOW` stores fixes the platform flagged as mock. " +
                            "Use `FLAG` to keep and mark them, or `REJECT` to drop them.",
                    )
                }
            }

            else -> {
                if (argument.resolvesToEnumConstant(INTEGRITY_POLICY_CLASS, "ALLOW")) {
                    context.report(
                        SECURITY_DISABLED,
                        node,
                        context.getLocation(node),
                        "`IntegrityPolicy.ALLOW` switches off the ${method.name.removeSuffix("Policy")} " +
                            "integrity check entirely — not even reported. Use `WARN` to report " +
                            "without blocking.",
                    )
                }
            }
        }
    }

    /**
     * Resolves an enum reference by class **and** constant name.
     *
     * A source-text match on `"ALLOW"` would fire on any unrelated enum a host happened to
     * name that way, and a check that cries wolf in a `FATAL` issue gets suppressed
     * project-wide — which would take the real checks down with it.
     */
    private fun UExpression.resolvesToEnumConstant(className: String, constant: String): Boolean {
        val reference = this as? UReferenceExpression ?: return false
        val field = reference.resolve() as? PsiField ?: return false
        if (field.name != constant) return false
        return field.containingClass?.qualifiedName == className
    }

    private fun isDebugSourceSet(context: JavaContext): Boolean {
        val path = context.file.path.replace('\\', '/')
        return path.contains("/src/debug/") || path.contains("/src/androidTest/") ||
            path.contains("/src/test/")
    }

    companion object {
        private const val BUILDER_CLASS = "com.devstree.traker.TrakerConfig.Builder"
        private const val INTEGRITY_POLICY_CLASS = "com.devstree.traker.integrity.IntegrityPolicy"
        private const val MOCK_POLICY_CLASS = "com.devstree.traker.geo.model.MockPolicy"

        private val IMPLEMENTATION = Implementation(
            SecurityDisabledDetector::class.java,
            Scope.JAVA_FILE_SCOPE,
        )

        @JvmField
        val SECURITY_DISABLED: Issue = Issue.create(
            id = "FieldTrackSecurityDisabled",
            briefDescription = "FieldTrack device-integrity checks disabled",
            explanation = """
                The FieldTrack SDK's device-integrity layer detects accessibility automation, \
                developer mode, hooking frameworks such as Frida, clock tampering and \
                mock-location apps. It is already waived automatically in debuggable builds, \
                so disabling it in shared code only affects the release build your users run.

                If you need it off while developing, put the override in `src/debug/`.
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.FATAL,
            implementation = IMPLEMENTATION,
        )

        @JvmField
        val MOCK_LOCATION_ALLOWED: Issue = Issue.create(
            id = "FieldTrackMockLocationAllowed",
            briefDescription = "Mock locations stored unflagged",
            explanation = """
                `MockPolicy.ALLOW` stores fixes the platform itself marked as mock, with \
                nothing on the row to say so. Any downstream consumer — payroll, dispatch, \
                an audit — then reads a fabricated position as a real one.

                `FLAG` (the default) keeps them and marks them. `REJECT` drops them.
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 8,
            severity = Severity.FATAL,
            implementation = IMPLEMENTATION,
        )
    }
}
