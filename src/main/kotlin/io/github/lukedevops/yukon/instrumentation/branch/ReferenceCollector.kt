package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ConstantDynamic
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor

/**
 * Adds every class a piece of bytecode names to [names], as internal names, in the order first
 * seen. Array types reduce to their element type, a method type to its parameter and return types,
 * and primitives are ignored. Nothing here filters by scope: [BranchSiteAnalyzer] does that once it
 * knows which method or class a name belongs to. See ADR 0030.
 */
internal class ReferenceCollector(
    private val names: MutableSet<String>,
) {
    /** An internal name as an instruction's owner or type operand spells it, which is an array descriptor for an array type. */
    fun internalName(name: String?) {
        if (name == null) return
        if (name.startsWith("[")) descriptor(name) else names += name
    }

    /** A field or method descriptor. */
    fun descriptor(descriptor: String?) {
        if (descriptor == null) return
        type(Type.getType(descriptor))
    }

    /** A type: an object type by its internal name, an array by its element type, a method type by its parameters and return. */
    fun type(type: Type) {
        when (type.sort) {
            Type.ARRAY -> {
                type(type.elementType)
            }

            Type.OBJECT -> {
                names += type.internalName
            }

            Type.METHOD -> {
                type.argumentTypes.forEach(::type)
                type(type.returnType)
            }
        }
    }

    /**
     * A generic signature, so `List<JsonNode>` names `JsonNode` as well as `List`. A malformed
     * signature adds what was read before the fault: a signature is optional metadata the JVM
     * never checks, and one bad attribute must not fail the class's transform.
     */
    fun signature(signature: String?) {
        if (signature == null) return
        try {
            SignatureReader(signature).accept(SignatureNames())
        } catch (_: RuntimeException) {
            // Keep whatever the reader reached.
        }
    }

    /** An `ldc` operand or a bootstrap argument. */
    fun constant(value: Any?) {
        when (value) {
            is Type -> {
                type(value)
            }

            is Handle -> {
                handle(value)
            }

            is ConstantDynamic -> {
                descriptor(value.descriptor)
                handle(value.bootstrapMethod)
                for (index in 0 until value.bootstrapMethodArgumentCount) constant(value.getBootstrapMethodArgument(index))
            }
        }
    }

    /** A method or field handle: its owner and the types in its descriptor. */
    fun handle(handle: Handle) {
        internalName(handle.owner)
        descriptor(handle.desc)
    }

    /**
     * An annotation's own type, returning a visitor that records the types its values name, when
     * [visible] says the annotation is runtime-visible. An invisible one (class retention, such as
     * kotlinc's `@NotNull`) records nothing and returns null, values included: the JVM never
     * resolves its types, so its jar can leave the runtime classpath with nothing changing.
     */
    fun annotation(
        descriptor: String,
        visible: Boolean,
    ): AnnotationVisitor? {
        if (!visible) return null
        descriptor(descriptor)
        return AnnotationValues()
    }

    /**
     * An annotation interface element's default value. It carries no visibility flag of its own
     * and is read at runtime by reflection on the annotation type, so its values always count.
     */
    fun annotationDefault(): AnnotationVisitor = AnnotationValues()

    /**
     * Enum constants name their enum type, class values their class, and nested annotations their
     * annotation type; strings and primitives name nothing. A nested annotation shares the
     * visibility of the annotation holding it, so it always counts here.
     */
    private inner class AnnotationValues : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(
            name: String?,
            value: Any?,
        ) {
            if (value is Type) type(value)
        }

        override fun visitEnum(
            name: String?,
            descriptor: String?,
            value: String?,
        ) {
            descriptor(descriptor)
        }

        override fun visitAnnotation(
            name: String?,
            descriptor: String?,
        ): AnnotationVisitor? = descriptor?.let { annotation(it, visible = true) }

        override fun visitArray(name: String?): AnnotationVisitor = this
    }

    /**
     * Records every class type a signature names. An inner class type arrives as its outer type
     * followed by its simple name, so each class type's name is kept on a stack until its
     * [visitEnd] to build `Outer$Inner`.
     */
    private inner class SignatureNames : SignatureVisitor(Opcodes.ASM9) {
        private val open = ArrayDeque<String>()

        override fun visitClassType(name: String) {
            open.addLast(name)
            names += name
        }

        override fun visitInnerClassType(name: String) {
            val outer = open.removeLastOrNull() ?: return
            val inner = "$outer$$name"
            open.addLast(inner)
            names += inner
        }

        override fun visitEnd() {
            open.removeLastOrNull()
        }
    }
}
