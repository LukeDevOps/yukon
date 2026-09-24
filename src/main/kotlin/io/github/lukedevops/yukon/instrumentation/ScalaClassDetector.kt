package io.github.lukedevops.yukon.instrumentation

import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * Detects the marker scalac writes onto every class file it compiles: a `Scala` class attribute
 * for Scala 3, or a `ScalaSig` class attribute for Scala 2's pickled signature. ASM has no built-in
 * handling for either name, so a class carrying one still reaches [ClassVisitor.visitAttribute] as
 * a generic, unparsed attribute; only its type name is read here.
 *
 * [TypeMatchPolicy.methodMatcher] uses this to tell a scalac lambda body (`$anonfun$...`) apart
 * from an unrelated synthetic method of the same shape on a class scalac never compiled, which
 * must stay excluded.
 */
object ScalaClassDetector {
    private val SCALA_ATTRIBUTE_TYPES = setOf("Scala", "ScalaSig")

    /** Whether [attribute], a class attribute ASM did not parse, is one scalac writes. */
    fun isScalaAttribute(attribute: Attribute): Boolean = attribute.type in SCALA_ATTRIBUTE_TYPES

    /** Whether [classBytes] carries a `Scala` or `ScalaSig` class attribute. */
    fun isScalaClass(classBytes: ByteArray): Boolean {
        var found = false
        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitAttribute(attribute: Attribute) {
                    if (isScalaAttribute(attribute)) found = true
                }
            }
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return found
    }
}
