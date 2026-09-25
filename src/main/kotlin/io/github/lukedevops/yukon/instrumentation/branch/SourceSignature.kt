package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/**
 * What a method's class file says about its signature beyond the descriptor. A consumer uses it
 * to write the signature as the source did. See ADR 0043.
 *
 * [parameterNames] holds one name per descriptor parameter, in order, or is empty. See
 * [ParameterNameReader] for where the names come from.
 *
 * [genericSignature] is the method's `Signature` attribute as the class file writes it, or empty
 * when the method has none.
 *
 * [extensionReceiver] is true when the first name is a Kotlin extension receiver's. kotlinc names
 * that parameter `$this$<function>`, and older kotlinc named it `$receiver`.
 */
data class SourceSignature(
    val parameterNames: List<String>,
    val genericSignature: String,
    val extensionReceiver: Boolean,
) {
    companion object {
        /** No names, no generic signature and no receiver. A `<clinit>` and an unread class get this. */
        val NONE = SourceSignature(emptyList(), "", false)

        /** Builds the facts from the chosen names and the method's `Signature` attribute, if any. */
        fun of(
            parameterNames: List<String>,
            signature: String?,
        ): SourceSignature {
            val first = parameterNames.firstOrNull()
            val receiver = first != null && (first.startsWith("\$this\$") || first == "\$receiver")
            return SourceSignature(parameterNames, signature ?: "", receiver)
        }

        /**
         * Picks a method's parameter names. [methodParameters] holds the `MethodParameters`
         * attribute's names, a null for an unnamed entry, or is null when the method has no such
         * attribute. It wins when it names every descriptor parameter. Otherwise each parameter's
         * name is [namesBySlot]'s entry for the slot the parameter occupies. An instance method's
         * `this` takes slot 0, and a `long` or `double` takes two slots. When any slot has no
         * name, the result is empty, so a list is never partial.
         */
        fun pickNames(
            descriptor: String,
            isStatic: Boolean,
            methodParameters: List<String?>?,
            namesBySlot: Map<Int, String>,
        ): List<String> {
            val argumentTypes = Type.getArgumentTypes(descriptor)
            if (argumentTypes.isEmpty()) return emptyList()
            if (methodParameters != null && methodParameters.size == argumentTypes.size && methodParameters.all { !it.isNullOrEmpty() }) {
                return methodParameters.map { it!! }
            }
            var slot = if (isStatic) 0 else 1
            val names = ArrayList<String>(argumentTypes.size)
            for (type in argumentTypes) {
                names += namesBySlot[slot] ?: return emptyList()
                slot += type.size
            }
            return names
        }
    }
}

/**
 * Reads the parameter names of one method while passing every event on to [delegate]. It hands
 * the chosen names to [onEnd] from [visitEnd]. See [SourceSignature.pickNames].
 *
 * A LocalVariableTable entry counts only when its range starts at the method's first
 * instruction. A compiler can reuse a slot for a later local, and that entry names the local, not
 * the parameter. The first instruction's label is the one label [visitLabel] sees before any
 * instruction.
 */
internal class ParameterNameReader(
    access: Int,
    private val descriptor: String,
    delegate: MethodVisitor?,
    private val onEnd: (List<String>) -> Unit,
) : MethodVisitor(Opcodes.ASM9, delegate) {
    private val isStatic = access and Opcodes.ACC_STATIC != 0
    private var methodParameters: MutableList<String?>? = null
    private var startLabel: Label? = null
    private var instructionSeen = false
    private val namesBySlot = mutableMapOf<Int, String>()

    override fun visitParameter(
        name: String?,
        access: Int,
    ) {
        (methodParameters ?: mutableListOf<String?>().also { methodParameters = it }) += name
        super.visitParameter(name, access)
    }

    override fun visitLabel(label: Label) {
        if (!instructionSeen && startLabel == null) startLabel = label
        super.visitLabel(label)
    }

    override fun visitLocalVariable(
        name: String,
        descriptor: String,
        signature: String?,
        start: Label,
        end: Label,
        index: Int,
    ) {
        if (start === startLabel) namesBySlot.putIfAbsent(index, name)
        super.visitLocalVariable(name, descriptor, signature, start, end, index)
    }

    override fun visitEnd() {
        onEnd(SourceSignature.pickNames(descriptor, isStatic, methodParameters, namesBySlot))
        super.visitEnd()
    }

    override fun visitInsn(opcode: Int) {
        instructionSeen = true
        super.visitInsn(opcode)
    }

    override fun visitIntInsn(
        opcode: Int,
        operand: Int,
    ) {
        instructionSeen = true
        super.visitIntInsn(opcode, operand)
    }

    override fun visitVarInsn(
        opcode: Int,
        varIndex: Int,
    ) {
        instructionSeen = true
        super.visitVarInsn(opcode, varIndex)
    }

    override fun visitTypeInsn(
        opcode: Int,
        type: String,
    ) {
        instructionSeen = true
        super.visitTypeInsn(opcode, type)
    }

    override fun visitFieldInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
    ) {
        instructionSeen = true
        super.visitFieldInsn(opcode, owner, name, descriptor)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        instructionSeen = true
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitInvokeDynamicInsn(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Handle,
        vararg bootstrapMethodArguments: Any,
    ) {
        instructionSeen = true
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
    }

    override fun visitJumpInsn(
        opcode: Int,
        label: Label,
    ) {
        instructionSeen = true
        super.visitJumpInsn(opcode, label)
    }

    override fun visitLdcInsn(value: Any) {
        instructionSeen = true
        super.visitLdcInsn(value)
    }

    override fun visitIincInsn(
        varIndex: Int,
        increment: Int,
    ) {
        instructionSeen = true
        super.visitIincInsn(varIndex, increment)
    }

    override fun visitTableSwitchInsn(
        min: Int,
        max: Int,
        dflt: Label,
        vararg labels: Label,
    ) {
        instructionSeen = true
        super.visitTableSwitchInsn(min, max, dflt, *labels)
    }

    override fun visitLookupSwitchInsn(
        dflt: Label,
        keys: IntArray,
        labels: Array<out Label>,
    ) {
        instructionSeen = true
        super.visitLookupSwitchInsn(dflt, keys, labels)
    }

    override fun visitMultiANewArrayInsn(
        descriptor: String,
        numDimensions: Int,
    ) {
        instructionSeen = true
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
    }
}
