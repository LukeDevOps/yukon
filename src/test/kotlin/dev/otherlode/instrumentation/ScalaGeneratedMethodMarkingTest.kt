package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.branch.ScalaCaseClassFixtures
import dev.otherlode.instrumentation.branch.ScalaFixtures
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the Scala generated-method marks through the real transform pipeline, on the `:fixtures-scala3` and
 * `:fixtures-scala2` fixtures: static forwarders, case-class and companion plumbing, and an
 * object's `writeReplace`, with the adopter's own members left unmarked.
 */
class ScalaGeneratedMethodMarkingTest {
    private companion object {
        const val PACKAGE = "com.example.scalatarget"
        const val TYPES = "com/example/scalatarget"

        /** The case-class plumbing the agent marks, apart from Scala 3's `_1`, `_2` and on. */
        val CASE_CLASS_METHODS =
            setOf(
                "canEqual",
                "copy",
                "equals",
                "hashCode",
                "toString",
                "productArity",
                "productElement",
                "productElementName",
                "productElementNames",
                "productIterator",
                "productPrefix",
            )
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
    }

    /** Loads and initialises each named fixture class under the agent, and returns every manifest probe. */
    private fun probesAfterLoading(
        module: String,
        vararg simpleNames: String,
    ): List<ProbeLocation> {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)
        for (simpleName in simpleNames) Class.forName("$PACKAGE.$simpleName", true, loader)
        return registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes
    }

    private fun List<ProbeLocation>.methodsOf(simpleName: String): List<ProbeLocation> =
        filter { it.className == "$PACKAGE.$simpleName" && it.kind == ProbeKind.METHOD }

    private fun List<ProbeLocation>.method(
        simpleName: String,
        name: String,
        descriptor: String,
    ): ProbeLocation = methodsOf(simpleName).single { it.methodName == name && it.methodDescriptor == descriptor }

    private fun `every static method on an object's class and a case class is a static forwarder`(module: String) {
        val probes = probesAfterLoading(module, "Driver", "Driver\$", "Cc", "Written", "Obj")

        for (simpleName in listOf("Driver", "Cc", "Written", "Obj")) {
            val statics = probes.methodsOf(simpleName).filter { it.static && it.methodName != "<clinit>" }
            assertTrue(statics.isNotEmpty(), "$simpleName has static forwarders")
            for (probe in statics) {
                assertEquals(GeneratedBy.STATIC_FORWARDER, probe.generatedBy, "$simpleName.${probe.methodName}${probe.methodDescriptor}")
            }
        }
        val objectMethods = probes.methodsOf("Driver\$").filter { it.methodName.startsWith("call") }
        assertTrue(objectMethods.isNotEmpty())
        for (probe in objectMethods) {
            assertEquals(GeneratedBy.NONE, probe.generatedBy, "the object's own ${probe.methodName} holds the code")
        }
    }

    @Test
    fun `scala 3 - every static method on an object's class and a case class is a static forwarder`() =
        `every static method on an object's class and a case class is a static forwarder`("scala3")

    @Test
    fun `scala 2 - every static method on an object's class and a case class is a static forwarder`() =
        `every static method on an object's class and a case class is a static forwarder`("scala2")

    private fun `a case class's plumbing is marked and its field accessors are not`(module: String) {
        val probes = probesAfterLoading(module, "Cc", "Written")

        for (simpleName in listOf("Cc", "Written")) {
            val instanceMethods = probes.methodsOf(simpleName).filter { !it.static }
            val plumbing =
                instanceMethods.filter {
                    it.methodName in CASE_CLASS_METHODS || Regex("_\\d+").matches(it.methodName)
                }
            val expectedMarked = plumbing.filterNot { simpleName == "Written" && it.methodName == "toString" }
            assertTrue(expectedMarked.map { it.methodName }.containsAll(listOf("canEqual", "copy", "equals", "hashCode")))
            for (probe in expectedMarked) {
                assertEquals(GeneratedBy.CASE_CLASS, probe.generatedBy, "$simpleName.${probe.methodName}${probe.methodDescriptor}")
            }
            for (accessor in listOf("a", "b")) {
                assertEquals(GeneratedBy.NONE, instanceMethods.single { it.methodName == accessor }.generatedBy, "$simpleName.$accessor")
            }
            for (constructor in instanceMethods.filter { it.methodName == "<init>" }) {
                assertEquals(GeneratedBy.NONE, constructor.generatedBy, "$simpleName.<init>${constructor.methodDescriptor}")
            }
        }
        if (module == "scala3") {
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("Cc", "_1", "()I").generatedBy)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("Cc", "_2", "()I").generatedBy)
        }
        assertEquals(
            GeneratedBy.NONE,
            probes.method("Written", "toString", "()Ljava/lang/String;").generatedBy,
            "an override the adopter wrote is ordinary code",
        )
    }

    @Test
    fun `scala 3 - a case class's plumbing is marked and its field accessors are not`() =
        `a case class's plumbing is marked and its field accessors are not`("scala3")

    @Test
    fun `scala 2 - a case class's plumbing is marked and its field accessors are not`() =
        `a case class's plumbing is marked and its field accessors are not`("scala2")

    private fun `a companion's plumbing is marked and its hand-written apply is not`(module: String) {
        val probes = probesAfterLoading(module, "Cc", "Cc\$", "Written", "Written\$")
        val unapplyReturn = if (module == "scala3") "Lcom/example/scalatarget/Cc;" else "Lscala/Option;"

        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Cc\$", "apply", "(II)Lcom/example/scalatarget/Cc;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Cc\$", "unapply", "(Lcom/example/scalatarget/Cc;)$unapplyReturn").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Cc\$", "toString", "()Ljava/lang/String;").generatedBy)
        if (module == "scala3") {
            assertEquals(
                GeneratedBy.CASE_CLASS,
                probes.method("Cc\$", "fromProduct", "(Lscala/Product;)Lcom/example/scalatarget/Cc;").generatedBy,
            )
            assertEquals(
                GeneratedBy.CASE_CLASS,
                probes.method("Written\$", "fromProduct", "(Lscala/Product;)Lcom/example/scalatarget/Written;").generatedBy,
            )
        }
        assertEquals(
            GeneratedBy.CASE_CLASS,
            probes.method("Written\$", "apply", "(ILjava/lang/String;)Lcom/example/scalatarget/Written;").generatedBy,
        )
        assertEquals(
            GeneratedBy.NONE,
            probes.method("Written\$", "apply", "(Ljava/lang/String;)Lcom/example/scalatarget/Written;").generatedBy,
            "a hand-written apply builds its partner from other values than its own parameters",
        )
        assertEquals(GeneratedBy.NONE, probes.method("Cc\$", "<init>", "()V").generatedBy)
    }

    @Test
    fun `scala 3 - a companion's plumbing is marked and its hand-written apply is not`() =
        `a companion's plumbing is marked and its hand-written apply is not`("scala3")

    @Test
    fun `scala 2 - a companion's plumbing is marked and its hand-written apply is not`() =
        `a companion's plumbing is marked and its hand-written apply is not`("scala2")

    private fun `an object's writeReplace is marked SCALA_OBJECT`(module: String) {
        val probes = probesAfterLoading(module, "Cc\$", "Written\$", "Driver\$")

        val owners = if (module == "scala3") listOf("Cc\$", "Written\$", "Driver\$") else listOf("Cc\$", "Written\$")
        for (owner in owners) {
            assertEquals(GeneratedBy.SCALA_OBJECT, probes.method(owner, "writeReplace", "()Ljava/lang/Object;").generatedBy, owner)
        }
    }

    @Test
    fun `scala 3 - an object's writeReplace is marked SCALA_OBJECT`() = `an object's writeReplace is marked SCALA_OBJECT`("scala3")

    @Test
    fun `scala 2 - an object's writeReplace is marked SCALA_OBJECT`() = `an object's writeReplace is marked SCALA_OBJECT`("scala2")

    private fun `omission probes follow their target's mark`(module: String) {
        val probes = probesAfterLoading(module, "Cc", "Cc\$", "Written")
        val omissions = probes.filter { it.kind == ProbeKind.OPTIONAL_ARGUMENT }

        val copyOmissions = omissions.filter { it.className == "$PACKAGE.Cc" && it.methodName == "copy" }
        assertEquals(2, copyOmissions.size)
        assertTrue(copyOmissions.all { it.generatedBy == GeneratedBy.CASE_CLASS })

        val constructorOmissions = omissions.filter { it.methodName == "<init>" }
        assertTrue(constructorOmissions.isNotEmpty())
        assertTrue(constructorOmissions.all { it.generatedBy == GeneratedBy.NONE }, "a constructor's own defaults stay the adopter's")

        if (module == "scala2") {
            val applyOmissions = omissions.filter { it.className == "$PACKAGE.Cc\$" && it.methodName == "apply" }
            assertEquals(2, applyOmissions.size)
            assertTrue(applyOmissions.all { it.generatedBy == GeneratedBy.CASE_CLASS })
        }
    }

    @Test
    fun `scala 3 - omission probes follow their target's mark`() = `omission probes follow their target's mark`("scala3")

    @Test
    fun `scala 2 - omission probes follow their target's mark`() = `omission probes follow their target's mark`("scala2")

    /** Asserts that every instance method of [simpleName] named in the plumbing set is [GeneratedBy.CASE_CLASS], apart from [except]. */
    private fun List<ProbeLocation>.assertPlumbingMarked(
        simpleName: String,
        except: Set<String> = emptySet(),
    ) {
        val plumbing =
            methodsOf(simpleName).filter {
                !it.static && (it.methodName in CASE_CLASS_METHODS || Regex("_\\d+").matches(it.methodName)) && it.methodName !in except
            }
        assertTrue(plumbing.map { it.methodName }.containsAll(listOf("canEqual", "hashCode", "productArity", "productElement")), simpleName)
        for (probe in plumbing) {
            assertEquals(GeneratedBy.CASE_CLASS, probe.generatedBy, "$simpleName.${probe.methodName}${probe.methodDescriptor}")
        }
    }

    private fun `a case class declared over several lines is marked whatever its layout`(module: String) {
        val probes = probesAfterLoading(module, "Multi", "Multi\$")
        val multi = "Lcom/example/scalatarget/Multi;"
        val unapplyReturn = if (module == "scala3") multi else "Lscala/Option;"

        probes.assertPlumbingMarked("Multi", except = setOf("toString"))
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi", "copy", "(ILjava/lang/String;)$multi").generatedBy)
        if (module == "scala3") {
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi", "_1", "()I").generatedBy)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi", "_2", "()Ljava/lang/String;").generatedBy)
        }
        assertEquals(GeneratedBy.NONE, probes.method("Multi", "a", "()I").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Multi", "b", "()Ljava/lang/String;").generatedBy)
        assertEquals(
            GeneratedBy.NONE,
            probes.method("Multi", "toString", "()Ljava/lang/String;").generatedBy,
            "an override the adopter wrote in the body is ordinary code",
        )

        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi\$", "apply", "(ILjava/lang/String;)$multi").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi\$", "unapply", "($multi)$unapplyReturn").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Multi\$", "toString", "()Ljava/lang/String;").generatedBy)
    }

    @Test
    fun `scala 3 - a case class declared over several lines is marked whatever its layout`() =
        `a case class declared over several lines is marked whatever its layout`("scala3")

    @Test
    fun `scala 2 - a case class declared over several lines is marked whatever its layout`() =
        `a case class declared over several lines is marked whatever its layout`("scala2")

    private fun `a hand-written override before a body val is not marked`(module: String) {
        val probes = probesAfterLoading(module, "BodyVal", "BodyVal\$")

        probes.assertPlumbingMarked("BodyVal", except = setOf("toString"))
        assertEquals(
            GeneratedBy.NONE,
            probes.method("BodyVal", "toString", "()Ljava/lang/String;").generatedBy,
            "the adopter's override is ordinary code, whatever the body declares after it",
        )
        assertEquals(GeneratedBy.NONE, probes.method("BodyVal", "later", "()I").generatedBy)
    }

    @Test
    fun `scala 3 - a hand-written override before a body val is not marked`() =
        `a hand-written override before a body val is not marked`("scala3")

    @Test
    fun `scala 2 - a hand-written override before a body val is not marked`() =
        `a hand-written override before a body val is not marked`("scala2")

    private fun `a case class whose supertype brings Product is marked`(module: String) {
        val probes = probesAfterLoading(module, "Round", "Round\$", "Box", "Box\$")
        val unapplyReturn = { owner: String -> if (module == "scala3") "Lcom/example/scalatarget/$owner;" else "Lscala/Option;" }

        for ((simpleName, parameters) in listOf("Round" to "D", "Box" to "DD")) {
            val type = "Lcom/example/scalatarget/$simpleName;"
            probes.assertPlumbingMarked(simpleName)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(simpleName, "copy", "($parameters)$type").generatedBy)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(simpleName, "toString", "()Ljava/lang/String;").generatedBy)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("$simpleName\$", "apply", "($parameters)$type").generatedBy)
            assertEquals(
                GeneratedBy.CASE_CLASS,
                probes.method("$simpleName\$", "unapply", "($type)${unapplyReturn(simpleName)}").generatedBy,
            )
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("$simpleName\$", "toString", "()Ljava/lang/String;").generatedBy)
        }
        assertEquals(GeneratedBy.NONE, probes.method("Round", "r", "()D").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Box", "w", "()D").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Box", "h", "()D").generatedBy)
    }

    @Test
    fun `scala 3 - a case class whose supertype brings Product is marked`() =
        `a case class whose supertype brings Product is marked`("scala3")

    @Test
    fun `scala 2 - a case class whose supertype brings Product is marked`() =
        `a case class whose supertype brings Product is marked`("scala2")

    private fun `an empty case class's companion unapply returns boolean and is marked`(module: String) {
        val probes = probesAfterLoading(module, "Empty", "Empty\$")

        probes.assertPlumbingMarked("Empty")
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Empty\$", "unapply", "(Lcom/example/scalatarget/Empty;)Z").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Empty\$", "apply", "()Lcom/example/scalatarget/Empty;").generatedBy)
    }

    @Test
    fun `scala 3 - an empty case class's companion unapply returns boolean and is marked`() =
        `an empty case class's companion unapply returns boolean and is marked`("scala3")

    @Test
    fun `scala 2 - an empty case class's companion unapply returns boolean and is marked`() =
        `an empty case class's companion unapply returns boolean and is marked`("scala2")

    private fun `a case object's module class carries marked plumbing`(module: String) {
        val probes = probesAfterLoading(module, "Solo", "Solo\$")

        probes.assertPlumbingMarked("Solo\$")
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Solo\$", "toString", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Solo\$", "<init>", "()V").generatedBy)
        assertEquals(GeneratedBy.SCALA_OBJECT, probes.method("Solo\$", "writeReplace", "()Ljava/lang/Object;").generatedBy)
        val statics = probes.methodsOf("Solo").filter { it.static && it.methodName != "<clinit>" }
        assertTrue(statics.isNotEmpty())
        assertTrue(statics.all { it.generatedBy == GeneratedBy.STATIC_FORWARDER })
    }

    @Test
    fun `scala 3 - a case object's module class carries marked plumbing`() =
        `a case object's module class carries marked plumbing`("scala3")

    @Test
    fun `scala 2 - a case object's module class carries marked plumbing`() =
        `a case object's module class carries marked plumbing`("scala2")

    private fun `a class that is not a case class keeps its toString unmarked`(module: String) {
        val probes = probesAfterLoading(module, "NotCase")

        assertEquals(GeneratedBy.NONE, probes.method("NotCase", "toString", "()Ljava/lang/String;").generatedBy)
    }

    @Test
    fun `scala 3 - a class that is not a case class keeps its toString unmarked`() =
        `a class that is not a case class keeps its toString unmarked`("scala3")

    @Test
    fun `scala 2 - a class that is not a case class keeps its toString unmarked`() =
        `a class that is not a case class keeps its toString unmarked`("scala2")

    private fun `every fixture case class and companion has its plumbing marked and nothing the adopter wrote`(module: String) {
        val probes = probesAfterLoading(module, *ScalaCaseClassFixtures.allClasses(module).toTypedArray())

        for (simpleName in ScalaCaseClassFixtures.allClasses(module)) {
            val instanceMethods = probes.methodsOf(simpleName).filter { !it.static }
            assertTrue(instanceMethods.isNotEmpty(), simpleName)
            val checked =
                instanceMethods.mapNotNull { probe ->
                    ScalaCaseClassFixtures.expectedMark(module, simpleName, probe.methodName, probe.methodDescriptor)?.let { probe to it }
                }
            for ((probe, expected) in checked) {
                assertEquals(expected, probe.generatedBy, "$module $simpleName.${probe.methodName}${probe.methodDescriptor}")
            }
            val markedNames = checked.filter { it.second == GeneratedBy.CASE_CLASS }.mapTo(mutableSetOf()) { it.first.methodName }
            val required =
                if (simpleName in ScalaCaseClassFixtures.CASE_CLASSES) {
                    setOf("canEqual", "equals", "copy", "productArity", "productElement", "productPrefix") -
                        ScalaCaseClassFixtures.HAND_WRITTEN[simpleName].orEmpty()
                } else {
                    // Written's explicit companion gets no toString from scalac.
                    setOf("apply", "unapply")
                }
            assertTrue(markedNames.containsAll(required), "$module $simpleName marks $markedNames")
        }
    }

    @Test
    fun `scala 3 - every fixture case class and companion has its plumbing marked and nothing the adopter wrote`() =
        `every fixture case class and companion has its plumbing marked and nothing the adopter wrote`("scala3")

    @Test
    fun `scala 2 - every fixture case class and companion has its plumbing marked and nothing the adopter wrote`() =
        `every fixture case class and companion has its plumbing marked and nothing the adopter wrote`("scala2")

    private fun `every element type's hashCode, equals and productElement are marked`(module: String) {
        val probes = probesAfterLoading(module, "Mixed", "One", "Hidden", "Priv")

        for (simpleName in listOf("Mixed", "One", "Hidden", "Priv")) {
            for ((name, descriptor) in listOf(
                "hashCode" to "()I",
                "equals" to "(Ljava/lang/Object;)Z",
                "productElement" to "(I)Ljava/lang/Object;",
                "productElementName" to "(I)Ljava/lang/String;",
            )) {
                assertEquals(GeneratedBy.CASE_CLASS, probes.method(simpleName, name, descriptor).generatedBy, "$module $simpleName.$name")
            }
        }
    }

    @Test
    fun `scala 3 - every element type's hashCode, equals and productElement are marked`() =
        `every element type's hashCode, equals and productElement are marked`("scala3")

    @Test
    fun `scala 2 - every element type's hashCode, equals and productElement are marked`() =
        `every element type's hashCode, equals and productElement are marked`("scala2")

    private fun `a hand-written copy whose body is scalac's is marked`(module: String) {
        val probes = probesAfterLoading(module, "HandCopy")

        // HandCopy's copy is the adopter's, `def copy(a: Int = 2): HandCopy = new HandCopy(a)`, but
        // its body is instruction for instruction the copy scalac would have written, so no rule
        // reading bytecode can tell them apart, and it is marked like the generated one. Its
        // default, 2 in place of the current value, lives in the default getter, whose omission
        // probe follows copy's mark.
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("HandCopy", "copy", "(I)Lcom/example/scalatarget/HandCopy;").generatedBy)
        val omission = probes.single { it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.className == "$PACKAGE.HandCopy" }
        assertEquals(GeneratedBy.CASE_CLASS, omission.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("HandCopy", "toString", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("HandCopy", "hashCode", "()I").generatedBy)
    }

    @Test
    fun `scala 3 - a hand-written copy whose body is scalac's is marked`() =
        `a hand-written copy whose body is scalac's is marked`("scala3")

    @Test
    fun `scala 2 - a hand-written copy whose body is scalac's is marked`() =
        `a hand-written copy whose body is scalac's is marked`("scala2")

    @Test
    fun `scala 2 - an accessor for a private or protected element is marked`() {
        val probes = probesAfterLoading("scala2", "Priv", "Hidden")

        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Priv", "a\$access\$0", "()I").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Priv", "b\$access\$1", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Hidden", "secret\$access\$0", "()J").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Priv", "b", "()Ljava/lang/String;").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Hidden", "open", "()Z").generatedBy)
    }

    private fun `an inner and a local case class's companions have their plumbing marked`(module: String) {
        val local = ScalaCaseClassFixtures.companionOf(module, "Outer\$Local\$1")
        val probes = probesAfterLoading(module, "Outer\$Inner", "Outer\$Inner\$", "Outer\$Local\$1", local)

        for ((companion, partner) in listOf("Outer\$Inner\$" to "Outer\$Inner", local to "Outer\$Local\$1")) {
            val type = "Lcom/example/scalatarget/$partner;"
            val unapplyReturn = if (module == "scala3") type else "Lscala/Option;"
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(companion, "apply", "(I)$type").generatedBy, companion)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(companion, "unapply", "($type)$unapplyReturn").generatedBy, companion)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(companion, "toString", "()Ljava/lang/String;").generatedBy, companion)
            if (module == "scala3") {
                assertEquals(
                    GeneratedBy.CASE_CLASS,
                    probes.method(companion, "fromProduct", "(Lscala/Product;)$type").generatedBy,
                    companion,
                )
            }
            assertTrue(probes.methodsOf(companion).none { it.generatedBy == GeneratedBy.SCALA_OBJECT }, "$companion has no MODULE\$")
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(partner, "productPrefix", "()Ljava/lang/String;").generatedBy, partner)
            assertEquals(GeneratedBy.CASE_CLASS, probes.method(partner, "copy", "(I)$type").generatedBy, partner)
        }
    }

    @Test
    fun `scala 3 - an inner and a local case class's companions have their plumbing marked`() =
        `an inner and a local case class's companions have their plumbing marked`("scala3")

    @Test
    fun `scala 2 - an inner and a local case class's companions have their plumbing marked`() =
        `an inner and a local case class's companions have their plumbing marked`("scala2")

    private fun `a copy or apply through an auxiliary constructor is not marked`(module: String) {
        val probes = probesAfterLoading(module, "Aux", "Aux\$")
        val aux = "Lcom/example/scalatarget/Aux;"

        assertEquals(GeneratedBy.NONE, probes.method("Aux", "copy", "(Ljava/lang/String;)$aux").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Aux\$", "apply", "(Ljava/lang/String;)$aux").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Aux\$", "apply", "(I)$aux").generatedBy)
        // Scala 2.13.15 writes no copy of its own once the class declares any method named copy.
        if (module == "scala3") assertEquals(GeneratedBy.CASE_CLASS, probes.method("Aux", "copy", "(I)$aux").generatedBy)
        if (module == "scala2") assertTrue(probes.methodsOf("Aux").none { it.methodName == "copy" && it.methodDescriptor == "(I)$aux" })
    }

    @Test
    fun `scala 3 - a copy or apply through an auxiliary constructor is not marked`() =
        `a copy or apply through an auxiliary constructor is not marked`("scala3")

    @Test
    fun `scala 2 - a copy or apply through an auxiliary constructor is not marked`() =
        `a copy or apply through an auxiliary constructor is not marked`("scala2")

    private fun `a constructor that skips a parameter gives no elements`(module: String) {
        val probes = probesAfterLoading(module, "Q", "Q\$")
        val elementBased =
            listOf(
                "productArity" to "()I",
                "productElement" to "(I)Ljava/lang/Object;",
                "productElementName" to "(I)Ljava/lang/String;",
                "hashCode" to "()I",
                "equals" to "(Ljava/lang/Object;)Z",
            )

        // Scala 2.13.15 passes `a` to Base and stores only `b`, so no field holds element 0 and
        // nothing that depends on the elements is marked. Scala 3.3.4 stores `a` first and keeps
        // the ordinary shape.
        val expected = if (module == "scala2") GeneratedBy.NONE else GeneratedBy.CASE_CLASS
        for ((name, descriptor) in elementBased) {
            assertEquals(expected, probes.method("Q", name, descriptor).generatedBy, "$module Q.$name")
        }
    }

    @Test
    fun `scala 3 - a constructor that skips a parameter gives no elements`() =
        `a constructor that skips a parameter gives no elements`("scala3")

    @Test
    fun `scala 2 - a constructor that skips a parameter gives no elements`() =
        `a constructor that skips a parameter gives no elements`("scala2")

    private fun `a productArity returning another count than the elements' is not marked`(module: String) {
        val probes = probesAfterLoading(module, "Lie")

        assertEquals(GeneratedBy.NONE, probes.method("Lie", "productArity", "()I").generatedBy)
    }

    @Test
    fun `scala 3 - a productArity returning another count than the elements' is not marked`() =
        `a productArity returning another count than the elements' is not marked`("scala3")

    @Test
    fun `scala 2 - a productArity returning another count than the elements' is not marked`() =
        `a productArity returning another count than the elements' is not marked`("scala2")

    @Test
    fun `scala 2 - a hand-written _1 is not marked`() {
        val probes = probesAfterLoading("scala2", "Al")

        assertEquals(GeneratedBy.NONE, probes.method("Al", "_1", "()I").generatedBy)
    }

    @Test
    fun `scala 3 - a hand-written _1 whose body is scalac's is marked`() {
        val probes = probesAfterLoading("scala3", "Al")

        // Scala 3.3.4 writes no `_1` of its own beside the adopter's, and the adopter's body is
        // instruction for instruction the one it would have written.
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Al", "_1", "()I").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Al", "_2", "()Ljava/lang/String;").generatedBy)
    }

    @Test
    fun `scala 3 - a hand-written access accessor is not marked and does not stand in for the element's accessor`() {
        val probes = probesAfterLoading("scala3", "Acc")

        assertEquals(GeneratedBy.NONE, probes.method("Acc", "a\$access\$0", "()I").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Acc", "hashCode", "()I").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Acc", "equals", "(Ljava/lang/Object;)Z").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Acc", "productElement", "(I)Ljava/lang/Object;").generatedBy)
    }

    private fun `three elements of one primitive type mark productElement`(module: String) {
        val probes = probesAfterLoading(module, "P3")

        assertEquals(GeneratedBy.CASE_CLASS, probes.method("P3", "productElement", "(I)Ljava/lang/Object;").generatedBy)
    }

    @Test
    fun `scala 3 - three elements of one primitive type mark productElement`() =
        `three elements of one primitive type mark productElement`("scala3")

    @Test
    fun `scala 2 - three elements of one primitive type mark productElement`() =
        `three elements of one primitive type mark productElement`("scala2")

    private fun `a final case class's equals, written without canEqual, is marked`(module: String) {
        val finals = listOf("FT", "FE", "F1", "FinalOuter\$FIn", "FinalHolder\$FObj")
        val probes = probesAfterLoading(module, *finals.toTypedArray(), "NoCanEqual")

        for (simpleName in finals) {
            assertEquals(
                GeneratedBy.CASE_CLASS,
                probes.method(simpleName, "equals", "(Ljava/lang/Object;)Z").generatedBy,
                "$module $simpleName",
            )
        }
        // In Scala 3.3.4 this hand-written equals is instruction for instruction what scalac writes
        // for a final class; the class is not final, so scalac's own would call canEqual.
        assertEquals(GeneratedBy.NONE, probes.method("NoCanEqual", "equals", "(Ljava/lang/Object;)Z").generatedBy)
    }

    @Test
    fun `scala 3 - a final case class's equals, written without canEqual, is marked`() =
        `a final case class's equals, written without canEqual, is marked`("scala3")

    @Test
    fun `scala 2 - a final case class's equals, written without canEqual, is marked`() =
        `a final case class's equals, written without canEqual, is marked`("scala2")

    @Test
    fun `scala 2 - companion members of Scala 3's shapes are not marked`() {
        val probes = probesAfterLoading("scala2", "U0\$", "Id\$", "FP\$")

        assertEquals(GeneratedBy.NONE, probes.method("U0\$", "unapply", "(L$TYPES/U0;)Z").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("Id\$", "unapply", "(L$TYPES/Id;)L$TYPES/Id;").generatedBy)
        assertEquals(GeneratedBy.NONE, probes.method("FP\$", "fromProduct", "(Lscala/Product;)L$TYPES/FP;").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("FP\$", "unapply", "(L$TYPES/FP;)Lscala/Option;").generatedBy)
    }

    private fun `a primary constructor that builds its own class is still the primary one`(module: String) {
        val probes = probesAfterLoading(module, "Node", "Node\$")
        val node = "L$TYPES/Node;"

        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Node", "copy", "(I)$node").generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("Node\$", "apply", "(I)$node").generatedBy)
        val omission =
            probes.single {
                it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.className == "$PACKAGE.Node" && it.methodName == "copy"
            }
        assertEquals(GeneratedBy.CASE_CLASS, omission.generatedBy, "copy\$default\$1 follows copy")
        if (module == "scala3") {
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("Node\$", "fromProduct", "(Lscala/Product;)$node").generatedBy)
        }
    }

    @Test
    fun `scala 3 - a primary constructor that builds its own class is still the primary one`() =
        `a primary constructor that builds its own class is still the primary one`("scala3")

    @Test
    fun `scala 2 - a primary constructor that builds its own class is still the primary one`() =
        `a primary constructor that builds its own class is still the primary one`("scala2")

    private fun `a try-catch in the class body leaves the elements readable`(module: String) {
        val probes = probesAfterLoading(module, "TryBody")

        for ((name, descriptor) in listOf(
            "productArity" to "()I",
            "productElement" to "(I)Ljava/lang/Object;",
            "hashCode" to "()I",
            "equals" to "(Ljava/lang/Object;)Z",
        )) {
            assertEquals(GeneratedBy.CASE_CLASS, probes.method("TryBody", name, descriptor).generatedBy, "$module TryBody.$name")
        }
    }

    @Test
    fun `scala 3 - a try-catch in the class body leaves the elements readable`() =
        `a try-catch in the class body leaves the elements readable`("scala3")

    @Test
    fun `scala 2 - a try-catch in the class body leaves the elements readable`() =
        `a try-catch in the class body leaves the elements readable`("scala2")

    private fun `a productArity returning fewer than the elements is not marked`(module: String) {
        val probes = probesAfterLoading(module, "Lie2")

        assertEquals(GeneratedBy.NONE, probes.method("Lie2", "productArity", "()I").generatedBy)
    }

    @Test
    fun `scala 3 - a productArity returning fewer than the elements is not marked`() =
        `a productArity returning fewer than the elements is not marked`("scala3")

    @Test
    fun `scala 2 - a productArity returning fewer than the elements is not marked`() =
        `a productArity returning fewer than the elements is not marked`("scala2")

    private fun `a final case class with its own canEqual keeps the call in equals`(module: String) {
        val probes = probesAfterLoading(module, "UC", "UC2", "FZC", "FinalOuter\$FInEmpty")
        val equals = "(Ljava/lang/Object;)Z"

        // scalac leaves canEqual out of a final class's equals only when canEqual is its own. UC's
        // canEqual is the adopter's but compiles to scalac's body, so it is marked, and scalac's
        // equals, which still calls it, is marked too.
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("UC", "equals", equals).generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("UC", "canEqual", equals).generatedBy)
        // UC2's equals is the adopter's, instruction for instruction scalac's equals for a final
        // class whose canEqual is scalac's, which UC2's is not.
        assertEquals(GeneratedBy.NONE, probes.method("UC2", "equals", equals).generatedBy)
        // With no elements, Scala 2.13.15 drops the call for any final class and Scala 3.3.4 keeps it
        // when canEqual is the adopter's; each is scalac's own equals.
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("FZC", "equals", equals).generatedBy)
        assertEquals(GeneratedBy.CASE_CLASS, probes.method("FinalOuter\$FInEmpty", "equals", equals).generatedBy)
    }

    @Test
    fun `scala 3 - a final case class with its own canEqual keeps the call in equals`() =
        `a final case class with its own canEqual keeps the call in equals`("scala3")

    @Test
    fun `scala 2 - a final case class with its own canEqual keeps the call in equals`() =
        `a final case class with its own canEqual keeps the call in equals`("scala2")
}
