package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceSignatureTest {
    private companion object {
        const val ASM_CLASS = "com/example/target/AsmSignature"
    }

    private fun kotlinBytes(simpleName: String): ByteArray = File("build/classes/kotlin/test/com/example/target/$simpleName.class").readBytes()

    private fun javaBytes(simpleName: String): ByteArray = File("build/classes/java/test/com/example/target/$simpleName.class").readBytes()

    private val topLevel by lazy { BranchSiteAnalyzer.analyze(kotlinBytes("SignatureTargetKt")) { _, _ -> true } }

    private val kotlinClass by lazy { BranchSiteAnalyzer.analyze(kotlinBytes("SignatureTarget")) { _, _ -> true } }

    private val javaClass by lazy { BranchSiteAnalyzer.analyze(javaBytes("SignatureJavaTarget")) { _, _ -> true } }

    @Test
    fun `a private top-level function sends its parameter names in order, with no generic signature and no receiver`() {
        val signature = topLevel.sourceSignatureOf("formatTotal", "(DLjava/lang/String;I)Ljava/lang/String;")

        assertEquals(SourceSignature(listOf("total", "currency", "decimals"), "", false), signature)
    }

    @Test
    fun `a generic function sends its Signature attribute verbatim`() {
        val signature = topLevel.sourceSignatureOf("firstOf", "(Ljava/util/List;)Ljava/lang/Object;")

        assertEquals(listOf("items"), signature.parameterNames)
        assertEquals("<T:Ljava/lang/Object;>(Ljava/util/List<+TT;>;)TT;", signature.genericSignature)
    }

    @Test
    fun `a function with no parameters sends no names and keeps its generic return type`() {
        val signature = topLevel.sourceSignatureOf("orders", "()Ljava/util/List;")

        assertEquals(SourceSignature(emptyList(), "()Ljava/util/List<Ljava/lang/String;>;", false), signature)
    }

    @Test
    fun `an extension function sends its receiver's compiler name and the receiver flag`() {
        val signature = topLevel.sourceSignatureOf("shout", "(Ljava/lang/String;)Ljava/lang/String;")

        assertEquals(listOf("\$this\$shout"), signature.parameterNames)
        assertTrue(signature.extensionReceiver)
    }

    @Test
    fun `a suspend function ends with its continuation's compiler name and keeps Continuation in its signature`() {
        val signature = topLevel.sourceSignatureOf("loadName", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;")

        assertEquals(listOf("id", "\$completion"), signature.parameterNames)
        assertEquals("(ILkotlin/coroutines/Continuation<-Ljava/lang/String;>;)Ljava/lang/Object;", signature.genericSignature)
        assertFalse(signature.extensionReceiver)
    }

    @Test
    fun `a lambda body sends its captured value's name first`() {
        val name = "prefixAll\$lambda\$0"
        val descriptor = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

        assertTrue(topLevel.isLambdaBody(name, descriptor))
        assertEquals(SourceSignature(listOf("\$prefix", "it"), "", false), topLevel.sourceSignatureOf(name, descriptor))
    }

    @Test
    fun `a long and a double each take two slots, in a static function and after this in an instance method`() {
        assertEquals(listOf("count", "ratio", "last"), topLevel.sourceSignatureOf("mix", "(JDI)Ljava/lang/String;").parameterNames)
        assertEquals(
            listOf("factor", "weight", "tag"),
            kotlinClass.sourceSignatureOf("scale", "(JDLjava/lang/String;)Ljava/lang/String;").parameterNames,
        )
    }

    @Test
    fun `an instance method and a constructor leave out this`() {
        assertEquals(listOf("label", "count"), kotlinClass.sourceSignatureOf("describe", "(Ljava/lang/String;I)Ljava/lang/String;").parameterNames)
        assertEquals(listOf("id", "rate"), kotlinClass.sourceSignatureOf("<init>", "(JD)V").parameterNames)
    }

    @Test
    fun `a Java class built with debug info sends its names, a later local in the table included`() {
        assertEquals(
            listOf("left", "count", "ratio", "last"),
            javaClass.sourceSignatureOf("join", "(Ljava/lang/String;JDI)Ljava/lang/String;").parameterNames,
        )
        assertEquals(listOf("name", "seed"), javaClass.sourceSignatureOf("<init>", "(Ljava/lang/String;J)V").parameterNames)
        assertEquals(listOf("greeting"), javaClass.sourceSignatureOf("greet", "(Ljava/lang/String;)Ljava/lang/String;").parameterNames)
    }

    @Test
    fun `a class with no LocalVariableTable sends no names but keeps its generic signatures`() {
        val writer = ClassWriter(0)
        ClassReader(kotlinBytes("SignatureTargetKt")).accept(writer, ClassReader.SKIP_DEBUG)
        val stripped = BranchSiteAnalyzer.analyze(writer.toByteArray()) { _, _ -> true }

        assertEquals(SourceSignature.NONE, stripped.sourceSignatureOf("formatTotal", "(DLjava/lang/String;I)Ljava/lang/String;"))
        assertEquals(SourceSignature.NONE, stripped.sourceSignatureOf("shout", "(Ljava/lang/String;)Ljava/lang/String;"))
        assertEquals(
            SourceSignature(emptyList(), "<T:Ljava/lang/Object;>(Ljava/util/List<+TT;>;)TT;", false),
            stripped.sourceSignatureOf("firstOf", "(Ljava/util/List;)Ljava/lang/Object;"),
        )
    }

    @Test
    fun `a Java class with its LocalVariableTable stripped sends no names`() {
        val writer = ClassWriter(0)
        ClassReader(javaBytes("SignatureJavaTarget")).accept(writer, ClassReader.SKIP_DEBUG)
        val stripped = BranchSiteAnalyzer.analyze(writer.toByteArray()) { _, _ -> true }

        assertEquals(emptyList(), stripped.sourceSignatureOf("join", "(Ljava/lang/String;JDI)Ljava/lang/String;").parameterNames)
        assertEquals(emptyList(), stripped.sourceSignatureOf("<init>", "(Ljava/lang/String;J)V").parameterNames)
    }

    @Test
    fun `the type initializer gets no source signature`() {
        val analysis = BranchSiteAnalyzer.analyze(javaBytes("StaticFlagTarget")) { _, _ -> true }

        assertTrue(analysis.hasTypeInitializer)
        assertEquals(SourceSignature.NONE, analysis.sourceSignatureOf("<clinit>", "()V"))
        assertEquals(listOf("value"), analysis.sourceSignatureOf("twice", "(I)I").parameterNames)
    }

    @Test
    fun `MethodParameters names every parameter and wins over the LocalVariableTable`() {
        val bytes =
            asmMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "(JI)V", methodParameters = listOf("count", "limit")) { start, _, end ->
                visitLocalVariable("tableCount", "J", null, start, end, 0)
                visitLocalVariable("tableLimit", "I", null, start, end, 2)
            }

        assertEquals(listOf("count", "limit"), analyzeAsm(bytes).sourceSignatureOf("m", "(JI)V").parameterNames)
    }

    @Test
    fun `MethodParameters alone names the parameters when there is no LocalVariableTable`() {
        val bytes = asmMethod(Opcodes.ACC_PUBLIC, "(Ljava/lang/String;)V", methodParameters = listOf("text")) { _, _, _ -> }

        assertEquals(listOf("text"), analyzeAsm(bytes).sourceSignatureOf("m", "(Ljava/lang/String;)V").parameterNames)
    }

    @Test
    fun `a MethodParameters entry with no name falls back to the LocalVariableTable`() {
        val bytes =
            asmMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "(II)V", methodParameters = listOf("first", null)) { start, _, end ->
                visitLocalVariable("a", "I", null, start, end, 0)
                visitLocalVariable("b", "I", null, start, end, 1)
            }

        assertEquals(listOf("a", "b"), analyzeAsm(bytes).sourceSignatureOf("m", "(II)V").parameterNames)
    }

    @Test
    fun `a slot's entry that starts after the first instruction names a later local, not the parameter`() {
        val bytes =
            asmMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "(I)V") { start, later, end ->
                visitLocalVariable("reused", "I", null, later, end, 0)
                visitLocalVariable("value", "I", null, start, end, 0)
            }

        assertEquals(listOf("value"), analyzeAsm(bytes).sourceSignatureOf("m", "(I)V").parameterNames)
    }

    @Test
    fun `a parameter the table names only from a later instruction leaves the whole list empty`() {
        val bytes =
            asmMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "(II)V") { start, later, end ->
                visitLocalVariable("first", "I", null, start, end, 0)
                visitLocalVariable("second", "I", null, later, end, 1)
            }

        assertEquals(emptyList(), analyzeAsm(bytes).sourceSignatureOf("m", "(II)V").parameterNames)
    }

    @Test
    fun `older kotlinc's receiver name counts as an extension receiver, and only in first place`() {
        assertTrue(SourceSignature.of(listOf("\$receiver", "x"), null).extensionReceiver)
        assertTrue(SourceSignature.of(listOf("\$this\$shout"), null).extensionReceiver)
        assertFalse(SourceSignature.of(listOf("\$prefix", "\$this\$apply"), null).extensionReceiver)
        assertFalse(SourceSignature.of(listOf("receiver"), null).extensionReceiver)
        assertFalse(SourceSignature.of(emptyList(), null).extensionReceiver)
    }

    private fun analyzeAsm(bytes: ByteArray) = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }

    /**
     * One class with one method `m`, whose body is two `nop`s and a `return`. [locals] writes the
     * method's LocalVariableTable entries. It gets the label at the first instruction, the label at
     * the second, and the label at the end.
     */
    private fun asmMethod(
        access: Int,
        descriptor: String,
        methodParameters: List<String?>? = null,
        locals: MethodVisitor.(start: Label, later: Label, end: Label) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, ASM_CLASS, null, "java/lang/Object", null)
        val mv = writer.visitMethod(access, "m", descriptor, null, null)
        methodParameters?.forEach { mv.visitParameter(it, 0) }
        mv.visitCode()
        val start = Label()
        val later = Label()
        val end = Label()
        mv.visitLabel(start)
        mv.visitInsn(Opcodes.NOP)
        mv.visitLabel(later)
        mv.visitInsn(Opcodes.NOP)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitLabel(end)
        mv.locals(start, later, end)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
