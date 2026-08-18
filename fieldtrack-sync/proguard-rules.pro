# Traker sync — build-time R8 configuration for the published release AAR.
#
# See fieldtrack-core/proguard-rules.pro for the consumer-rules distinction and the honest
# limits of obfuscation.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The public surface: TrakerSync, SyncConfig, SyncTransport and its request/response
# family, SyncQueue.Result, OkHttpSyncTransport. A host implementing SyncTransport
# compiles against these names.
-keep public class com.devstree.traker.sync.TrakerSync { public protected *; }
-keep public class com.devstree.traker.sync.TrakerSync$* { public protected *; }
-keep public class com.devstree.traker.sync.SyncConfig { public protected *; }
-keep public class com.devstree.traker.sync.SyncConfig$Builder { public protected *; }
-keep public class com.devstree.traker.sync.SyncConfig$Companion { public protected *; }
-keep public class com.devstree.traker.sync.SyncTransport { public protected *; }
-keep public class com.devstree.traker.sync.SyncRequest { public protected *; }
-keep public class com.devstree.traker.sync.SyncResponse { public protected *; }
-keep public class com.devstree.traker.sync.SyncResponse$* { public protected *; }
-keep public class com.devstree.traker.sync.SyncPayload { public protected *; }
-keep public class com.devstree.traker.sync.SyncPayload$Companion { public protected *; }
-keep public class com.devstree.traker.sync.SyncPoint { public protected *; }
-keep public class com.devstree.traker.sync.SyncPoint$Companion { public protected *; }
-keep public class com.devstree.traker.sync.OkHttpSyncTransport { public protected *; }
-keep public class com.devstree.traker.sync.OkHttpSyncTransport$Companion { public protected *; }
-keep public class com.devstree.traker.sync.SyncQueue { public protected *; }
-keep public class com.devstree.traker.sync.SyncQueue$* { public protected *; }
-keep public class com.devstree.traker.sync.SyncTimeouts { public protected *; }
-keep public class com.devstree.traker.sync.SyncTimeouts$Companion { public protected *; }
-keep public class com.devstree.traker.sync.SyncEvent { public protected *; }
-keep public class com.devstree.traker.sync.SyncEvent$* { public protected *; }

# WorkManager instantiates SyncWorker reflectively; the dependency's own consumer rule
# does not run in this library-mode pass.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class com.devstree.traker.geo.port.TrackLogger {
    public void d(java.lang.String, java.lang.String);
    public void w(java.lang.String, java.lang.String);
}

-repackageclasses 'tr.dev.sync'
