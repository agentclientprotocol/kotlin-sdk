import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheckInternalPackagesNotPublicTest {
    private fun violations(vararg lines: String): List<Int> =
        findPublicDeclarationsInInternalPackages(lines.toList()).map { it.lineNumber }

    @Test
    fun `class entry in an internal package is flagged`() {
        assertEquals(listOf(1), violations("public final class com/example/client/internal/Holder : com/example/Base {"))
    }

    @Test
    fun `nested class in an internal package is flagged`() {
        assertEquals(listOf(1), violations("public final class com/example/internal/Foo\$Bar {"))
    }

    @Test
    fun `file facade in an internal package is flagged`() {
        assertEquals(listOf(1), violations("public final class com/example/internal/FooKt {"))
    }

    @Test
    fun `interface and abstract entries in an internal package are flagged`() {
        assertEquals(
            listOf(1, 3),
            violations(
                "public abstract interface class a/internal/Api {",
                "}",
                "public abstract class a/internal/Base {",
            ),
        )
    }

    @Test
    fun `entry without supertypes or body suffix on the name is still read by its name`() {
        assertEquals(listOf(1), violations("public final class internal/Top {"))
    }

    @Test
    fun `internal packages of other libraries in supertypes and members are not flagged`() {
        assertEquals(
            emptyList(),
            violations(
                "public final synthetic class com/example/Foo\$\$serializer : kotlinx/serialization/internal/GeneratedSerializer {",
                "\tpublic synthetic fun <init> (ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
                "\tpublic final fun serializer ()Lkotlinx/serialization/internal/GeneratedSerializer;",
                "}",
                "public final class com/example/Bar : kotlin/jvm/internal/Lambda {",
                "}",
            ),
        )
    }

    @Test
    fun `package segments that merely contain the word internal are not flagged`() {
        assertEquals(
            emptyList(),
            violations(
                "public final class com/example/internalfoo/A {",
                "public final class com/example/myinternal/B {",
                "public final class com/example/internals/C {",
                "public final class com/example/api/InternalThing {",
            ),
        )
    }

    @Test
    fun `blank lines, closing braces and member lines are ignored`() {
        assertEquals(
            emptyList(),
            violations(
                "",
                "   ",
                "}",
                "\tpublic final fun com/example/internal/Fake ()V",
                "  public final class com/example/internal/Indented {",
            ),
        )
    }

    @Test
    fun `line numbers are one-based and point at the entry`() {
        assertEquals(
            listOf(3, 7),
            violations(
                "public final class com/example/Ok {",
                "}",
                "public final class com/example/internal/A {",
                "\tpublic fun x ()V",
                "}",
                "",
                "public final class com/example/impl/internal/B {",
            ),
        )
    }

    @Test
    fun `task passes on a clean dump and writes the marker`(@TempDir dir: File) {
        val task = registerTask(dir, "public final class com/example/Ok {\n}\n")

        task.check()

        assertTrue(File(dir, "build/ok").exists())
    }

    @Test
    fun `task fails on a bad dump and lists file and line`(@TempDir dir: File) {
        val task = registerTask(
            dir,
            "public final class com/example/Ok {\n}\n\npublic final class com/example/internal/Bad {\n}\n",
        )

        val failure = assertThrows<GradleException> { task.check() }

        assertTrue("api/acp.api:4: public final class com/example/internal/Bad {" in failure.message!!, failure.message)
        assertTrue("make it internal or move it out" in failure.message!!, failure.message)
        assertTrue(!File(dir, "build/ok").exists())
    }

    private fun registerTask(dir: File, dump: String): CheckInternalPackagesNotPublic {
        val project = ProjectBuilder.builder().withProjectDir(File(dir, "module").apply { mkdirs() }).build()
        val api = File(project.projectDir, "api").apply { mkdirs() }
        File(api, "acp.api").writeText(dump)

        return project.tasks.register("checkInternalPackagesNotPublic", CheckInternalPackagesNotPublic::class.java) {
            apiDumps.from(api.listFiles { f -> f.name.endsWith(".api") }!!.toList())
            marker.set(File(dir, "build/ok"))
        }.get()
    }
}
