import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

private val releaseModules = listOf("geo", "core", "maps", "snap", "sync")

private data class ReleaseArtifact(
    val module: String,
    val requiredApi: List<String>,
    val obfuscatedPrefix: String?,
)

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = readBytes()
    val hash = digest.digest(bytes)
    return hash.joinToString("") { byte -> "%02x".format(byte) }
}

/** Audits shipped bytecode instead of trusting build flags or ProGuard comments. */
abstract class VerifyReleaseObfuscationTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = repositoryRoot.get().asFile
        val artifacts = listOf(
            ReleaseArtifact(
                "geo",
                listOf(
                    "com/devstree/trackit/geo/math/Bearing.class",
                    "com/devstree/trackit/geo/math/Geodesy.class",
                    "com/devstree/trackit/geo/math/Haversine.class",
                    "com/devstree/trackit/geo/model/FixDecision.class",
                    "com/devstree/trackit/geo/model/GeoPoint.class",
                    "com/devstree/trackit/geo/model/MotionState.class",
                    "com/devstree/trackit/geo/model/TrackFix.class",
                    "com/devstree/trackit/geo/model/TrackPoint.class",
                    "com/devstree/trackit/geo/model/Verdict.class",
                    "com/devstree/trackit/geo/plot/PolylineCodec.class",
                    "com/devstree/trackit/geo/plot/TrackBuilder.class",
                    "com/devstree/trackit/geo/plot/model/SegmentType.class",
                    "com/devstree/trackit/geo/plot/model/Track.class",
                    "com/devstree/trackit/geo/plot/model/TrackOptions.class",
                    "com/devstree/trackit/geo/port/TrackLogger.class",
                ),
                "tr/dev/geo/",
            ),
            ReleaseArtifact(
                "core",
                listOf(
                    "com/devstree/trackit/AccuracyProfile.class",
                    "com/devstree/trackit/LocationProviderType.class",
                    "com/devstree/trackit/RawFix.class",
                    "com/devstree/trackit/TrackIt.class",
                    "com/devstree/trackit/TrackItConfig.class",
                    "com/devstree/trackit/domain/model/ErrorCode.class",
                    "com/devstree/trackit/domain/model/LocationAccuracy.class",
                    "com/devstree/trackit/domain/model/PermissionTier.class",
                    "com/devstree/trackit/domain/model/PointQuery.class",
                    "com/devstree/trackit/domain/model/ProviderState.class",
                    "com/devstree/trackit/domain/model/TrackItEvent.class",
                    "com/devstree/trackit/domain/model/TrackItResult.class",
                    "com/devstree/trackit/domain/model/TrackSession.class",
                    "com/devstree/trackit/motion/DeviceSensors.class",
                    "com/devstree/trackit/permission/PermissionManager.class",
                ),
                "tr/dev/core/",
            ),
            ReleaseArtifact(
                "maps",
                listOf(
                    "com/devstree/trackit/maps/ArrowIcons.class",
                    "com/devstree/trackit/maps/LiveTrackRenderer.class",
                    "com/devstree/trackit/maps/LiveTrackRenderer\$CameraFollowMode.class",
                    "com/devstree/trackit/maps/LiveTrackRenderer\$Options.class",
                    "com/devstree/trackit/maps/TrackRenderer.class",
                    "com/devstree/trackit/maps/TrackRenderer\$RendererOptions.class",
                ),
                null,
            ),
            ReleaseArtifact(
                "snap",
                listOf("com/devstree/trackit/snap/OsrmSnapProvider.class"),
                "tr/dev/snap/",
            ),
            ReleaseArtifact(
                "sync",
                listOf(
                    "com/devstree/trackit/sync/OkHttpSyncTransport.class",
                    "com/devstree/trackit/sync/OkHttpSyncTransport\$Companion.class",
                    "com/devstree/trackit/sync/SyncConfig.class",
                    "com/devstree/trackit/sync/SyncPayload.class",
                    "com/devstree/trackit/sync/SyncPayload\$Companion.class",
                    "com/devstree/trackit/sync/SyncPoint.class",
                    "com/devstree/trackit/sync/SyncPoint\$Companion.class",
                    "com/devstree/trackit/sync/SyncQueue.class",
                    "com/devstree/trackit/sync/SyncQueue\$Result.class",
                    "com/devstree/trackit/sync/SyncRequest.class",
                    "com/devstree/trackit/sync/SyncResponse.class",
                    "com/devstree/trackit/sync/SyncTransport.class",
                    "com/devstree/trackit/sync/TrackItSync.class",
                    "com/devstree/trackit/sync/TrackItSync\$Companion.class",
                ),
                "tr/dev/sync/",
            ),
        )

        val forbiddenLogs = listOf(
            "TrackIt/",
            "Cadence ->",
            "Motion ->",
            "No open session; stopping service",
            "One-shot suppressed after",
            "Activity transitions registered",
            "Auth expired",
            "Upload failed (",
        )

        artifacts.forEach { artifact ->
            val aar = root.resolve("trackit-${artifact.module}/build/outputs/aar/trackit-${artifact.module}-release.aar")
            check(aar.isFile) {
                "Missing release artifact: ${aar.relativeTo(root)}"
            }

            val classesJar = ZipFile(aar).use { zip ->
                val entry = checkNotNull(zip.getEntry("classes.jar")) {
                    "${aar.name} does not contain classes.jar"
                }
                zip.getInputStream(entry).readBytes()
            }

            val classNames = mutableSetOf<String>()
            val classBytes = ArrayList<Byte>()
            ZipInputStream(ByteArrayInputStream(classesJar)).use { jar ->
                while (true) {
                    val entry = jar.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        classNames += entry.name
                        classBytes += jar.readBytes().toList()
                    }
                }
            }

            artifact.requiredApi.forEach { api ->
                check(api in classNames) { "${artifact.module} release renamed or removed supported API: $api" }
            }
            val generatedLeaks = classNames.filter { className ->
                !className.startsWith("tr/dev/") &&
                    (className.endsWith("\$\$serializer.class") ||
                        className.substringAfterLast('/').matches(Regex("[a-z]\\.class")))
            }
            check(generatedLeaks.isEmpty()) {
                "${artifact.module} release left shortened/generated implementation in an API package: " +
                    generatedLeaks
            }
            if (artifact.obfuscatedPrefix != null) {
                check(classNames.any { it.startsWith(artifact.obfuscatedPrefix) }) {
                    "${artifact.module} release contains no shortened implementation class under ${artifact.obfuscatedPrefix}"
                }
            } else {
                val publicPackage = "com/devstree/trackit/${artifact.module}/"
                val unexpected = classNames.filter {
                    it.startsWith(publicPackage) && it !in artifact.requiredApi
                }
                check(unexpected.isEmpty()) {
                    "${artifact.module} release left implementation classes in its public package: $unexpected"
                }
            }

            val bytecodeText = classBytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            forbiddenLogs.forEach { message ->
                check(message !in bytecodeText) {
                    "${artifact.module} release still contains SDK log text: $message"
                }
            }

            ZipFile(aar).use { zip ->
                check(zip.getEntry("mapping.txt") == null) {
                    "${artifact.module} AAR must not publish its private R8 mapping"
                }
            }
        }

        val oldPackages = listOf(
            "com/devstree/trackit/geo/internal/",
            "com/devstree/trackit/internal/",
            "com/devstree/trackit/maps/internal/",
            "com/devstree/trackit/snap/internal/",
            "com/devstree/trackit/sync/internal/",
        )
        artifacts.forEach { artifact ->
            val aar = root.resolve("trackit-${artifact.module}/build/outputs/aar/trackit-${artifact.module}-release.aar")
            val classesJar = ZipFile(aar).use { zip ->
                zip.getInputStream(zip.getEntry("classes.jar")).readBytes()
            }
            val listing = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(classesJar)).use { jar ->
                while (true) listing += (jar.nextEntry ?: break).name
            }
            oldPackages.forEach { oldPackage ->
                check(listing.none { it.startsWith(oldPackage) }) {
                    "${artifact.module} release leaked the old implementation package $oldPackage"
                }
            }
        }

        val sourceArchives = Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                val value = path.toString()
                path.fileName.toString().endsWith("-sources.jar") &&
                    "/trackit-" in value && "/build/" in value
            }.toList()
        }
        check(sourceArchives.isEmpty()) {
            "Source archives must not be published beside obfuscated binaries: $sourceArchives"
        }

        val documentationArchives = Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                path.fileName.toString().endsWith("-javadoc.jar") &&
                    "/trackit-" in path.toString() && "/build/" in path.toString()
            }.toList()
        }
        documentationArchives.forEach { archive ->
            ZipFile(archive.toFile()).use { zip ->
                val sourceEntries = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".kt") || it.endsWith(".java") }
                    .toList()
                check(sourceEntries.isEmpty()) {
                    "Javadoc archive must contain rendered API documentation only: " +
                        "$archive contains $sourceEntries"
                }
            }
        }
    }
}

/** Copies the exact R8 release outputs into local release storage keyed by version. */
abstract class ArchiveReleaseMappingsTask : DefaultTask() {
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val commitSha: Property<String>

    @TaskAction
    fun archive() {
        val root = repositoryRoot.get().asFile
        val archiveRoot = root.resolve("build/release-mappings/${version.get()}")

        releaseModules.forEach { module ->
            val aar = root.resolve("trackit-$module/build/outputs/aar/trackit-$module-release.aar")
            check(aar.isFile) {
                "Missing release artifact for mapping archive: ${aar.relativeTo(root)}"
            }

            val mappingDir = root.resolve("trackit-$module/build/outputs/mapping/release")
            val moduleArchive = archiveRoot.resolve(module)
            moduleArchive.mkdirs()

            val mapping = mappingDir.resolve("mapping.txt")
            check(mapping.isFile) {
                "Missing release mapping for mapping archive: ${mapping.relativeTo(root)}"
            }

            listOf("mapping.txt", "seeds.txt", "usage.txt").forEach { name ->
                val source = mappingDir.resolve(name)
                if (source.isFile) {
                    Files.copy(source.toPath(), moduleArchive.resolve(name).toPath(), REPLACE_EXISTING)
                }
            }

            moduleArchive.resolve("manifest.txt").writeText(
                buildString {
                    appendLine("module=$module")
                    appendLine("version=${version.get()}")
                    appendLine("commit=${commitSha.get()}")
                    appendLine("aar=${aar.relativeTo(root)}")
                    appendLine("aarSha256=${aar.sha256()}")
                    appendLine("mappingSha256=${mapping.sha256()}")
                },
            )
        }
    }
}
