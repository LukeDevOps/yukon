package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves each condition the writer produces against real compiled fixtures: `ConditionTarget.kt`,
 * `ConditionJavaTarget.java` and each Scala fixture module's `Conditions.scala`. Every shape read
 * back to source here was confirmed with `javap -c -l -p` on these fixtures first. See ADR 0037.
 */
class ConditionWriterTest {
    private fun code(text: String) = ConditionPart(ConditionPartKind.CODE, text)

    private fun literal(text: String) = ConditionPart(ConditionPartKind.STRING_LITERAL, text)

    private val placeholder = ConditionPart(ConditionPartKind.PLACEHOLDER, "")

    private fun kotlinBytes(simpleName: String) = File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private fun javaBytes(simpleName: String) = File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    /** Reads another test fixture's bytes by internal name, the way the agent reads a class through its loader. */
    private val fixtureLookup: (String) -> ByteArray? = { internalName ->
        listOf("kotlin", "java")
            .map { File("build/classes/$it/test/$internalName.class") }
            .firstOrNull { it.isFile }
            ?.readBytes()
    }

    /** Every site's condition in [method] of the class in [bytes], in site index order. */
    private fun conditions(
        bytes: ByteArray,
        method: String,
        lookup: (String) -> ByteArray? = fixtureLookup,
    ): List<List<ConditionPart>> =
        BranchSiteAnalyzer
            .analyze(bytes, lookup, includePackages = listOf("com.example")) { _, _ -> true }
            .sites
            .filter { it.methodName == method }
            .map { it.condition }

    private fun kotlin(method: String): List<ConditionPart> = conditions(kotlinBytes("ConditionTarget"), method).single()

    private fun kotlinTopLevel(method: String): List<ConditionPart> = conditions(kotlinBytes("ConditionTargetKt"), method).single()

    private fun java(method: String): List<ConditionPart> = conditions(javaBytes("ConditionJavaTarget"), method).single()

    private fun scala(
        module: String,
        method: String,
    ): List<List<ConditionPart>> = conditions(ScalaFixtures.classBytes(module, "Conditions"), method)

    // --- Kotlin idioms ---

    @Test
    fun `kotlin - Intrinsics areEqual reads as == and its ifne as !=`() {
        assertEquals(listOf(code("a == b")), kotlin("areEqualCheck"))
        assertEquals(listOf(code("a != b")), kotlin("notEqualCheck"))
    }

    @Test
    fun `kotlin - a Kotlin property getter reads as the property`() {
        assertEquals(listOf(code("target.limit > 3")), kotlinTopLevel("getterCheck"))
    }

    @Test
    fun `kotlin - a Java getX and isX getter read as properties`() {
        assertEquals(listOf(code("file.name == "), literal("x")), kotlin("javaGetterCheck"))
        assertEquals(listOf(code("file.isFile")), kotlin("javaIsGetterCheck"))
    }

    @Test
    fun `kotlin - a field read in its own class reads as the field`() {
        assertEquals(listOf(code("other.limit > 3")), kotlin("propertyCheck"))
    }

    @Test
    fun `kotlin - Intrinsics compare reads as the relation it feeds`() {
        assertEquals(listOf(code("a > b")), kotlin("compareToCheck"))
    }

    @Test
    fun `kotlin - a makeConcatWithConstants template reads as a concatenation with literal parts`() {
        assertEquals(
            listOf(code("name + "), literal("-"), code(" + count == "), literal("x-1")),
            kotlin("templateCheck"),
        )
    }

    @Test
    fun `kotlin - an inlined in-scope copy uses the inline function's parameter name without the iv suffix`() {
        assertEquals(listOf(code("value > 10")), kotlin("inlineCaller"))
    }

    @Test
    fun `kotlin - an extension receiver reads as this`() {
        assertEquals(listOf(code("this.limit > 2")), kotlinTopLevel("extensionCheck"))
    }

    @Test
    fun `kotlin - a companion property read names the owner class`() {
        assertEquals(listOf(code("ConditionHolder.threshold > 5")), kotlin("companionCheck"))
    }

    @Test
    fun `kotlin - an object property read names the object`() {
        assertEquals(listOf(code("ConditionSettings.enabled")), kotlin("objectCheck"))
    }

    @Test
    fun `kotlin - is and not-is checks`() {
        assertEquals(listOf(code("value is String")), kotlin("isCheck"))
        assertEquals(listOf(code("value !is String")), kotlin("notIsCheck"))
    }

    @Test
    fun `kotlin - if_acmp reads as === between instances of ordinary classes`() {
        assertEquals(listOf(code("a === b")), kotlin("identityCheck"))
        assertEquals(listOf(code("a === b")), kotlinTopLevel("sameInstanceCheck"))
    }

    @Test
    fun `kotlin - if_acmp reads as == when an operand's class is an enum`() {
        assertEquals(listOf(code("mode == ConditionMode.FAST")), kotlin("enumCheck"))
    }

    @Test
    fun `kotlin - if_acmp on an enum keeps === when the lookup cannot read the enum's class`() {
        val condition = conditions(kotlinBytes("ConditionTarget"), "enumCheck") { error("the class cannot be read") }.single()

        assertEquals(listOf(code("mode === ConditionMode.FAST")), condition)
    }

    @Test
    fun `kotlin - a narrowing conversion reads as its to-call`() {
        assertEquals(listOf(code("total.toInt() > 3")), kotlinTopLevel("narrowingCheck"))
        assertEquals(listOf(code("code.toByte() > 3")), kotlinTopLevel("byteCheck"))
    }

    @Test
    fun `kotlin - a widening conversion is transparent`() {
        assertEquals(listOf(code("count < limit")), kotlinTopLevel("wideningCheck"))
    }

    // --- Java idioms ---

    @Test
    fun `java - equals stays a call`() {
        assertEquals(listOf(code("a.equals(b)")), java("equalsCall"))
    }

    @Test
    fun `java - == and != on references`() {
        assertEquals(listOf(code("a == b")), java("referenceEq"))
        assertEquals(listOf(code("a != b")), java("referenceNe"))
        assertEquals(listOf(code("o.getClass() == String.class")), java("classLiteralCheck"))
    }

    @Test
    fun `java - instanceof`() {
        assertEquals(listOf(code("o instanceof String")), java("instanceOfCheck"))
    }

    @Test
    fun `java - a getter stays a call`() {
        assertEquals(listOf(code("other.getCount() > 2")), java("getterCall"))
    }

    @Test
    fun `java - makeConcatWithConstants reads as a + concatenation`() {
        assertEquals(listOf(code("(name + "), literal("-"), code(" + n).equals("), literal("x-1"), code(")")), java("concatCheck"))
    }

    @Test
    fun `java - a concatenation that starts with two numbers starts with an empty string`() {
        assertEquals(listOf(code("("), literal(""), code(" + a + b).isEmpty()")), java("numericConcatCheck"))
    }

    @Test
    fun `java - field reads, a static field by its owner's simple name`() {
        assertEquals(listOf(code("other.count + ConditionJavaTarget.total > 10")), java("fieldRead"))
    }

    @Test
    fun `java - array loads and array length`() {
        assertEquals(listOf(code("values[i] > values.length")), java("arrayRead"))
    }

    @Test
    fun `java - new with its constructor`() {
        assertEquals(listOf(code("new StringBuilder(seed).length() > 3")), java("newCheck"))
    }

    @Test
    fun `java - negation, a char constant and a dup`() {
        assertEquals(listOf(code("-a > 5")), java("negationCheck"))
        assertEquals(listOf(code("c == 'x'")), java("charCheck"))
        assertEquals(listOf(code("a > 0")), java("dupCheck"))
    }

    @Test
    fun `java - unboxing and checkcast are transparent`() {
        assertEquals(listOf(code("boxed > 4")), java("boxedCheck"))
        assertEquals(listOf(code("o.length() > 1")), java("castCheck"))
    }

    @Test
    fun `java - a narrowing conversion reads as a cast`() {
        assertEquals(listOf(code("(int) total > 3")), java("narrowingCheck"))
        assertEquals(listOf(code("(long) (value + offset) > 3L")), java("doubleToLongCheck"))
        assertEquals(listOf(code("(char) code == 'x'")), java("charNarrowingCheck"))
        assertEquals(listOf(code("(int) value")), java("unknownSwitch"))
    }

    @Test
    fun `java - a widening conversion is transparent`() {
        assertEquals(listOf(code("a > 0.5")), java("intToDoubleCheck"))
        assertEquals(listOf(code("f > 0.5")), java("floatToDoubleCheck"))
        assertEquals(listOf(code("a < b")), java("longToFloatCheck"))
    }

    // --- Scala idioms, on Scala 2 and Scala 3 output alike ---

    private fun `BoxesRunTime equals reads as ==`(module: String) {
        assertEquals(listOf(listOf(code("a == b"))), scala(module, "anyEquals"))
    }

    @Test
    fun `scala 2 - BoxesRunTime equals reads as ==`() = `BoxesRunTime equals reads as ==`("scala2")

    @Test
    fun `scala 3 - BoxesRunTime equals reads as ==`() = `BoxesRunTime equals reads as ==`("scala3")

    /** The inline null check scalac puts around `equals`: its `ifnonnull`, `ifnull` and `ifeq` sites, in that order. */
    private fun `== on references reads as ==, with its null checks`(module: String) {
        assertEquals(
            listOf(listOf(code("a == null")), listOf(code("b != null")), listOf(code("a == b"))),
            scala(module, "stringEquals"),
        )
        assertEquals(
            listOf(
                listOf(code("text == null")),
                listOf(literal("say \"hi\"\nbye"), code(" != null")),
                listOf(code("text == "), literal("say \"hi\"\nbye")),
            ),
            scala(module, "literalCheck"),
        )
    }

    @Test
    fun `scala 2 - == on references reads as ==, with its null checks`() = `== on references reads as ==, with its null checks`("scala2")

    @Test
    fun `scala 3 - == on references reads as ==, with its null checks`() = `== on references reads as ==, with its null checks`("scala3")

    private fun `eq, ne, unboxing and isInstanceOf`(module: String) {
        assertEquals(listOf(listOf(code("a eq b"))), scala(module, "referenceEq"))
        assertEquals(listOf(listOf(code("a ne b"))), scala(module, "referenceNe"))
        assertEquals(listOf(listOf(code("a > 3"))), scala(module, "boxedCompare"))
        assertEquals(listOf(listOf(code("a.isInstanceOf[String]"))), scala(module, "instanceCheck"))
    }

    private fun `conversions`(module: String) {
        assertEquals(listOf(listOf(code("total.toInt > 3"))), scala(module, "narrowing"))
        assertEquals(listOf(listOf(code("count < limit"))), scala(module, "widening"))
    }

    @Test
    fun `scala 2 - a narrowing conversion reads as toInt and a widening one is transparent`() = `conversions`("scala2")

    @Test
    fun `scala 3 - a narrowing conversion reads as toInt and a widening one is transparent`() = `conversions`("scala3")

    @Test
    fun `scala 2 - eq, ne, unboxing and isInstanceOf`() = `eq, ne, unboxing and isInstanceOf`("scala2")

    @Test
    fun `scala 3 - eq, ne, unboxing and isInstanceOf`() = `eq, ne, unboxing and isInstanceOf`("scala3")

    // --- Each jump family's fall-through reading ---

    @Test
    fun `ifeq on a boolean reads as the boolean, and ifne as its negation`() {
        assertEquals(listOf(code("flag")), kotlin("booleanCheck"))
        assertEquals(listOf(code("!flag")), kotlin("negatedBooleanCheck"))
    }

    @Test
    fun `ifeq on an int reads as != 0, and ifne as == 0`() {
        assertEquals(listOf(code("count != 0")), kotlin("intZeroCheck"))
        assertEquals(listOf(code("a == 0")), java("zeroEq"))
        assertEquals(listOf(code("a < 0")), java("zeroLt"))
    }

    @Test
    fun `each if_icmp reads as its test negated`() {
        assertEquals(listOf(code("a == b")), java("icmpEq"), "if_icmpne")
        assertEquals(listOf(code("a != b")), java("icmpNe"), "if_icmpeq")
        assertEquals(listOf(code("a < b")), java("icmpLt"), "if_icmpge")
        assertEquals(listOf(code("a >= b")), java("icmpGe"), "if_icmplt")
        assertEquals(listOf(code("a > b")), java("icmpGt"), "if_icmple")
        assertEquals(listOf(code("a <= b")), java("icmpLe"), "if_icmpgt")
    }

    @Test
    fun `lcmp, fcmpg and dcmpl read as the source relation between their operands`() {
        assertEquals(listOf(code("a > b")), java("longCompare"))
        assertEquals(listOf(code("a < b")), java("floatLess"))
        assertEquals(listOf(code("a > b")), java("doubleGreater"))
        assertEquals(listOf(code("discounted > 100.0")), kotlin("doubleCheck"))
    }

    @Test
    fun `a double compare whose NaN result falls through reads as the negated relation`() {
        assertEquals(listOf(code("!(a > b)")), java("doubleNotGreater"))
    }

    @Test
    fun `ifnull reads as != null and ifnonnull as == null`() {
        assertEquals(listOf(code("s != null")), java("nullCheck"))
        assertEquals(listOf(code("s == null")), java("nonNullCheck"))
    }

    @Test
    fun `a switch's condition is its subject`() {
        assertEquals(listOf(code("value + 1")), java("subjectSwitch"))
    }

    // --- Literals and placeholders ---

    @Test
    fun `a string literal is one part holding its raw value, quote and newline included`() {
        assertEquals(listOf(code("text == "), literal("say \"hi\"\nbye")), kotlin("literalCheck"))
        assertEquals(listOf(code("text.equals("), literal("say \"hi\"\nbye"), code(")")), java("literalCheck"))
    }

    @Test
    fun `an unknown invokedynamic is a placeholder inside an otherwise written condition`() {
        assertEquals(listOf(code("ConditionJavaTarget.call("), placeholder, code(") > 0")), java("lambdaCheck"))
    }

    @Test
    fun `a window that is only an unknown value gives no condition`() {
        assertEquals(emptyList(), java("patternSwitch"))
    }

    @Test
    fun `an int widened to compare with a long literal reads as written`() {
        assertEquals(
            listOf(listOf(code("o != null")), listOf(code("o.hashCode() > 7L"))),
            conditions(javaBytes("ConditionJavaTarget"), "nullLiteralCheck"),
        )
    }

    @Test
    fun `a dropped site has no condition`() {
        val sites =
            BranchSiteAnalyzer
                .analyze(kotlinBytes("InlinedCopyTargetKt"), includePackages = listOf("com.example.target")) { _, _ -> true }
                .sites
        val dropped = sites.filter { it.dropReason != null }
        check(dropped.isNotEmpty())
        assertEquals(emptyList(), dropped.flatMap { it.condition })
    }
}
