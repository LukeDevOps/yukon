package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [BranchSiteAnalyzer]'s reference recording (ADR 0030): which constructs name an
 * out-of-scope class, where each reference lands (a probed method or the class), and that
 * attribution follows ADR 0024's call-edge rules for pass-throughs, lambda bodies and body classes.
 *
 * `com.example.library` is out of scope throughout, as are the JDK and the Kotlin standard
 * library: the analyser records every out-of-scope name, and dropping the JDK's happens later, at
 * transform time, where a classloader can say where a class lives.
 */
class ReferenceAnalyzerTest {
    private val includePackages = listOf("com.example.target", "com.example.other")

    private val lookup: (String) -> ByteArray? = { internalName ->
        val dottedName = internalName.replace('/', '.')
        listOf("build/classes/kotlin/test", "build/classes/java/test")
            .asSequence()
            .map { ClassFileLocator.ForFolder(File(it)).locate(dottedName) }
            .firstOrNull { it.isResolved }
            ?.resolve()
    }

    /** The method tier's own rule, by name: synthetic accessors and default-filling methods are never probed. */
    private val probedLikeTheMethodTier: (String, String) -> Boolean = { name, _ ->
        !name.startsWith("access$") && !name.endsWith("\$default") && name != "<clinit>"
    }

    private fun analyze(
        dottedName: String,
        includes: List<String> = includePackages,
        filter: (String, String) -> Boolean = probedLikeTheMethodTier,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(lookup(dottedName.replace('.', '/'))!!, lookup, includes, emptyList()) { n, d -> filter(n, d) }

    private fun lib(simpleName: String) = "com.example.library.Lib$$simpleName"

    private fun BranchSiteAnalyzer.Analysis.libReferencesOf(
        name: String,
        descriptor: String,
    ): Set<String> = referencesOf(name, descriptor).filter { it.startsWith("com.example.library.") }.toSet()

    private val target = "com.example.target.ReferenceTarget"

    @Test
    fun `each instruction kind names its owner or type`() {
        val analysis = analyze(target)
        val expected =
            mapOf(
                ("newInstance" to "()Ljava/lang/Object;") to setOf(lib("New")),
                ("cast" to "(Ljava/lang/Object;)Ljava/lang/Object;") to setOf(lib("Cast")),
                ("instanceOf" to "(Ljava/lang/Object;)Z") to setOf(lib("InstanceOf")),
                ("newArray" to "()Ljava/lang/Object;") to setOf(lib("ArrayElement")),
                ("multiArray" to "()Ljava/lang/Object;") to setOf(lib("MultiArray")),
                ("classLiteral" to "()Ljava/lang/Object;") to setOf(lib("Literal")),
                ("arrayClassLiteral" to "()Ljava/lang/Object;") to setOf(lib("ArrayLiteral")),
                ("staticCall" to "()I") to setOf(lib("StaticOwner")),
                ("staticField" to "()I") to setOf(lib("FieldOwner")),
                ("catches" to "()V") to setOf(lib("StaticOwner"), lib("Caught")),
            )
        for ((key, references) in expected) {
            assertEquals(references, analysis.libReferencesOf(key.first, key.second), "${key.first}${key.second}")
        }
    }

    @Test
    fun `a method or field instruction's descriptor types are references`() {
        val analysis = analyze(target)

        assertEquals(setOf(lib("StaticOwner"), lib("Accepted")), analysis.libReferencesOf("callDescriptor", "()V"))
        assertEquals(setOf(lib("FieldOwner"), lib("FieldTyped")), analysis.libReferencesOf("fieldDescriptor", "()Ljava/lang/Object;"))
    }

    @Test
    fun `a method's own descriptor, throws clause and generic signature are references`() {
        val analysis = analyze(target)

        assertEquals(
            setOf(lib("Returned"), lib("Param"), lib("Thrown")),
            analysis.libReferencesOf("signature", "(Lcom/example/library/Lib\$Param;)Lcom/example/library/Lib\$Returned;"),
        )
        assertEquals(
            listOf("java.util.List", lib("GenericParam")),
            analysis.referencesOf("generic", "(Ljava/util/List;)V"),
            "List<Lib.GenericParam> names both, the argument only through the signature",
        )
    }

    @Test
    fun `method and parameter annotations name their types and their enum, class and nested annotation values`() {
        val analysis = analyze(target)

        assertEquals(
            setOf(
                lib("MethodAnno"),
                lib("MethodMode"),
                lib("MethodClassValue"),
                lib("MethodNested"),
                lib("VisibleParamAnno"),
                lib("ParamClassValue"),
            ),
            analysis.libReferencesOf("annotated", "(I)V"),
            "the class-retention ParamAnno beside VisibleParamAnno is not a reference",
        )
    }

    @Test
    fun `a class-retention annotation names nothing, neither its type nor its values, in any position`() {
        val bytes = lookup("com/example/target/ReferenceTarget")!!
        val invisibleInBytes = readInvisibleAnnotationDescriptors(bytes)
        assertTrue("Lcom/example/library/Lib\$Invisible;" in invisibleInBytes, "the fixture carries the invisible annotation")
        assertTrue("Lcom/example/library/Lib\$InvisibleTypeUse;" in invisibleInBytes)
        assertTrue("Lcom/example/library/Lib\$ParamAnno;" in invisibleInBytes)

        val analysis = BranchSiteAnalyzer.analyze(bytes, lookup, includePackages, emptyList()) { _, _ -> true }
        val everywhere =
            readMethodKeys(bytes).flatMap { (name, descriptor) -> analysis.referencesOf(name, descriptor) } + analysis.classReferences

        assertEquals(emptySet(), analysis.libReferencesOf("invisiblyAnnotated", "(Ljava/lang/Object;)Ljava/lang/Object;"))
        assertEquals(emptySet(), analysis.libReferencesOf("invisibleTypeUseReturn", "()Ljava/lang/Object;"))
        val invisibleTypes = listOf("Invisible", "InvisibleMode", "InvisibleClassValue", "InvisibleNested", "InvisibleTypeUse", "ParamAnno")
        for (name in invisibleTypes.map(::lib)) assertTrue(name !in everywhere, "$name is named only by class-retention annotations")
    }

    @Test
    fun `an annotation element's default value is a class reference`() {
        val analysis = analyze("com.example.target.ReferenceAnnotationWithDefault")

        assertEquals(setOf(lib("DefaultElementValue")), analysis.classReferences.filter { it.startsWith("com.example.library.") }.toSet())
    }

    @Test
    fun `kotlinc's class-retention nullability annotations are not references`() {
        val bytes = lookup("com/example/target/ReferenceAttributionTarget")!!
        assertTrue(
            readInvisibleAnnotationDescriptors(bytes).any { it.startsWith("Lorg/jetbrains/annotations/") },
            "kotlinc marks the fixture's non-null signatures with @NotNull",
        )

        val analysis = BranchSiteAnalyzer.analyze(bytes, lookup, includePackages, emptyList()) { _, _ -> true }
        val everywhere =
            readMethodKeys(bytes).flatMap { (name, descriptor) -> analysis.referencesOf(name, descriptor) } + analysis.classReferences

        assertEquals(emptyList(), everywhere.filter { it.startsWith("org.jetbrains.annotations.") })
        assertTrue("kotlin.Metadata" in analysis.classReferences, "kotlin.Metadata is runtime-visible and still counts")
    }

    @Test
    fun `a type-use annotation on a return type and on a cast are references`() {
        val analysis = analyze(target)

        assertEquals(setOf(lib("TypeUseReturn")), analysis.libReferencesOf("typeUseReturn", "()Ljava/lang/Object;"))
        assertEquals(setOf(lib("TypeUseCast")), analysis.libReferencesOf("typeUseCast", "(Ljava/lang/Object;)Ljava/lang/Object;"))
    }

    @Test
    fun `an invokedynamic names its descriptor types and the owner of a method handle argument`() {
        val analysis = analyze(target)

        val references = analysis.referencesOf("methodReference", "()Lcom/example/library/Lib\$Producer;")
        assertTrue(lib("RefTarget") in references, "the method reference's target owner, from the bootstrap arguments")
        assertTrue(lib("Producer") in references)
        assertTrue("java.lang.invoke.LambdaMetafactory" in references, "the bootstrap handle's owner")
    }

    @Test
    fun `primitives, arrays of primitives and the class itself are not references`() {
        val analysis = analyze(target)

        assertEquals(
            emptyList(),
            analysis.referencesOf("primitivesAndSelf", "(I[JLcom/example/target/ReferenceTarget;[Lcom/example/target/ReferenceTarget;)I"),
        )
    }

    @Test
    fun `in-scope classes are not references`() {
        val analysis = analyze(target)

        assertEquals(emptyList(), analysis.referencesOf("inScope", "(Lcom/example/other/OtherTarget;)I"))
    }

    @Test
    fun `a reference is listed once per method, in the order first seen`() {
        val analysis = analyze(target)

        assertEquals(
            listOf("java.lang.Object", lib("Cast")),
            analysis.referencesOf("cast", "(Ljava/lang/Object;)Ljava/lang/Object;"),
        )
    }

    @Test
    fun `supertypes, the class annotation and its values, and field types, signatures and annotations are class references`() {
        val analysis = analyze(target)

        assertEquals(
            setOf(
                lib("Base"),
                lib("Iface"),
                lib("Marker"),
                lib("Mode"),
                lib("ClassValue"),
                lib("Nested"),
                lib("FieldAnno"),
                lib("FieldType"),
                lib("FieldGeneric"),
                lib("TypeUseField"),
            ),
            analysis.classReferences.filter { it.startsWith("com.example.library.") }.toSet(),
        )
        assertTrue("java.util.List" in analysis.classReferences, "a field's descriptor")
    }

    @Test
    fun `an abstract method's signature belongs to the class, and the concrete method beside it holds none of it`() {
        val analysis = analyze("com.example.target.AbstractReferenceTarget", filter = { name, _ -> name != "abs" })

        assertEquals(setOf(lib("AbstractReturn"), lib("AbstractParam")), analysis.classReferences.filter { it.startsWith("com.") }.toSet())
        assertEquals(emptyList(), analysis.referencesOf("concrete", "()I"))
    }

    @Test
    fun `every library type the fixture names lands on a method or on the class, and no class-level one lands on a method`() {
        val bytes = lookup("com/example/target/ReferenceTarget")!!
        val analysis = BranchSiteAnalyzer.analyze(bytes, lookup, includePackages, emptyList()) { _, _ -> true }
        val methodKeys = readMethodKeys(bytes)
        val onMethods = methodKeys.flatMap { (name, descriptor) -> analysis.referencesOf(name, descriptor) }.toSet()
        val onClass = analysis.classReferences.toSet()
        val libraryTypesNamed =
            setOf(
                "Base",
                "Iface",
                "Marker",
                "Mode",
                "ClassValue",
                "Nested",
                "FieldAnno",
                "FieldType",
                "FieldGeneric",
                "TypeUseField",
                "New",
                "Cast",
                "InstanceOf",
                "ArrayElement",
                "MultiArray",
                "Literal",
                "ArrayLiteral",
                "StaticOwner",
                "Accepted",
                "FieldOwner",
                "FieldTyped",
                "Caught",
                "Returned",
                "Param",
                "Thrown",
                "GenericParam",
                "MethodAnno",
                "MethodMode",
                "MethodClassValue",
                "MethodNested",
                "VisibleParamAnno",
                "ParamClassValue",
                "TypeUseReturn",
                "TypeUseCast",
                "Producer",
                "RefTarget",
                "LambdaBody",
            ).map(::lib).toSet()

        assertEquals(libraryTypesNamed, (onMethods + onClass).filter { it.startsWith("com.example.library.") }.toSet())
        val classOnly = setOf("Marker", "Mode", "ClassValue", "Nested", "FieldAnno", "FieldType", "FieldGeneric", "TypeUseField", "Iface")
        for (name in classOnly.map(::lib)) assertTrue(name !in onMethods, "$name is held by the class, not by a method")
    }

    @Test
    fun `with no include rules everything is in scope, so every list is empty`() {
        val analysis = analyze(target, includes = emptyList(), filter = { _, _ -> true })
        val methodKeys = readMethodKeys(lookup("com/example/target/ReferenceTarget")!!)

        assertTrue(methodKeys.all { (name, descriptor) -> analysis.referencesOf(name, descriptor).isEmpty() })
        assertEquals(emptyList(), analysis.classReferences)
    }

    @Test
    fun `a same-class default-filling method's references land on the method that calls it`() {
        val analysis = analyze("com.example.target.ReferenceAttributionTarget")

        assertEquals(setOf(lib("DefaultValue")), analysis.libReferencesOf("callsDefault", "()Ljava/lang/Object;"))
    }

    @Test
    fun `a cross-class default-filling method's references land on the method that calls it`() {
        val analysis = analyze("com.example.target.ReferenceDefaultCaller")

        assertEquals(
            setOf(lib("DefaultValue")),
            analysis.libReferencesOf("call", "(Lcom/example/target/ReferenceAttributionTarget;)Ljava/lang/Object;"),
        )
    }

    @Test
    fun `a same-class pass-through's references land on the method that reaches it`() {
        // helper stands in for a same-class accessor: excluding it from probing makes it a
        // pass-through, and its reference to the library must follow it into its caller.
        val asPassThrough =
            analyze("com.example.target.ReferenceAttributionTarget", filter = { name, descriptor ->
                name != "helper" && probedLikeTheMethodTier(name, descriptor)
            })
        val asProbed = analyze("com.example.target.ReferenceAttributionTarget")

        assertEquals(setOf(lib("Helper")), asPassThrough.libReferencesOf("callsHelper", "()Ljava/lang/Object;"))
        assertEquals(emptySet(), asProbed.libReferencesOf("callsHelper", "()Ljava/lang/Object;"))
        assertEquals(setOf(lib("Helper")), asProbed.libReferencesOf("helper", "()Ljava/lang/Object;"))
    }

    @Test
    fun `a nested class's call through kotlinc's accessor names the accessed method's return type`() {
        // The accessor's descriptor already names Lib.Secret here, so this pins the real kotlinc
        // shape, not the substitution: the hand-assembled accessor tests below do that.
        val analysis = analyze("com.example.target.ReferenceAttributionTarget\$Inner")

        assertEquals(setOf(lib("Secret")), analysis.libReferencesOf("callSecret", "()Ljava/lang/Object;"))
    }

    @Test
    fun `a cross-class accessor's body references land on the method that calls it`() {
        val (outer, nested) = assembledAccessorPair()
        val lookupWithAccessor: (String) -> ByteArray? = { if (it == ACCESSOR_OUTER) outer else lookup(it) }

        val analysis =
            BranchSiteAnalyzer.analyze(
                nested,
                lookupWithAccessor,
                includePackages,
                emptyList(),
                methodFilter = probedLikeTheMethodTier,
            )

        assertEquals(
            setOf(lib("Secret")),
            analysis.libReferencesOf("call", "()Ljava/lang/Object;"),
            "Lib.Secret appears only inside access\$000's body, never in the descriptor the call site names",
        )
    }

    @Test
    fun `a same-class accessor's body references land on the method that calls it, not on the class`() {
        val (outer, _) = assembledAccessorPair()

        val analysis = BranchSiteAnalyzer.analyze(outer, lookup, includePackages, emptyList(), methodFilter = probedLikeTheMethodTier)

        assertEquals(setOf(lib("Secret")), analysis.libReferencesOf("direct", "()Ljava/lang/Object;"))
        assertTrue(lib("Secret") !in analysis.classReferences, "a reached pass-through's references are not also the class's")
    }

    @Test
    fun `a re-kinded Scala default getter hands its references, and its pass-throughs', to the class`() {
        val bytes = assembledScalaGetterClass()

        val analysis = BranchSiteAnalyzer.analyze(bytes, lookup, includePackages, emptyList(), methodFilter = probedLikeTheMethodTier)

        assertEquals(1, analysis.scalaGetterSites.size, "the getter resolves onto f, so it carries no METHOD probe")
        assertEquals(
            setOf(lib("DefaultValue"), lib("Secret")),
            analysis.classReferences.filter { it.startsWith("com.example.library.") }.toSet(),
        )
        assertEquals(emptySet(), analysis.libReferencesOf("f", "(Ljava/lang/Object;)Ljava/lang/Object;"))
    }

    /**
     * A class shaped like scalac's output for `def f(x: Any = ...)`: `f` and its public getter
     * `f\$default\$1`, whose body constructs one library type itself and reaches another through a
     * synthetic same-class method, the pass-through shape. No Scala attribute is needed, since
     * getter resolution reads names and descriptors only.
     */
    private fun assembledScalaGetterClass(): ByteArray {
        val owner = "com/example/target/AssembledScalaGetter"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, owner, null, "java/lang/Object", null)

        fun construct(
            name: String,
            access: Int,
            descriptor: String,
            type: String,
        ) = writer.visitMethod(access, name, descriptor, null, null).apply {
            visitCode()
            visitTypeInsn(Opcodes.NEW, type)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false)
            if (name == "f\$default\$1") {
                visitInsn(Opcodes.POP)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESTATIC, owner, "access\$0", "(L$owner;)Ljava/lang/Object;", false)
            }
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "f", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 1)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        construct("f\$default\$1", Opcodes.ACC_PUBLIC, "()Ljava/lang/Object;", "com/example/library/Lib\$DefaultValue")
        construct(
            "access\$0",
            Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            "(L$owner;)Ljava/lang/Object;",
            "com/example/library/Lib\$Secret",
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    /**
     * The shape javac emits for a nested class reaching a private member of its outer class when
     * compiling for a release before nestmates (11): a static synthetic `access$000` on the outer
     * class, called from the nested class. Here the accessor's body constructs a [lib] type its
     * descriptor does not name, which no compiler's accessor does, so that the reference can only
     * reach the caller through substitution. The outer class also calls the accessor itself from
     * `direct`, the same-class shape.
     */
    private fun assembledAccessorPair(): Pair<ByteArray, ByteArray> {
        val secret = "com/example/library/Lib\$Secret"
        val outerWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        outerWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, ACCESSOR_OUTER, null, "java/lang/Object", null)
        outerWriter.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        outerWriter
            .visitMethod(Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC, "access\$000", "(L$ACCESSOR_OUTER;)Ljava/lang/Object;", null, null)
            .apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, secret)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, secret, "<init>", "()V", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
        outerWriter.visitMethod(Opcodes.ACC_PUBLIC, "direct", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, ACCESSOR_OUTER, "access\$000", "(L$ACCESSOR_OUTER;)Ljava/lang/Object;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        outerWriter.visitEnd()

        val nestedName = "$ACCESSOR_OUTER\$Nested"
        val nestedWriter = ClassWriter(ClassWriter.COMPUTE_MAXS)
        nestedWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, nestedName, null, "java/lang/Object", null)
        nestedWriter.visitField(Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC, "this\$0", "L$ACCESSOR_OUTER;", null, null).visitEnd()
        nestedWriter.visitMethod(Opcodes.ACC_PUBLIC, "call", "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, nestedName, "this\$0", "L$ACCESSOR_OUTER;")
            visitMethodInsn(Opcodes.INVOKESTATIC, ACCESSOR_OUTER, "access\$000", "(L$ACCESSOR_OUTER;)Ljava/lang/Object;", false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        nestedWriter.visitEnd()
        return outerWriter.toByteArray() to nestedWriter.toByteArray()
    }

    @Test
    fun `a pass-through no probed method reaches keeps its references on the class`() {
        val analysis = analyze("com.example.target.ReferenceAttributionTarget")

        assertTrue(
            lib("Secret") in analysis.classReferences,
            "access\$secret is only called from Inner, so its references stay with the class",
        )
    }

    @Test
    fun `a lambda body keeps its own references and its creator gains none of them`() {
        val kotlin = analyze("com.example.target.ReferenceAttributionTarget")
        assertEquals(emptySet(), kotlin.libReferencesOf("makesLambda", "()Lkotlin/jvm/functions/Function0;"))
        assertEquals(setOf(lib("LambdaBody")), kotlin.libReferencesOf("makesLambda\$lambda\$0", "()Lkotlin/Unit;"))

        val java = analyze(target)
        assertEquals(emptySet(), java.libReferencesOf("lambda", "()Ljava/lang/Runnable;"))
        assertEquals(setOf(lib("LambdaBody")), java.libReferencesOf("lambda\$lambda\$0", "()V"))
    }

    @Test
    fun `a body class keeps its own references and its creator gains none of them`() {
        val creator = analyze("com.example.target.ReferenceAttributionTarget")
        assertEquals(emptySet(), creator.libReferencesOf("makesObject", "()Ljava/lang/Runnable;"))

        val body = analyze("com.example.target.ReferenceAttributionTarget\$makesObject\$1")
        assertEquals(setOf(lib("BodyRef")), body.libReferencesOf("run", "()V"))
    }

    /** Every annotation descriptor in [bytes] held by a RuntimeInvisible*Annotations attribute, in any position. */
    private fun readInvisibleAnnotationDescriptors(bytes: ByteArray): Set<String> {
        val found = mutableSetOf<String>()
        val api = net.bytebuddy.jar.asm.Opcodes.ASM9

        fun record(
            descriptor: String,
            visible: Boolean,
        ): net.bytebuddy.jar.asm.AnnotationVisitor? {
            if (!visible) found += descriptor
            return null
        }
        net.bytebuddy.jar.asm.ClassReader(bytes).accept(
            object : net.bytebuddy.jar.asm.ClassVisitor(api) {
                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ) = record(descriptor, visible)

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ) = object : net.bytebuddy.jar.asm.FieldVisitor(api) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)

                    override fun visitTypeAnnotation(
                        typeRef: Int,
                        typePath: net.bytebuddy.jar.asm.TypePath?,
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = object : net.bytebuddy.jar.asm.MethodVisitor(api) {
                    override fun visitAnnotation(
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)

                    override fun visitParameterAnnotation(
                        parameter: Int,
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)

                    override fun visitTypeAnnotation(
                        typeRef: Int,
                        typePath: net.bytebuddy.jar.asm.TypePath?,
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)

                    override fun visitInsnAnnotation(
                        typeRef: Int,
                        typePath: net.bytebuddy.jar.asm.TypePath?,
                        descriptor: String,
                        visible: Boolean,
                    ) = record(descriptor, visible)
                }
            },
            0,
        )
        return found
    }

    private fun readMethodKeys(bytes: ByteArray): List<Pair<String, String>> {
        val keys = mutableListOf<Pair<String, String>>()
        net.bytebuddy.jar.asm.ClassReader(bytes).accept(
            object : net.bytebuddy.jar.asm.ClassVisitor(net.bytebuddy.jar.asm.Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): net.bytebuddy.jar.asm.MethodVisitor? {
                    keys += name to descriptor
                    return null
                }
            },
            net.bytebuddy.jar.asm.ClassReader.SKIP_CODE,
        )
        return keys
    }

    private companion object {
        const val ACCESSOR_OUTER = "com/example/target/AssembledAccessorOuter"
    }
}
