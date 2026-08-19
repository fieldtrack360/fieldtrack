# Tracker maps — build-time R8 configuration for the published release AAR.
#
# See fieldtrack-core/proguard-rules.pro for the consumer-rules distinction and the honest
# limits of obfuscation. Only the three renderer entry points and their public option
# types retain names. Compiler-generated helpers are optimized away and private methods
# are shortened.

-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*,AnnotationDefault
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep public class com.field360.traker.maps.ArrowIcons { public protected *; }
-keep public class com.field360.traker.maps.TrackRenderer { public protected *; }
-keep public class com.field360.traker.maps.TrackRenderer$RendererOptions { public protected *; }
-keep public class com.field360.traker.maps.LiveTrackRenderer { public protected *; }
-keep public class com.field360.traker.maps.LiveTrackRenderer$Options { public protected *; }
-keep public class com.field360.traker.maps.LiveTrackRenderer$CameraFollowMode { public protected *; }

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-repackageclasses 'tr.dev.maps'
