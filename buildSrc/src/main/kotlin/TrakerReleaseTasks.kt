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
) {
    val projectName: String = "fieldtrack-$module"

    fun releaseAar(root: File): File =
        root.resolve("$projectName/build/outputs/aar/$projectName-release.aar")
}

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
                    "com/devstree/traker/geo/math/Bearing.class",
                    "com/devstree/traker/geo/math/Geodesy.class",
                    "com/devstree/traker/geo/math/Haversine.class",
                    "com/devstree/traker/geo/model/FixDecision.class",
                    "com/devstree/traker/geo/model/GeoPoint.class",
                    "com/devstree/traker/geo/model/MotionState.class",
                    "com/devstree/traker/geo/model/TrackFix.class",
                    "com/devstree/traker/geo/model/TrackPoint.class",
                    "com/devstree/traker/geo/model/Verdict.class",
                    "com/devstree/traker/geo/plot/PolylineCodec.class",
                    "com/devstree/traker/geo/plot/TrackBuilder.class",
                    "com/devstree/traker/geo/plot/model/SegmentType.class",
                    "com/devstree/traker/geo/plot/model/Track.class",
                    "com/devstree/traker/geo/plot/model/TrackOptions.class",
                    "com/devstree/traker/geo/port/TrackLogger.class",
                ),
                "tr/dev/geo/",
            ),
            ReleaseArtifact(
                "core",
                listOf(
                    "com/devstree/traker/AccuracyProfile.class",
                    "com/devstree/traker/LocationProviderType.class",
                    "com/devstree/traker/RawFix.class",
                    "com/devstree/traker/Traker.class",
                    "com/devstree/traker/TrakerConfig.class",
                    "com/devstree/traker/domain/model/ErrorCode.class",
                    "com/devstree/traker/domain/model/LocationAccuracy.class",
                    "com/devstree/traker/domain/model/PermissionTier.class",
                    "com/devstree/traker/domain/model/PointQuery.class",
                    "com/devstree/traker/domain/model/ProviderState.class",
                    "com/devstree/traker/domain/model/TrakerEvent.class",
                    "com/devstree/traker/domain/model/TrakerResult.class",
                    "com/devstree/traker/domain/model/TrackSession.class",
                    "com/devstree/traker/motion/DeviceSensors.class",
                    "com/devstree/traker/permission/PermissionManager.class",
                ),
                "tr/dev/core/",
            ),
            ReleaseArtifact(
                "maps",
                listOf(
                    "com/devstree/traker/maps/ArrowIcons.class",
                    "com/devstree/traker/maps/LiveTrackRenderer.class",
                    "com/devstree/traker/maps/LiveTrackRenderer\$CameraFollowMode.class",
                    "com/devstree/traker/maps/LiveTrackRenderer\$Options.class",
                    "com/devstree/traker/maps/TrackRenderer.class",
                    "com/devstree/traker/maps/TrackRenderer\$RendererOptions.class",
                ),
                null,
            ),
            ReleaseArtifact(
                "snap",
                listOf("com/devstree/traker/snap/OsrmSnapProvider.class"),
                "tr/dev/snap/",
            ),
            ReleaseArtifact(
                "sync",
                listOf(
                    "com/devstree/traker/sync/OkHttpSyncTransport.class",
                    "com/devstree/traker/sync/OkHttpSyncTransport\$Companion.class",
                    "com/devstree/traker/sync/SyncConfig.class",
                    "com/devstree/traker/sync/SyncPayload.class",
                    "com/devstree/traker/sync/SyncPayload\$Companion.class",
                    "com/devstree/traker/sync/SyncPoint.class",
                    "com/devstree/traker/sync/SyncPoint\$Companion.class",
                    "com/devstree/traker/sync/SyncQueue.class",
                    "com/devstree/traker/sync/SyncQueue\$Result.class",
                    "com/devstree/traker/sync/SyncRequest.class",
                    "com/devstree/traker/sync/SyncResponse.class",
                    "com/devstree/traker/sync/SyncTransport.class",
                    "com/devstree/traker/sync/TrakerSync.class",
                    "com/devstree/traker/sync/TrakerSync\$Companion.class",
                ),
                "tr/dev/sync/",
            ),
        )

        val forbiddenLogs = listOf(
            "Traker/",
            "Cadence ->",
            "Motion ->",
            "No open session; stopping service",
            "One-shot suppressed after",
            "Activity transitions registered",
            "Auth expired",
            "Upload failed (",
        )

        artifacts.forEach { artifact ->
            val aar = artifact.releaseAar(root)
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
                val publicPackage = "com/devstree/traker/${artifact.module}/"
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
            "com/devstree/traker/geo/internal/",
            "com/devstree/traker/internal/",
            "com/devstree/traker/maps/internal/",
            "com/devstree/traker/snap/internal/",
            "com/devstree/traker/sync/internal/",
        )
        artifacts.forEach { artifact ->
            val aar = artifact.releaseAar(root)
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
                val value = path.toString().replace(File.separatorChar, '/')
                path.fileName.toString().endsWith("-sources.jar") &&
                    "/fieldtrack-" in value && "/build/" in value
            }.toList()
        }
        check(sourceArchives.isEmpty()) {
            "Source archives must not be published beside obfuscated binaries: $sourceArchives"
        }

        val documentationArchives = Files.walk(root.toPath()).use { paths ->
            paths.filter { path ->
                val value = path.toString().replace(File.separatorChar, '/')
                path.fileName.toString().endsWith("-javadoc.jar") &&
                    "/fieldtrack-" in value && "/build/" in value
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
            val projectName = "fieldtrack-$module"
            val aar = root.resolve("$projectName/build/outputs/aar/$projectName-release.aar")
            check(aar.isFile) {
                "Missing release artifact for mapping archive: ${aar.relativeTo(root)}"
            }

            val mappingDir = root.resolve("$projectName/build/outputs/mapping/release")
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
