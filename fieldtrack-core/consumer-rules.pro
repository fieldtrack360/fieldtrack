# Tracker core — rules a consuming app inherits.
#
# These are *consumer* rules: they are merged into the host's R8 configuration, so every
# line here constrains somebody else's build. That is the reason this file is short and
# specific rather than a `-keep class com.field360.tracker.** { *; }`. A blanket keep on
# an SDK this size would add several hundred KB to every host's APK to protect against a
# handful of reflective lookups, and it would hide the next one rather than document it.
#
# Most of what an SDK like this needs is already shipped by the libraries themselves, and
# duplicating their rules here would mean maintaining a stale copy:
#
#   kotlinx-serialization  keeps `Companion`, `serializer()`, `INSTANCE` and
#                          `$$serializer.descriptor` for every `@Serializable` class,
#                          and keeps the annotations. Field *names* need no keep: the
#                          generated descriptor carries them as string literals, so the
#                          wire format survives obfuscation by construction.
#   room-runtime           `-keep class * extends androidx.room.RoomDatabase`, which
#                          covers TrackerDatabase and the generated `_Impl` it loads.
#                          Entities and DAOs are referenced statically by generated code.
#   work-runtime           `-keepnames class * extends androidx.work.ListenableWorker`
#                          plus their constructors, which covers BackstopWorker,
#                          RestoreWorker and PruneWorker.
#   AGP                    generates keeps from the merged manifest, which covers
#                          TrackingService, BootReceiver, ActivityTransitionReceiver and
#                          StationaryFenceReceiver.
#
# What is left is the one thing nothing else covers.

# ── enum constant names ─────────────────────────────────────────────────────
#
# THE rule in this file, and the only one whose absence is a silent data bug rather than
# a crash.
#
# Stored points persist `movementStatus`, `detectedActivity` and `motionState` as their
# enum *name*, and read them back with `valueOf` (data/db/Mappers.kt, Repositories.kt).
# Those call sites are `runCatching { … }.getOrDefault(…)` — deliberately, because a row
# written by a newer version must not crash an older one. The consequence under
# obfuscation is that a renamed constant does not throw: it falls through to the default.
#
# So without this rule, a host's *release* build reads every stored point back as
# STEADY / UNKNOWN / STOPPED. No exception, no log, no failed request. Motion history,
# activity segments and the debug overlay are all quietly wrong, in the one build
# configuration that ships and the one nobody runs the test suite against.
#
# `<fields>` is what preserves the constant names; `values()`/`valueOf()` keep the
# accessors a host may call on a name it persisted itself.
-keepclassmembers,allowoptimization enum com.field360.tracker.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# `IntegritySignal` is covered by the rule above and depends on it for the same reason,
# one step further out: its constant names are uploaded verbatim in `integrity_signals`
# and a backend rule matches on them. A renamed constant there is a security signal that
# silently stops matching in release builds only.

# `fieldtrack-geo` ships the equivalent rule in its own AAR. This duplicate remains because
# core persists geo enum names directly and must protect them even if dependency rule
# aggregation changes. The rule is idempotent in a host R8 configuration.
