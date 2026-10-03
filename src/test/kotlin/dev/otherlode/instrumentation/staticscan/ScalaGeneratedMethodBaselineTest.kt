package dev.otherlode.instrumentation.staticscan

import dev.otherlode.export.DeclaredMethod
import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.branch.ScalaCaseClassFixtures
import dev.otherlode.instrumentation.branch.ScalaFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that a static baseline scan of each Scala fixture module declares the same generated-method marks
 * the transform-time path gives, the companion rule's partner read through the scan's own locator
 * included, and a default getter the mark of the method it fills a default for.
 */
class ScalaGeneratedMethodBaselineTest {
    private fun `a scan marks what the transform marks`(module: String) {
        val declaredClasses =
            StaticBaselineScanner(listOf("com.example.scalatarget"))
                .scan(listOf(ScalaFixtures.outputDir(module)))
                .declaredClasses

        fun method(
            simpleName: String,
            name: String,
            descriptor: String,
        ): DeclaredMethod =
            declaredClasses
                .single { it.className == "com.example.scalatarget.$simpleName" }
                .methods
                .single { it.methodName == name && it.methodDescriptor == descriptor }

        val driverMethods = declaredClasses.single { it.className == "com.example.scalatarget.Driver" }.methods
        assertTrue(driverMethods.isNotEmpty())
        assertTrue(driverMethods.all { it.generatedBy == GeneratedBy.STATIC_FORWARDER }, "every Driver method is a static forwarder")
        assertEquals(GeneratedBy.NONE, method("Driver\$", "callSimpleAllOmitted", "()I").generatedBy)
        assertEquals(GeneratedBy.STATIC_FORWARDER, method("Cc", "apply", "(II)Lcom/example/scalatarget/Cc;").generatedBy)

        assertEquals(GeneratedBy.CASE_CLASS, method("Cc", "canEqual", "(Ljava/lang/Object;)Z").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Cc", "copy", "(II)Lcom/example/scalatarget/Cc;").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Cc", "a", "()I").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Written", "toString", "()Ljava/lang/String;").generatedBy)

        assertEquals(GeneratedBy.CASE_CLASS, method("Cc\$", "apply", "(II)Lcom/example/scalatarget/Cc;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Cc\$", "toString", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Written\$", "apply", "(Ljava/lang/String;)Lcom/example/scalatarget/Written;").generatedBy)
        assertEquals(GeneratedBy.SCALA_OBJECT, method("Cc\$", "writeReplace", "()Ljava/lang/Object;").generatedBy)

        assertEquals(GeneratedBy.NONE, method("NotCase", "toString", "()Ljava/lang/String;").generatedBy)

        // A default getter the manifest reports as an omission probe carries its target's mark
        // there, so the baseline gives the getter's own entry the same one.
        assertEquals(GeneratedBy.CASE_CLASS, method("Cc", "copy\$default\$1", "()I").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Cc\$", "\$lessinit\$greater\$default\$1", "()I").generatedBy)
    }

    private fun `a scan marks multi-line, sealed-trait, empty and object case classes as the transform does`(module: String) {
        val declaredClasses =
            StaticBaselineScanner(listOf("com.example.scalatarget"))
                .scan(listOf(ScalaFixtures.outputDir(module)))
                .declaredClasses

        fun methodsOf(simpleName: String): List<DeclaredMethod> =
            declaredClasses.single { it.className == "com.example.scalatarget.$simpleName" }.methods

        fun method(
            simpleName: String,
            name: String,
            descriptor: String,
        ): DeclaredMethod = methodsOf(simpleName).single { it.methodName == name && it.methodDescriptor == descriptor }

        val plumbingNames = setOf("canEqual", "copy", "equals", "hashCode", "toString", "productArity", "productElement", "productPrefix")
        // Multi's toString is the adopter's own, written in the body.
        val handWritten = mapOf("Multi" to setOf("toString"))
        for (simpleName in listOf("Multi", "Round", "Box", "Empty", "Solo\$")) {
            val except = handWritten[simpleName].orEmpty()
            val plumbing =
                methodsOf(simpleName).filter {
                    !it.static &&
                        (it.methodName in plumbingNames || Regex("_\\d+").matches(it.methodName)) &&
                        it.methodName !in except
                }
            assertTrue(plumbing.any { it.methodName == "canEqual" }, simpleName)
            for (declared in plumbing) {
                assertEquals(GeneratedBy.CASE_CLASS, declared.generatedBy, "$simpleName.${declared.methodName}${declared.methodDescriptor}")
            }
        }
        val multi = "Lcom/example/scalatarget/Multi;"
        assertEquals(GeneratedBy.NONE, method("Multi", "a", "()I").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Multi", "toString", "()Ljava/lang/String;").generatedBy)
        if (module == "scala3") assertEquals(GeneratedBy.CASE_CLASS, method("Multi", "_2", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Multi\$", "apply", "(ILjava/lang/String;)$multi").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Multi\$", "toString", "()Ljava/lang/String;").generatedBy)

        assertEquals(GeneratedBy.NONE, method("Round", "r", "()D").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Round\$", "apply", "(D)Lcom/example/scalatarget/Round;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Box\$", "toString", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, method("Empty\$", "unapply", "(Lcom/example/scalatarget/Empty;)Z").generatedBy)
        assertEquals(GeneratedBy.NONE, method("Solo\$", "<init>", "()V").generatedBy)
    }

    @Test
    fun `scala 3 - a scan marks multi-line, sealed-trait, empty and object case classes as the transform does`() =
        `a scan marks multi-line, sealed-trait, empty and object case classes as the transform does`("scala3")

    @Test
    fun `scala 2 - a scan marks multi-line, sealed-trait, empty and object case classes as the transform does`() =
        `a scan marks multi-line, sealed-trait, empty and object case classes as the transform does`("scala2")

    private fun `a scan marks every fixture case class's plumbing and nothing the adopter wrote`(module: String) {
        val declaredClasses =
            StaticBaselineScanner(listOf("com.example.scalatarget"))
                .scan(listOf(ScalaFixtures.outputDir(module)))
                .declaredClasses

        for (simpleName in ScalaCaseClassFixtures.allClasses(module)) {
            val methods =
                declaredClasses.single { it.className == "com.example.scalatarget.$simpleName" }.methods.filter { !it.static }
            val checked =
                methods.mapNotNull { declared ->
                    ScalaCaseClassFixtures.expectedMark(module, simpleName, declared.methodName, declared.methodDescriptor)?.let {
                        declared to
                            it
                    }
                }
            assertTrue(checked.any { it.second == GeneratedBy.CASE_CLASS }, "$module $simpleName")
            for ((declared, expected) in checked) {
                assertEquals(expected, declared.generatedBy, "$module $simpleName.${declared.methodName}${declared.methodDescriptor}")
            }
        }
    }

    @Test
    fun `scala 3 - a scan marks every fixture case class's plumbing and nothing the adopter wrote`() =
        `a scan marks every fixture case class's plumbing and nothing the adopter wrote`("scala3")

    @Test
    fun `scala 2 - a scan marks every fixture case class's plumbing and nothing the adopter wrote`() =
        `a scan marks every fixture case class's plumbing and nothing the adopter wrote`("scala2")

    @Test
    fun `scala 3 - a scan marks what the transform marks`() = `a scan marks what the transform marks`("scala3")

    @Test
    fun `scala 2 - a scan marks what the transform marks`() = `a scan marks what the transform marks`("scala2")
}
