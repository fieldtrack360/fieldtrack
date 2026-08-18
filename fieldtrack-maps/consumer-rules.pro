# Traker maps — rules a consuming app inherits.
#
# Nothing to keep, and this is the one module where that is true by design rather than by
# delegation to another library's rules.
#
# The renderer holds no reflected types, serialises nothing, and is constructed by the
# host where the map is. It consumes `Arrows.place()` and draws — every type it touches is
# reached through a static reference from host code, so R8 keeps exactly what is used and
# strips the rest, which is the correct outcome for a drawing helper a host may only
# partly use.
#
# The enums it hands back (`RenderTag`, `SegmentType`) are kept by `trackit-core`'s
# consumer rules for any host that also depends on core. A host depending on
# `trackit-maps` and `trackit-geo` alone does not persist or `valueOf` them — nothing in
# this module or in the plotting plane reads an enum back from a name — so there is
# nothing to preserve.
