package com.devstree.traker.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * The FieldTrack lint checks, as lint discovers them.
 *
 * Every issue here guards the same thing from a different angle: a **release** build that
 * ships with the device-integrity layer neutered. The runtime layer waives itself in a
 * debuggable build by design, which is what makes these checks necessary — the waiver is
 * the thing an integrator can accidentally (or deliberately) carry into production, and no
 * amount of runtime code can catch that from inside the process it has already been
 * switched off in.
 */
class FieldTrackIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = listOf(
        SecurityDisabledDetector.SECURITY_DISABLED,
        SecurityDisabledDetector.MOCK_LOCATION_ALLOWED,
        DebuggableReleaseDetector.DEBUGGABLE_RELEASE,
        LicenseHardcodedDetector.LICENSE_HARDCODED,
    )

    override val api: Int = CURRENT_API

    /** Lint 31.0 shipped with AGP 8.0; below that these detectors do not load. */
    override val minApi: Int = 14

    override val vendor: Vendor = Vendor(
        vendorName = "FieldTrack",
        identifier = "com.github.fieldtrack360.fieldtrack",
        feedbackUrl = "https://github.com/fieldtrack360/fieldtrack/issues",
    )
}
