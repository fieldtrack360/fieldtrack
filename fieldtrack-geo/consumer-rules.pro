# Tracker geo consumer rules.
#
# Enum values are stable persisted and wire-format values. Preserve their names when a
# consuming application's R8 pass optimizes the already-obfuscated AAR.
-keepclassmembers,allowoptimization enum com.field360.traker.geo.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
