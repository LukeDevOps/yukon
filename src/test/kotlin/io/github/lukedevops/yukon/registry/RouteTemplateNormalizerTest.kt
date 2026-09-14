package io.github.lukedevops.yukon.registry

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTemplateNormalizerTest {
    @Test
    fun `a plain path gets a leading slash`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("orders"))
    }

    @Test
    fun `an empty input normalises to the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize(""))
    }

    @Test
    fun `a blank input normalises to the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize("   "))
    }

    @Test
    fun `the root path stays the root template`() {
        assertEquals("/", RouteTemplateNormalizer.normalize("/"))
    }

    @Test
    fun `a trailing slash is dropped`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders/"))
    }

    @Test
    fun `runs of slashes collapse to one`() {
        assertEquals("/orders/1", RouteTemplateNormalizer.normalize("//orders//1//"))
    }

    @Test
    fun `a Spring path parameter keeps its name`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id}"))
    }

    @Test
    fun `a Spring tail wildcard collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/**"))
    }

    @Test
    fun `a Spring 6 captures-the-rest parameter collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/{*path}"))
    }

    @Test
    fun `a single star segment stays a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/*"))
    }

    @Test
    fun `a JAX-RS regex constraint written with a space is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("""/orders/{id: \d+}"""))
    }

    @Test
    fun `a JAX-RS regex constraint written as a character class is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id:[0-9]+}"))
    }

    @Test
    fun `a JAX-RS regex constraint written as any-character is dropped`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{id:.+}"))
    }

    @Test
    fun `whitespace around a path parameter name is trimmed`() {
        assertEquals("/orders/{id}", RouteTemplateNormalizer.normalize("/orders/{ id : \\d+ }"))
    }

    @Test
    fun `a Ktor tail wildcard collapses to a single star segment`() {
        assertEquals("/*", RouteTemplateNormalizer.normalize("/{...}"))
    }

    @Test
    fun `a Ktor named tail parameter collapses to a single star segment`() {
        assertEquals("/files/*", RouteTemplateNormalizer.normalize("/files/{path...}"))
    }

    @Test
    fun `a Ktor optional parameter keeps its question mark`() {
        assertEquals("/{id?}", RouteTemplateNormalizer.normalize("/{id?}"))
    }

    @Test
    fun `a star embedded inside a segment is left as written`() {
        assertEquals("/files/*.txt", RouteTemplateNormalizer.normalize("/files/*.txt"))
    }

    @Test
    fun `a context path is prefixed with its own leading slash guaranteed`() {
        assertEquals("/app/orders", RouteTemplateNormalizer.normalize("/orders/", "/app/"))
    }

    @Test
    fun `a null context path is not prefixed`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders", null))
    }

    @Test
    fun `a blank context path is not prefixed`() {
        assertEquals("/orders", RouteTemplateNormalizer.normalize("/orders", "   "))
    }

    @Test
    fun `normalizeVerb uppercases and trims`() {
        assertEquals("GET", RouteTemplateNormalizer.normalizeVerb(" get "))
    }

    @Test
    fun `normalizeVerb treats a null verb as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb(null))
    }

    @Test
    fun `normalizeVerb treats a blank verb as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb("  "))
    }

    @Test
    fun `normalizeVerb treats a literal star as unconstrained`() {
        assertEquals("*", RouteTemplateNormalizer.normalizeVerb("*"))
    }
}
