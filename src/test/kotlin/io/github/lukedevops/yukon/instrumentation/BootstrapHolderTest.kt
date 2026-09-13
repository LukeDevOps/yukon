package io.github.lukedevops.yukon.instrumentation

import net.bytebuddy.agent.ByteBuddyAgent
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapHolderTest {
    @Test
    fun `installs the holder onto the bootstrap loader from the embedded jar, and is idempotent`() {
        val instrumentation = ByteBuddyAgent.install()

        BootstrapHolder.install(instrumentation)
        BootstrapHolder.install(instrumentation)

        assertTrue(BootstrapHolder.isInstalled())
        val holder = Class.forName(BootstrapHolder.HOLDER_CLASS_NAME, false, null)
        assertNull(holder.classLoader, "the holder must be defined by the bootstrap loader, not the app loader")
    }

    @Test
    fun `a missing embedded jar is reported as an install failure rather than a later NoClassDefFoundError`() {
        // install() returns early once another test has appended the holder to this JVM, so the
        // resource lookup is driven directly rather than through install().
        val exception =
            assertFailsWith<BootstrapInstallException> {
                BootstrapHolder.readEmbeddedJar("META-INF/yukon/does-not-exist.jar")
            }
        assertTrue("does-not-exist.jar" in exception.message.orEmpty())
    }
}
