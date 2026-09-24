package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves each switch lowering ADR 0038 reads back to source cases, against real compiled fixtures:
 * `SwitchJavaTarget.java`, `SwitchTarget.kt` and each Scala fixture module's `Switches.scala`.
 * Every shape was confirmed with `javap -c -l -p` on these fixtures first.
 */
class SwitchLoweringTest {
    private fun code(text: String) = ConditionPart(ConditionPartKind.CODE, text)

    private fun literal(text: String) = ConditionPart(ConditionPartKind.STRING_LITERAL, text)

    /** Reads another test fixture's bytes by internal name, the way the agent reads a class through its loader. */
    private val fixtureLookup: (String) -> ByteArray? = { internalName ->
        listOf("kotlin", "java")
            .map { File("build/classes/$it/test/$internalName.class") }
            .firstOrNull { it.isFile }
            ?.readBytes()
    }

    private fun javaBytes() = File("build/classes/java/test/com/example/target/SwitchJavaTarget.class").readBytes()

    private fun kotlinBytes() = File("build/classes/kotlin/test/com/example/target/SwitchTarget.class").readBytes()

    private fun analysis(bytes: ByteArray) =
        BranchSiteAnalyzer.analyze(bytes, fixtureLookup, includePackages = listOf("com.example")) { name, _ -> name != "<init>" }

    private val java by lazy { analysis(javaBytes()) }
    private val kotlin by lazy { analysis(kotlinBytes()) }

    private fun kept(
        analysis: BranchSiteAnalyzer.Analysis,
        method: String,
    ): List<KeptBranchSite> = analysis.keptSites.filter { it.site.methodName == method }

    private fun dropped(
        analysis: BranchSiteAnalyzer.Analysis,
        method: String,
    ): List<BranchSite> = analysis.sites.filter { it.methodName == method && it.dropReason != null }

    /** Each outcome as its role and label: `RED`, `"open"` for a literal, or `default`. */
    private fun outcomes(site: KeptBranchSite): List<String> =
        site.outcomes.map { outcome ->
            when (outcome.role) {
                BranchRole.DEFAULT -> {
                    "default"
                }

                BranchRole.CASE -> {
                    val label = outcome.caseLabel.single()
                    if (label.kind == ConditionPartKind.STRING_LITERAL) "\"${label.text}\"" else label.text
                }

                else -> {
                    outcome.role.name
                }
            }
        }

    /** The one line each outcome guards whole, or null when it guards none or several. */
    private fun guardedLines(site: KeptBranchSite): List<Int?> =
        site.outcomes.map { outcome ->
            outcome.guardedLines
                .singleOrNull()
                ?.takeIf { it.firstLine == it.lastLine }
                ?.firstLine
        }

    // --- javac ---

    @Test
    fun `javac enum switch - one rebuilt site with each constant as a label, the default last and the subject as condition`() {
        val site = kept(java, "enumStatement").single()

        assertEquals(listOf("RED", "BLUE", "default"), outcomes(site))
        assertEquals(listOf(code("color")), site.site.condition)
        assertTrue(site.outcomes.all { it.caseKey == null })
        assertEquals(listOf(9, 11, 13), guardedLines(site), "each case guards its own body")
    }

    @Test
    fun `javac enum switch - labels come from the map class, whose values count across the whole class`() {
        val site = kept(java, "enumNoDefault").single()

        assertEquals(listOf("RED", "GREEN", "default"), outcomes(site), "GREEN is map value 3, after enumStatement's RED and BLUE")
        assertEquals(listOf(24, 22), site.outcomes.take(2).map { it.guardedLines.single().lastLine })
    }

    @Test
    fun `javac enum switch expression - the default that only throws MatchException is not listed and keeps its branch index`() {
        val site = kept(java, "enumExpression").single()

        assertEquals(listOf("RED", "BLUE", "GREEN"), outcomes(site))
        assertEquals(listOf(32, 34, 33), guardedLines(site))
        assertTrue(site.site.throwingDefault)
        val first = site.outcomes.first().branchIndex
        assertEquals(listOf(first, first + 1, first + 2), site.outcomes.map { it.branchIndex })
        val next =
            kept(java, "stringStatement")
                .single()
                .outcomes
                .first()
                .branchIndex
        assertTrue(next > first + 3, "the default's branch index and the string lowering's sites still count")
    }

    @Test
    fun `javac 17 enum switch expression - the default that only throws IncompatibleClassChangeError is not listed`() {
        val site = kept(analysis(withJavac17Default(javaBytes())), "enumExpression").single()

        assertEquals(listOf("RED", "BLUE", "GREEN"), outcomes(site))
        assertTrue(site.site.throwingDefault)
    }

    @Test
    fun `javac string switch - the index switch is rebuilt with each literal, and the hash switch and equals checks are dropped`() {
        val site = kept(java, "stringStatement").single()

        assertEquals(listOf("\"open\"", "\"closed\"", "\"done\"", "\"Aa\"", "\"BB\"", "default"), outcomes(site))
        assertEquals(listOf(code("status")), site.site.condition)
        assertEquals(listOf(41, null, null, 46, 48, 50), guardedLines(site), "two labels on one body each guard nothing alone")
        val lowering = dropped(java, "stringStatement")
        assertEquals(6, lowering.size, "the hash switch and five equals checks")
        assertTrue(lowering.all { it.dropReason == BranchDropReason.SWITCH_LOWERING })
        assertEquals(
            lowering.map { it.siteIndex }.toSet(),
            java.sites
                .filter { it.methodName == "stringStatement" }
                .map { it.siteIndex }
                .toSet() - site.site.siteIndex,
        )
    }

    @Test
    fun `javac pattern switch - typeSwitch cases are the class patterns, and the subject is the condition`() {
        val site = kept(java, "patternSwitch").single()

        assertEquals(listOf("String", "Integer", "default"), outcomes(site))
        assertEquals(listOf(code("value")), site.site.condition)
        assertEquals(listOf(56, 57, 58), guardedLines(site))
    }

    @Test
    fun `javac pattern switch - a when guard stays its own site, guarded by its case`() {
        val (switch, guard) = kept(java, "guardedPattern")

        assertEquals(listOf("String", "String", "default"), outcomes(switch))
        assertEquals(listOf(null, null), switch.outcomes.take(2).map { it.branchKey }, "two cases with one label get no key")
        assertEquals(listOf(code("s.length() <= 3")), guard.site.condition)
        assertEquals(switch.outcomes.first().branchIndex, guard.guard)
    }

    @Test
    fun `javac enumSwitch - an enum constant and a class pattern label their cases`() {
        val (switch, guard) = kept(java, "enumPattern")

        assertEquals(listOf("RED", "SwitchColor", "default"), outcomes(switch))
        assertEquals(listOf(code("color")), switch.site.condition)
        assertEquals(switch.outcomes[1].branchIndex, guard.guard)
    }

    @Test
    fun `javac sealed pattern switch - the MatchException default is not listed`() {
        val site = kept(java, "sealedPattern").single()

        assertEquals(listOf("Circle", "Square"), outcomes(site))
        assertEquals(listOf(89, 90), guardedLines(site))
    }

    @Test
    fun `javac pattern switch - case null reads as null`() {
        val site = kept(java, "nullCase").single()

        assertEquals(listOf("null", "String", "default"), outcomes(site))
        assertEquals(listOf(code("null"), code("String")), site.outcomes.take(2).map { it.caseLabel.single() })
    }

    // --- kotlinc ---

    @Test
    fun `kotlinc enum when - one rebuilt site with each constant as a label, else last and the subject as condition`() {
        val site = kept(kotlin, "enumWithElse").single()

        assertEquals(listOf("RED", "BLUE", "default"), outcomes(site))
        assertEquals(listOf(code("tint")), site.site.condition)
        assertEquals(listOf(10, 11, 12), guardedLines(site))
    }

    @Test
    fun `kotlinc exhaustive enum when - the NoWhenBranchMatchedException default is not listed, as an expression and as a statement`() {
        val expression = kept(kotlin, "enumExhaustive").single()
        val statement = kept(kotlin, "enumStatement").single()

        assertEquals(listOf("RED", "BLUE", "GREEN"), outcomes(expression))
        assertEquals(listOf(27, 29, 28), guardedLines(expression))
        assertEquals(listOf("RED", "BLUE", "GREEN"), outcomes(statement))
        assertTrue(expression.site.throwingDefault && statement.site.throwingDefault)
    }

    @Test
    fun `kotlinc nullable enum when - the null check is dropped, and a null branch reads as null`() {
        val nullable = kept(kotlin, "enumNullable").single()
        val nullBranch = kept(kotlin, "enumWithNullBranch").single()

        assertEquals(listOf("RED", "GREEN", "default"), outcomes(nullable))
        assertEquals(listOf(code("tint")), nullable.site.condition)
        assertEquals(listOf(BranchDropReason.SWITCH_LOWERING), dropped(kotlin, "enumNullable").map { it.dropReason })
        assertEquals(listOf("null", "RED", "default"), outcomes(nullBranch))
        assertEquals(listOf(78, 79, 80), guardedLines(nullBranch))
    }

    @Test
    fun `kotlinc when over a Java enum reads the Java enum's constants`() {
        assertEquals(listOf("GREEN", "RED", "default"), outcomes(kept(kotlin, "javaEnum").single()))
    }

    @Test
    fun `kotlinc string when - the hash switch is dropped, and each equals check stays a site reading subject against its literal`() {
        val sites = kept(kotlin, "stringWhen")

        assertEquals(
            listOf(
                listOf(code("status != "), literal("Aa")),
                listOf(code("status != "), literal("BB")),
                listOf(code("status != "), literal("closed")),
                listOf(code("status != "), literal("done")),
                listOf(code("status == "), literal("open")),
            ),
            sites.map { it.site.condition },
        )
        assertTrue(sites.all { it.site.caseLabels == null })
        assertEquals(listOf(BranchDropReason.SWITCH_LOWERING), dropped(kotlin, "stringWhen").map { it.dropReason })
        assertEquals(
            41,
            sites
                .last()
                .outcomes
                .single { it.role == BranchRole.FALL_THROUGH }
                .guardedLines
                .single()
                .firstLine,
        )
    }

    @Test
    fun `kotlinc nullable string when - the null check and the hash switch are dropped`() {
        assertEquals(2, dropped(kotlin, "stringNullable").size)
        assertEquals(
            listOf(
                listOf(code("status != "), literal("closed")),
                listOf(code("status != "), literal("done")),
                listOf(code("status == "), literal("open")),
            ),
            kept(kotlin, "stringNullable").map { it.site.condition },
        )
    }

    // --- scalac ---

    @Test
    fun `scalac 2 string match - the null check and hash switch are dropped, and each equals check reads subject against its literal`() {
        val analysis = analysis(ScalaFixtures.classBytes("scala2", "Switches"))

        assertEquals(2, dropped(analysis, "stringMatch").size)
        assertEquals(
            listOf("closed", "Aa", "BB", "done", "open").map { listOf(code("status == "), literal(it)) },
            kept(analysis, "stringMatch").map { it.site.condition },
        )
    }

    @Test
    fun `scalac 3 string match - the null check and hash switch are dropped, and each equals check reads subject against its literal`() {
        val analysis = analysis(ScalaFixtures.classBytes("scala3", "Switches"))

        assertEquals(2, dropped(analysis, "stringMatch").size)
        assertEquals(
            listOf(
                listOf(code("status != "), literal("closed")),
                listOf(code("status == "), literal("BB")),
                listOf(code("status == "), literal("Aa")),
                listOf(code("status != "), literal("done")),
                listOf(code("status == "), literal("open")),
            ),
            kept(analysis, "stringMatch").map { it.site.condition },
        )
    }

    @Test
    fun `scalac enum, Enumeration and sealed matches compile to equals chains with no switch, and stay plain sites`() {
        val scala3 = analysis(ScalaFixtures.classBytes("scala3", "Switches"))
        val scala2 = analysis(ScalaFixtures.classBytes("scala2", "Switches"))

        for ((analysis, method) in listOf(
            scala3 to "enumMatch",
            scala3 to "enumExhaustive",
            scala2 to "sealedMatch",
            scala2 to "enumerationMatch",
        )) {
            assertTrue(dropped(analysis, method).isEmpty(), method)
            assertTrue(kept(analysis, method).all { !it.site.isSwitch && it.site.caseLabels == null }, method)
        }
    }

    // --- fallback ---

    @Test
    fun `an enum map class the lookup cannot read leaves the switch as a plain site with numeric case keys`() {
        val plain = BranchSiteAnalyzer.analyze(javaBytes(), includePackages = listOf("com.example")) { name, _ -> name != "<init>" }
        val site = kept(plain, "enumExpression").single()

        assertEquals(listOf(1, 2, 3, null), site.outcomes.map { it.caseKey })
        assertEquals(BranchRole.DEFAULT, site.outcomes.last().role)
        assertTrue(site.outcomes.all { it.caseLabel.isEmpty() })
    }

    /**
     * [bytes] with `enumExpression`'s default rewritten to the shape javac 17 emits, confirmed with
     * `javap -c` on javac 17.0.15 output: `new IncompatibleClassChangeError; dup; invokespecial
     * <init>()V; athrow` in place of javac 21's `MatchException` with two nulls.
     */
    private fun withJavac17Default(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != "enumExpression") return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String,
                        ) = super.visitTypeInsn(opcode, if (type == MATCH_EXCEPTION) ICCE else type)

                        override fun visitInsn(opcode: Int) {
                            if (opcode != Opcodes.ACONST_NULL) super.visitInsn(opcode)
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (owner == MATCH_EXCEPTION) {
                                super.visitMethodInsn(opcode, ICCE, name, "()V", isInterface)
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                            }
                        }
                    }
                }
            }
        ClassReader(bytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    private companion object {
        const val MATCH_EXCEPTION = "java/lang/MatchException"
        const val ICCE = "java/lang/IncompatibleClassChangeError"
    }
}
