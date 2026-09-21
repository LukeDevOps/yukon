package io.github.lukedevops.yukon.dependencies

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeSourceLocationTest {
    @Test
    fun `a file URL is a location on disk`() {
        assertEquals(
            CodeSourceLocation.OnDisk(Path.of("/libs/a b/one-1.0.jar")),
            CodeSourceLocation.parse("file:/libs/a%20b/one-1.0.jar"),
        )
    }

    @Test
    fun `a Spring Boot 3_2 nested URL splits at the last slash-bang and is percent-decoded`() {
        assertEquals(
            CodeSourceLocation.InJar(Path.of("/srv/my app/demo.jar"), "BOOT-INF/lib/jackson-core-2.17.0.jar"),
            CodeSourceLocation.parse("jar:nested:/srv/my%20app/demo.jar/!BOOT-INF/lib/jackson-core-2.17.0.jar!/"),
        )
    }

    @Test
    fun `a Spring Boot 2 nested URL names the outer jar as a file URL and the entry after the first separator`() {
        assertEquals(
            CodeSourceLocation.InJar(Path.of("/srv/demo.jar"), "BOOT-INF/lib/jackson-core-2.17.0.jar"),
            CodeSourceLocation.parse("jar:file:/srv/demo.jar!/BOOT-INF/lib/jackson-core-2.17.0.jar!/"),
        )
    }

    @Test
    fun `a jar URL naming no entry is the jar itself`() {
        assertEquals(CodeSourceLocation.OnDisk(Path.of("/libs/one.jar")), CodeSourceLocation.parse("jar:file:/libs/one.jar!/"))
    }

    @Test
    fun `a fat jar's classes directory is a directory in both Boot formats`() {
        val boot3 = CodeSourceLocation.parse("jar:nested:/srv/demo.jar/!BOOT-INF/classes/!/") as CodeSourceLocation.InJar
        val boot2 = CodeSourceLocation.parse("jar:file:/srv/demo.jar!/BOOT-INF/classes!/") as CodeSourceLocation.InJar
        val war = CodeSourceLocation.parse("jar:file:/srv/demo.war!/WEB-INF/classes!/") as CodeSourceLocation.InJar

        assertTrue(boot3.isDirectory)
        assertTrue(boot2.isDirectory)
        assertTrue(war.isDirectory)
        assertFalse(CodeSourceLocation.InJar(Path.of("/srv/demo.jar"), "BOOT-INF/lib/a.jar").isDirectory)
    }

    @Test
    fun `other schemes are unsupported`() {
        assertEquals(CodeSourceLocation.Unsupported, CodeSourceLocation.parse("jrt:/java.sql"))
        assertEquals(CodeSourceLocation.Unsupported, CodeSourceLocation.parse("jar:http://example.com/a.jar!/"))
        assertEquals(CodeSourceLocation.Unsupported, CodeSourceLocation.parse("http://example.com/a.jar"))
    }

    @Test
    fun `a Windows nested path loses the slash before its drive letter, as Boot's NestedLocation does`() {
        assertEquals("C:/srv/demo.jar", CodeSourceLocation.windowsNestedPath("/C:/srv/demo.jar"))
        assertEquals("C:/srv/demo.jar", CodeSourceLocation.windowsNestedPath("///C:/srv/demo.jar"))
        assertEquals("C:/srv/demo.jar", CodeSourceLocation.windowsNestedPath("C:/srv/demo.jar"))
        assertEquals("//server/share/demo.jar", CodeSourceLocation.windowsNestedPath("//server/share/demo.jar"))
    }

    @Test
    fun `a literal plus in a nested path is kept, since Boot decodes percent escapes only`() {
        assertEquals(
            CodeSourceLocation.InJar(Path.of("/srv/a+b/demo.jar"), "BOOT-INF/lib/x.jar"),
            CodeSourceLocation.parse("jar:nested:/srv/a+b/demo.jar/!BOOT-INF/lib/x.jar!/"),
        )
    }
}
