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
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * A license token written as a string literal in source.
 *
 * `WARNING`, not `FATAL`: it works, and a host may have a considered reason for it. But a
 * literal is the copy that ends up in a screenshot, in a public repository and in the
 * decompiled APK, and the manifest `meta-data` route exists precisely so the token can come
 * from a properties file that is not committed.
 */
class LicenseHardcodedDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("license")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!context.evaluator.isMemberInSubClassOf(method, BUILDER_CLASS, false)) return

        // Evaluated rather than pattern-matched on `ULiteralExpression`: a Kotlin string
        // literal reaches UAST as a template expression, so the type check alone misses the
        // ordinary `license("...")` case this whole detector exists for.
        val argument = node.valueArguments.firstOrNull() ?: return
        val value = ConstantEvaluator.evaluate(context, argument) as? String ?: return
        if (value.isBlank()) return

        context.report(
            LICENSE_HARDCODED,
            node,
            context.getLocation(node),
            "FieldTrack license token is hardcoded. Prefer the `TrackItLicense` manifest " +
                "meta-data, fed from a gradle property that is not committed.",
        )
    }

    companion object {
        private const val BUILDER_CLASS = "com.devstree.traker.TrakerConfig.Builder"

        @JvmField
        val LICENSE_HARDCODED: Issue = Issue.create(
            id = "FieldTrackLicenseHardcoded",
            briefDescription = "FieldTrack license token hardcoded in source",
            explanation = """
                A token written as a string literal ships inside the APK in readable form \
                and tends to reach version control along with it.

                Put it in the manifest as `TrackItLicense` meta-data and supply the value \
                from a gradle property or an environment variable at build time.
            """.trimIndent(),
            category = Category.SECURITY,
            priority = 5,
            severity = Severity.WARNING,
            implementation = Implementation(
                LicenseHardcodedDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
