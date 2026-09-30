package dev.otherlode.instrumentation.branch

import dev.otherlode.export.CallEdge
import dev.otherlode.export.GeneratedBy
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ByteVector
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves ADR 0048's rules at the analyser, over the Scala fixture modules' own class bytes and over
 * classes built here with ASM: a call into a static forwarder passes through to the object's
 * method, and the forwarder shape counts only in a class scalac compiled.
 */
class ScalaGeneratedMethodsAnalyzerTest {
    private companion object {
        const val PACKAGE = "com/example/scalatarget"
    }

    /** Reads a class of the fixture module [module] by internal name, as the agent's own lookup would. */
    private fun fixtureLookup(module: String): (String) -> ByteArray? =
        { internalName ->
            File(System.getProperty("otherlode.fixtures.$module.dir"), "$internalName.class").takeIf { it.isFile }?.readBytes()
        }

    private fun analyze(
        classBytes: ByteArray,
        lookup: (String) -> ByteArray? = { null },
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(classBytes, lookup, listOf("com.example.scalatarget")) { name, _ -> name != "<clinit>" }

    /** A class attribute named [name] with an empty body, such as the `Scala` attribute scalac writes. */
    private class ScalaAttribute(
        name: String = "Scala",
    ) : Attribute(name) {
        override fun write(
            classWriter: ClassWriter?,
            code: ByteArray?,
            codeLength: Int,
            maxStack: Int,
            maxLocals: Int,
        ): ByteVector = ByteVector()
    }

    /**
     * A class named [internalName] with one static `m(IJ)I` whose body reads `<moduleOwner>.MODULE$`,
     * loads both parameters and calls `m(IJ)I` on it, the shape scalac gives a static forwarder.
     * With [scala] the class carries a `Scala` attribute.
     */
    private fun forwarderShaped(
        internalName: String,
        moduleOwner: String,
        scala: Boolean,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        if (scala) writer.visitAttribute(ScalaAttribute())
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "(IJ)I", null, null)
        method.visitCode()
        method.visitFieldInsn(Opcodes.GETSTATIC, moduleOwner, "MODULE\$", "L$moduleOwner;")
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitVarInsn(Opcodes.LLOAD, 1)
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, moduleOwner, "m", "(IJ)I", false)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A class with one static method per entry of [calls], each calling that static method and returning its result. */
    private fun caller(vararg calls: Triple<String, String, String>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "$PACKAGE/ForwarderCaller", null, "java/lang/Object", null)
        calls.forEachIndexed { index, (owner, name, descriptor) ->
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call$index", "()Ljava/lang/Object;", null, null)
            method.visitCode()
            if (descriptor.startsWith("(II)")) {
                method.visitInsn(Opcodes.ICONST_1)
                method.visitInsn(Opcodes.ICONST_2)
            }
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false)
            if (descriptor.endsWith(")I")) method.visitInsn(Opcodes.POP).also { method.visitInsn(Opcodes.ACONST_NULL) }
            method.visitInsn(Opcodes.ARETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun `a call into a static forwarder reaches the object's method`(module: String) {
        val analysis =
            analyze(
                caller(
                    Triple("$PACKAGE/Driver", "callSimpleAllOmitted", "()I"),
                    Triple("$PACKAGE/Cc", "apply", "(II)L$PACKAGE/Cc;"),
                ),
                fixtureLookup(module),
            )

        assertEquals(
            listOf(
                CallEdge("com.example.scalatarget.Driver\$", "<clinit>", "()V", virtual = false),
                CallEdge("com.example.scalatarget.Driver\$", "callSimpleAllOmitted", "()I", virtual = true),
                CallEdge("com.example.scalatarget.Driver", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("call0", "()Ljava/lang/Object;"),
        )
        assertEquals(
            listOf(
                CallEdge("com.example.scalatarget.Cc\$", "<clinit>", "()V", virtual = false),
                CallEdge("com.example.scalatarget.Cc\$", "apply", "(II)Lcom/example/scalatarget/Cc;", virtual = true),
                CallEdge("com.example.scalatarget.Cc", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("call1", "()Ljava/lang/Object;"),
        )
    }

    @Test
    fun `scala 3 - a call into a static forwarder reaches the object's method`() =
        `a call into a static forwarder reaches the object's method`("scala3")

    @Test
    fun `scala 2 - a call into a static forwarder reaches the object's method`() =
        `a call into a static forwarder reaches the object's method`("scala2")

    @Test
    fun `the forwarder shape is a static forwarder only in a class carrying a Scala attribute`() {
        val scala = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Shape\$", scala = true))
        val java = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Shape\$", scala = false))

        assertEquals(GeneratedBy.STATIC_FORWARDER, scala.generatedBy("m", "(IJ)I"))
        assertEquals(GeneratedBy.NONE, java.generatedBy("m", "(IJ)I"))
    }

    @Test
    fun `a forwarder to an object other than the class's own twin is not a static forwarder`() {
        val analysis = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Other\$", scala = true))

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("m", "(IJ)I"))
    }

    private fun `a companion whose partner cannot be read marks no plumbing`(module: String) {
        val companion = fixtureLookup(module)("$PACKAGE/Cc\$")!!

        val analysis = analyze(companion)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("apply", "(II)L$PACKAGE/Cc;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("toString", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.SCALA_OBJECT, analysis.generatedBy("writeReplace", "()Ljava/lang/Object;"))
    }

    @Test
    fun `scala 3 - a companion whose partner cannot be read marks no plumbing`() =
        `a companion whose partner cannot be read marks no plumbing`("scala3")

    @Test
    fun `scala 2 - a companion whose partner cannot be read marks no plumbing`() =
        `a companion whose partner cannot be read marks no plumbing`("scala2")

    private fun `a module class reads its partner only when it declares a companion candidate`(module: String) {
        val lookup = fixtureLookup(module)
        val asked = mutableListOf<String>()
        val recording = { internalName: String -> lookup(internalName).also { asked += internalName } }

        ScalaGeneratedMethods.of(lookup("$PACKAGE/Driver\$")!!, recording)
        assertEquals(emptyList(), asked, "Driver\$ declares no apply, unapply, toString or fromProduct for a partner")

        ScalaGeneratedMethods.of(lookup("$PACKAGE/Cc\$")!!, recording)
        assertEquals(listOf("$PACKAGE/Cc"), asked)
    }

    @Test
    fun `scala 3 - a module class reads its partner only when it declares a companion candidate`() =
        `a module class reads its partner only when it declares a companion candidate`("scala3")

    @Test
    fun `scala 2 - a module class reads its partner only when it declares a companion candidate`() =
        `a module class reads its partner only when it declares a companion candidate`("scala2")

    private fun `a case class with an auxiliary constructor marks its plumbing and not its constructors`(module: String) {
        val written = fixtureLookup(module)("$PACKAGE/Written")!!

        val analysis = analyze(written)

        assertEquals(GeneratedBy.CASE_CLASS, analysis.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(Ljava/lang/String;)V"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(ILjava/lang/String;)V"))
    }

    @Test
    fun `scala 3 - a case class with an auxiliary constructor marks its plumbing and not its constructors`() =
        `a case class with an auxiliary constructor marks its plumbing and not its constructors`("scala3")

    @Test
    fun `scala 2 - a case class with an auxiliary constructor marks its plumbing and not its constructors`() =
        `a case class with an auxiliary constructor marks its plumbing and not its constructors`("scala2")

    /**
     * [classBytes] with a `nop` added to every method named in [names], at the start or, with
     * [beforeLast], just before the method's last instruction.
     */
    private fun withNop(
        classBytes: ByteArray,
        names: Set<String>,
        beforeLast: Boolean = false,
    ): ByteArray {
        val counts = mutableMapOf<String, Int>()
        if (beforeLast) {
            val counter =
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor = InstructionHook(null) { counts.merge(name + descriptor, 1, Int::plus) }
                }
            ClassReader(classBytes).accept(counter, 0)
        }
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
                    if (name !in names) return delegate
                    if (beforeLast) {
                        val last = counts.getValue(name + descriptor) - 1
                        var seen = 0
                        return InstructionHook(delegate) { if (seen++ == last) delegate.visitInsn(Opcodes.NOP) }
                    }
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitCode() {
                            super.visitCode()
                            super.visitInsn(Opcodes.NOP)
                        }
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** Passes every call to [delegate], running [beforeInstruction] ahead of each instruction. */
    private class InstructionHook(
        delegate: MethodVisitor?,
        private val beforeInstruction: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9, delegate) {
        override fun visitInsn(opcode: Int) {
            beforeInstruction()
            super.visitInsn(opcode)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            beforeInstruction()
            super.visitIntInsn(opcode, operand)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            beforeInstruction()
            super.visitVarInsn(opcode, varIndex)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            beforeInstruction()
            super.visitTypeInsn(opcode, type)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            beforeInstruction()
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            beforeInstruction()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            beforeInstruction()
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            beforeInstruction()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitLdcInsn(value: Any?) {
            beforeInstruction()
            super.visitLdcInsn(value)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            beforeInstruction()
            super.visitIincInsn(varIndex, increment)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            beforeInstruction()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            beforeInstruction()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            beforeInstruction()
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }
    }

    private fun `one instruction outside the shape leaves a method unmarked`(module: String) {
        val lookup = fixtureLookup(module)
        val mixed = lookup("$PACKAGE/Mixed")!!
        val changed = setOf("equals", "hashCode", "productElement", "copy")

        val original = analyze(mixed, lookup)
        val withExtra = analyze(withNop(mixed, changed), lookup)
        val withExtraAtEnd = analyze(withNop(mixed, changed, beforeLast = true), lookup)

        val descriptors =
            mapOf(
                "equals" to "(Ljava/lang/Object;)Z",
                "hashCode" to "()I",
                "productElement" to "(I)Ljava/lang/Object;",
                "copy" to "(JDZLjava/lang/String;IFCBSLjava/lang/Object;Lscala/Option;)L$PACKAGE/Mixed;",
            )
        for ((name, descriptor) in descriptors) {
            assertEquals(GeneratedBy.CASE_CLASS, original.generatedBy(name, descriptor), name)
            assertEquals(GeneratedBy.NONE, withExtra.generatedBy(name, descriptor), "$name with a nop")
            assertEquals(GeneratedBy.NONE, withExtraAtEnd.generatedBy(name, descriptor), "$name with a nop before its last instruction")
        }
        assertEquals(GeneratedBy.CASE_CLASS, withExtra.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.CASE_CLASS, withExtra.generatedBy("productElementName", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.CASE_CLASS, withExtraAtEnd.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
    }

    @Test
    fun `scala 3 - one instruction outside the shape leaves a method unmarked`() =
        `one instruction outside the shape leaves a method unmarked`("scala3")

    @Test
    fun `scala 2 - one instruction outside the shape leaves a method unmarked`() =
        `one instruction outside the shape leaves a method unmarked`("scala2")

    private fun `an equals of scalac's shape comparing only some elements is not marked`(module: String) {
        val lookup = fixtureLookup(module)

        val analysis = analyze(lookup("$PACKAGE/E3")!!, lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("equals", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.CASE_CLASS, analysis.generatedBy("hashCode", "()I"))
    }

    @Test
    fun `scala 3 - an equals of scalac's shape comparing only some elements is not marked`() =
        `an equals of scalac's shape comparing only some elements is not marked`("scala3")

    @Test
    fun `scala 2 - an equals of scalac's shape comparing only some elements is not marked`() =
        `an equals of scalac's shape comparing only some elements is not marked`("scala2")

    /**
     * [classBytes] with its Scala attributes swapped for the ones the other Scala version writes
     * on an inner class: `Scala` alone for Scala 3.3.4 ([asScala3]), `Scala` and
     * `ScalaInlineInfo` for Scala 2.13.15.
     */
    private fun relabelled(
        classBytes: ByteArray,
        asScala3: Boolean,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    super.visit(version, access, name, signature, superName, interfaces)
                    super.visitAttribute(ScalaAttribute())
                    if (!asScala3) super.visitAttribute(ScalaAttribute("ScalaInlineInfo"))
                }

                override fun visitAttribute(attribute: Attribute) {
                    if (attribute.type !in setOf("Scala", "ScalaSig", "ScalaInlineInfo", "TASTY")) super.visitAttribute(attribute)
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    private fun `a shape only the other Scala version writes is not marked`(module: String) {
        val lookup = fixtureLookup(module)
        val asScala3 = module == "scala2"

        val mixed = analyze(relabelled(lookup("$PACKAGE/Mixed")!!, asScala3), lookup)
        for ((name, descriptor) in listOf(
            "equals" to "(Ljava/lang/Object;)Z",
            "productElement" to "(I)Ljava/lang/Object;",
            "productElementName" to "(I)Ljava/lang/String;",
            "productIterator" to "()Lscala/collection/Iterator;",
        )) {
            assertEquals(GeneratedBy.NONE, mixed.generatedBy(name, descriptor), "$module Mixed.$name read as the other version")
        }
        for ((name, descriptor) in listOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productPrefix" to "()Ljava/lang/String;",
            "hashCode" to "()I",
        )) {
            assertEquals(GeneratedBy.CASE_CLASS, mixed.generatedBy(name, descriptor), "$module Mixed.$name is the same in both versions")
        }

        val companion = analyze(relabelled(lookup("$PACKAGE/Cc\$")!!, asScala3), lookup)
        val unapply = if (module == "scala3") "(L$PACKAGE/Cc;)L$PACKAGE/Cc;" else "(L$PACKAGE/Cc;)Lscala/Option;"
        assertEquals(GeneratedBy.NONE, companion.generatedBy("unapply", unapply))
        assertEquals(GeneratedBy.CASE_CLASS, companion.generatedBy("apply", "(II)L$PACKAGE/Cc;"))
        if (module == "scala3") assertEquals(GeneratedBy.NONE, companion.generatedBy("fromProduct", "(Lscala/Product;)L$PACKAGE/Cc;"))

        val empty = analyze(relabelled(lookup("$PACKAGE/Empty\$")!!, asScala3), lookup)
        assertEquals(GeneratedBy.NONE, empty.generatedBy("unapply", "(L$PACKAGE/Empty;)Z"))
    }

    @Test
    fun `scala 3 - a shape only the other Scala version writes is not marked`() =
        `a shape only the other Scala version writes is not marked`("scala3")

    @Test
    fun `scala 2 - a shape only the other Scala version writes is not marked`() =
        `a shape only the other Scala version writes is not marked`("scala2")
}
