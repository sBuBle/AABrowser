import groovy.json.JsonOutput
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes what the app's self-updater reads from a GitHub release, next to the release APK:
 * one patch per earlier release APK given, and update.json describing the APK and the patches.
 * Upload all of them as release assets.
 */
abstract class MakeUpdatePatchTask : DefaultTask() {
    /** Release APKs of earlier versions that users may have installed (usually just the last one). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val previousApks: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val newApk: RegularFileProperty

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val versionName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val newFile = newApk.get().asFile
        val newBytes = newFile.readBytes()
        val outDir = outputDir.get().asFile.apply { mkdirs() }
        // Patches from an earlier run would otherwise sit next to the new files and could be
        // uploaded by mistake.
        outDir.listFiles()?.filter { it.extension == "patch" || it.name == "update.json" }?.forEach { it.delete() }

        val patches = previousApks.files.sortedBy { it.name }.map { oldFile ->
            val oldBytes = oldFile.readBytes()
            val patchFile = File(outDir, "${oldFile.nameWithoutExtension}-to-${versionName.get()}.patch")
            // Suffix sorting recurses deeply on long runs of equal bytes (zip alignment padding).
            var failure: Throwable? = null
            val worker = Thread(null, {
                try {
                    patchFile.outputStream().buffered().use { ApkPatchWriter.write(oldBytes, newBytes, it) }
                } catch (t: Throwable) {
                    failure = t
                }
            }, "apk-patch", 1L shl 30)
            worker.start()
            worker.join()
            failure?.let { throw GradleException("Could not write ${patchFile.name}", it) }

            val rebuilt = ApkPatchWriter.apply(oldBytes, patchFile.readBytes())
            if (!rebuilt.contentEquals(newBytes)) {
                throw GradleException("${patchFile.name} does not rebuild ${newFile.name}")
            }
            logger.lifecycle("${patchFile.name}: ${patchFile.length() / 1024} KiB (full APK ${newFile.length() / 1024} KiB)")
            mapOf(
                "name" to patchFile.name,
                "size" to patchFile.length(),
                "sha256" to sha256(patchFile.readBytes()),
                "fromSha256" to sha256(oldBytes)
            )
        }

        val manifest = mapOf(
            "versionCode" to versionCode.get(),
            "versionName" to versionName.get(),
            "apk" to mapOf(
                "name" to newFile.name,
                "size" to newFile.length(),
                "sha256" to sha256(newBytes)
            ),
            "patches" to patches
        )
        File(outDir, "update.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        logger.lifecycle("Wrote update.json and ${patches.size} patch(es) to $outDir")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
