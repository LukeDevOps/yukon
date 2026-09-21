package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.none
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A provider whose constructor throws, so `ServiceLoader` fails to instantiate it. */
class ThrowingModule : EndpointModule {
    init {
        throw IllegalStateException("cannot construct")
    }

    override val name: String = "throwing"

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = none()

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> = builder
}

class EndpointModulesTest {
    @Test
    fun `a provider that cannot be constructed or found does not hide the modules listed beside it`() {
        val root = Files.createTempDirectory("yukon-modules")
        val services = root.resolve("META-INF/services/${EndpointModule::class.java.name}")
        Files.createDirectories(services.parent)
        Files.writeString(services, "${ThrowingModule::class.java.name}\n${FakeRouterModule::class.java.name}\ncom.example.Missing\n")
        val loader = URLClassLoader(arrayOf(root.toUri().toURL()), EndpointModulesTest::class.java.classLoader)

        val names = EndpointModules.discover(loader).map { it.name }

        assertTrue("fake-router" in names, "the module after the unconstructible one is still found: $names")
        assertFalse("throwing" in names)
    }
}
