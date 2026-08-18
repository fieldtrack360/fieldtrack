# Tracker sync — rules a consuming app inherits.
#
# Nothing. The previous rule here was:
#
#     -keep class com.devstree.traker.sync.** { *; }
#
# which kept every class and every member in the module, including `SyncQueue`'s internals
# and the OkHttp transport a host may not even be using. It was written for
# `SyncPayload` and `SyncPoint`, which are `@Serializable` — and those are already covered
# by `kotlinx-serialization-core`'s own consumer rules, which keep `Companion`,
# `serializer()` and the generated descriptor. The JSON keys are string literals in that
# descriptor, so the uploaded payload survives obfuscation unchanged.
#
# `SyncWorker` is covered by `work-runtime`'s `-keepnames class * extends
# androidx.work.ListenableWorker`, and the class itself survives shrinking because
# `OneTimeWorkRequestBuilder<SyncWorker>()` is reified into a real class reference.
#
# `SyncTransport` is an interface a host implements, so the host's own code keeps it.
#
# A blanket keep on a module is the easiest rule to write and the hardest to remove later,
# because nobody can tell afterwards which line it was protecting. Removing it costs the
# host a smaller APK and costs us nothing that is not already covered.
