# TrackIt core — build-time R8 configuration for the published release AAR.
#
# Distinct from consumer-rules.pro, and the two must not be confused: that file is merged
# into the HOST's build and constrains somebody else's R8 pass; this one runs when the
# release AAR itself is assembled. Its job is IP protection — the capture pipeline, the
# graph, the service plumbing and every internal class are renamed and flattened into
# `tr.dev.core`, so the shipped artifact exposes the public contract
# and nothing else, even to a host that never enables minification of its own.
#
# What this does NOT do, stated so nobody relies on it: obfuscation renames symbols. It
# does not encrypt strings, does not hide the tuning constants (they must exist at
# runtime), and does not obscure control flow. The `Reasons` vocabulary and error
# messages survive verbatim because they are API. A determined reader with a decompiler
# still sees the algorithm's shape — renaming raises the effort, it does not make the
# code secret.

# ── attributes the public API needs to stay usable ──────────────────────────
#
# Signature keeps generics on the kept surface (a host sees Flow<TrackItEvent>, not raw
# Flow). InnerClasses/EnclosingMethod keep the nested-class structure of Builder and the
# sealed hierarchies. Runtime annotations carry kotlin.Metadata, which R8 rewrites for
# kept classes — without it a Kotlin host loses named arguments, default values and
# null-safety on the whole API.
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault

# Stack traces from the field must remain mappable (retrace against the mapping file
# under build/outputs/mapping/release/), but real source file names need not ship.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── the public contract ─────────────────────────────────────────────────────
#
# Hosts and the sibling artifacts (trackit-bridge, trackit-sync, trackit-maps) are
# compiled separately against these names, so the names are load-bearing. Kept by
# explicit package/class rather than by visibility on purpose: Kotlin `internal`
# compiles to public bytecode, so "keep everything public" would keep the internals
# this file exists to hide.

# Root API types are listed explicitly so generated BuildConfig, logging helpers,
# serializers and enum switch tables are not accidentally treated as host API.
-keep public class com.devstree.trackit.AccuracyConfig { public protected *; }
-keep public class com.devstree.trackit.AccuracyConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.AccuracyProfile { public protected *; }
-keep public class com.devstree.trackit.DesiredAccuracy { public protected *; }
-keep public class com.devstree.trackit.GeolocationConfig { public protected *; }
-keep public class com.devstree.trackit.GeolocationConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.LocationProviderType { public protected *; }
-keep public class com.devstree.trackit.MotionConfig { public protected *; }
-keep public class com.devstree.trackit.MotionConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.PersistenceConfig { public protected *; }
-keep public class com.devstree.trackit.PersistenceConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.RawFix { public protected *; }
-keep public class com.devstree.trackit.RawPoint { public protected *; }
-keep public class com.devstree.trackit.SensorConfig { public protected *; }
-keep public class com.devstree.trackit.SensorConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.ServiceConfig { public protected *; }
-keep public class com.devstree.trackit.ServiceConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.TrackIt { public protected *; }
-keep public class com.devstree.trackit.TrackIt$Companion { public protected *; }
-keep public class com.devstree.trackit.TrackItArtifacts { public protected *; }
-keep public class com.devstree.trackit.TrackItArtifacts$Companion { public protected *; }
-keep public class com.devstree.trackit.TrackItConfig { public protected *; }
-keep public class com.devstree.trackit.TrackItConfig$Builder { public protected *; }
-keep public class com.devstree.trackit.TrackItConfig$Companion { public protected *; }
-keep public class com.devstree.trackit.TrackingMode { public protected *; }

# The model and repository seams hosts and siblings consume. Generated serializers are
# deliberately omitted: companion serializer() methods remain stable, implementations
# are renamed and repackaged.
-keep public class com.devstree.trackit.domain.model.ErrorCode { public protected *; }
-keep public class com.devstree.trackit.domain.model.GeofenceTransition { public protected *; }
-keep public class com.devstree.trackit.domain.model.LocationAccuracy { public protected *; }
-keep public class com.devstree.trackit.domain.model.PermissionTier { public protected *; }
-keep public class com.devstree.trackit.domain.model.PointQuery { public protected *; }
-keep public class com.devstree.trackit.domain.model.PointQuery$Companion { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItGeofence { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItGeofence$Companion { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItGeofenceEvent { public protected *; }
-keep public class com.devstree.trackit.domain.model.ProviderState { public protected *; }
-keep public class com.devstree.trackit.domain.model.BatteryInfo { public protected *; }
-keep public class com.devstree.trackit.domain.model.BatteryInfo$Companion { public protected *; }
-keep public class com.devstree.trackit.domain.model.PowerSource { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItEvent { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItEvent$* { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItResult { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItResult$* { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackItState { public protected *; }
-keep public class com.devstree.trackit.domain.model.TrackSession { public protected *; }
-keep public class com.devstree.trackit.domain.repository.** { public protected *; }

# The permission ladder — PermissionManager and its nested BackgroundRequest hierarchy.
# The package also holds the internal ProviderStateMonitor, which is why this is a class
# glob and not a package glob.
-keep public class com.devstree.trackit.permission.PermissionManager { public protected *; }
-keep public class com.devstree.trackit.permission.PermissionManager$* { public protected *; }

# The two motion types on the public surface. Everything else in the package —
# controllers, wake sources, the sensor probe — is wiring.
-keep public class com.devstree.trackit.motion.DeviceSensors { public protected *; }
-keep public class com.devstree.trackit.motion.MotionQuality { public protected *; }

# ── reflective entry points ─────────────────────────────────────────────────
#
# Instantiated by name by the framework, so the names must survive whatever the keep
# rules above decided. AGP derives some of these from the manifest; they are restated
# here so this file is correct on its own rather than correct because of a tool
# behaviour nobody remembers.
-keep class com.devstree.trackit.service.TrackingService { <init>(); }
-keep class com.devstree.trackit.service.BootReceiver { <init>(); }
-keep class com.devstree.trackit.motion.ActivityTransitionReceiver { <init>(); }
-keep class com.devstree.trackit.motion.StationaryFenceReceiver { <init>(); }

# WorkManager's default factory instantiates workers reflectively by class name
# (BackstopWorker, RestoreWorker, PruneWorker). The dependency ships an equivalent
# consumer rule, but dependency consumer rules apply to APP builds — not to this
# library-mode pass — so it is needed here in its own right.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room resolves TrackItDatabase_Impl via Class.forName(database.name + "_Impl"); both
# ends of that lookup must keep their names. Same reasoning as the worker rule: Room's
# own consumer rule does not run in this pass.
-keep class * extends androidx.room.RoomDatabase

# ── shipped-artifact hygiene ────────────────────────────────────────────────
#
# Logging is useful in development but exposes control-flow and failure details in a
# published SDK. Strip every android.util.Log level from release bytecode.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Most SDK logging goes through the TrackLogger port rather than android.util.Log
# directly. Mark both methods side-effect-free so R8 also removes the string construction
# feeding those calls. The sample still receives structured TrackItEvent diagnostics.
-assumenosideeffects class com.devstree.trackit.geo.port.TrackLogger {
    public void d(java.lang.String, java.lang.String);
    public void w(java.lang.String, java.lang.String);
}

# Everything not kept above is renamed AND moved here, so the package tree itself stops
# describing the architecture (no more capture/, di/, data/db/ to read like a map).
-repackageclasses 'tr.dev.core'
