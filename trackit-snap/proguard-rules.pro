# TrackIt snap — build-time R8 configuration for the published release AAR.
#
# See trackit-core/proguard-rules.pro for the consumer-rules distinction and the honest
# limits of obfuscation. The public surface is one class; everything else — the chunk
# cache, the OSRM response model, the matching heuristics' privates — obfuscates. The
# OSRM wire format survives renaming by construction: kotlinx-serialization bakes the
# JSON field names into the generated descriptors as string literals.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep public class com.devstree.trackit.snap.OsrmSnapProvider { public protected *; }
-keep public class com.devstree.trackit.snap.OsrmSnapProvider$* { public protected *; }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-repackageclasses 'tr.dev.snap'
