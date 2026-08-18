# Traker snap — rules a consuming app inherits.
#
# Nothing. The previous rule here was:
#
#     -keep class com.devstree.traker.snap.** { *; }
#
# written for `OsrmMatchResponse` and friends, which are `@Serializable` — and `internal`,
# so a blanket public keep on them was protecting types no host can name anyway.
#
# `kotlinx-serialization-core`'s own consumer rules already keep what deserialisation
# needs: `Companion`, `serializer()`, and the generated descriptor whose element names —
# including the `@SerialName("matchings_index")` remappings — are string literals baked in
# at compile time. R8 renaming the Kotlin property does not rename the JSON key it reads.
#
# `OsrmSnapProvider` is a public class a host constructs by name in its own code, so the
# host keeps it.
