import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a binary-compatibility-validator API dump lists a class from a package with an `internal` segment.
 *
 * The dump is the compiler's own view of the public API. Run the task next to `apiCheck`: `apiCheck` fails if the dump
 * is out of date, and this task fails if an up-to-date dump lists such a class.
 */
@CacheableTask
abstract class CheckInternalPackagesNotPublic : DefaultTask() {
    /**
     * The module's checked-in `*.api` dumps.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apiDumps: ConfigurableFileCollection

    /**
     * Marker written on success so that the task can be up to date and cacheable.
     */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val offenders = apiDumps.files.sortedBy { it.path }.flatMap { dump ->
            findPublicDeclarationsInInternalPackages(dump.readLines()).map {
                "${dump.parentFile.parentFile.name}/api/${dump.name}:${it.lineNumber}: ${it.line}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Public declarations found in `internal` packages:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\nInternal packages must contain only non-public declarations; make it internal or move it out."
            )
        }

        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

/**
 * A class entry of an API dump that lives in a package with an `internal` segment.
 *
 * @property lineNumber one-based line number in the dump
 * @property line the entry's declaration line
 */
data class InternalPackageViolation(val lineNumber: Int, val line: String)

/**
 * Finds the class entries of an API dump whose package has an `internal` segment.
 *
 * A class entry starts at column 0 (`public final class a/b/C : Super {`); members are indented and belong to the
 * entry above, so only the entry's own name decides. Types in member signatures and supertypes are ignored on
 * purpose, because they legitimately mention internal packages of other libraries, such as `kotlin/jvm/internal`.
 */
fun findPublicDeclarationsInInternalPackages(lines: List<String>): List<InternalPackageViolation> =
    lines.mapIndexedNotNull { index, line ->
        if (line.isClassEntryInInternalPackage()) InternalPackageViolation(index + 1, line) else null
    }

private fun String.isClassEntryInInternalPackage(): Boolean {
    if (isBlank() || first().isWhitespace() || first() == '}') return false
    val name = substringBefore(" : ").substringBefore(" {").substringAfterLast(' ')
    return "/$name".contains("/internal/")
}
