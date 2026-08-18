# Sample app — application-level R8 rules.
#
# The sample's release build is minified **on purpose**. It used to be
# `isMinifyEnabled = false`, which meant every `consumer-rules.pro` in this repository was
# shipped to hosts having never once been executed. Rules that are never exercised are
# guesses, and the guesses were wrong: the core rules kept a package that does not exist
# (`com.devstree.fieldtrack.db`, actual name `data.db`), and nothing anywhere preserved the
# enum constant names that stored points are read back by.
#
# So this app is the SDK's R8 test. `./gradlew :sample-android:assembleRelease` runs the
# real shrinker over the real library, with the real consumer rules, and fails if they are
# insufficient.
#
# Everything the SDK needs comes from its own consumer rules — that is the point of the
# exercise, and adding compensating keeps here would defeat it. What follows is the app's
# own business only.

# Compose keeps its own rules; Maps Compose and play-services-maps ship theirs.

# Line numbers in a crash report from a field test are worth more than the few KB the
# attribute costs. `SourceFile` is renamed rather than kept so the mapping file is still
# required to read them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The sample writes a plain-text capture log that is pulled off the device and read by a
# person hours later. Its `BuildConfig` fields are read into that file's header.
-keep class com.devstree.fieldtrack.sample.BuildConfig { *; }
