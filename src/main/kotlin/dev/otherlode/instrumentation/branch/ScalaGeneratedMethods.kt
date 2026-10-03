package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.instrumentation.ScalaClassDetector
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type

/**
 * The methods scalac emits from a declaration rather than from a body the adopter wrote, marked
 * from bytecode shape alone. Nothing here decodes `ScalaSig` or TASTy, and no line number is read:
 * each generated method is recognised by its body, which is fixed compiler output whatever the
 * source layout. Every shape below was read out of `javap -c -p` over the
 * `:fixtures-scala2` and `:fixtures-scala3` modules, compiled with Scala 2.13.15 and 3.3.4
 * (`Targets.scala` and `CaseShapes.scala`). Output of another Scala version is expected to miss a
 * shape here and stay unmarked, since a body that is not exactly one of these marks nothing.
 */
internal object ScalaGeneratedMethods {
    private const val MODULE_FIELD = "MODULE\$"
    private const val OUTER_FIELD = "\$outer"
    private const val OBJECT = "java/lang/Object"
    private const val STRING = "java/lang/String"
    private const val PRODUCT = "scala/Product"
    private const val ITERATOR = "Lscala/collection/Iterator;"
    private const val STATICS = "scala/runtime/Statics"
    private const val BOXES = "scala/runtime/BoxesRunTime"
    private const val RUNTIME = "scala/runtime/ScalaRunTime\$"
    private const val OUT_OF_BOUNDS = "java/lang/IndexOutOfBoundsException"
    private const val SERIALIZATION_PROXY = "scala/runtime/ModuleSerializationProxy"

    /** The seed of scalac's `hashCode` fold, `0xcafebabe`. */
    private const val HASH_SEED = -889275714

    /** Scala 3's `_1`, `_2` and on, which a case class gets for each element. */
    private val ELEMENT_ALIAS = Regex("_([1-9]\\d*)")

    /** Scala 2's accessor for a `private` or `protected` element, `a$access$0` for element 0 named `a`. */
    private val ACCESS_ACCESSOR = Regex("(.+)\\\$access\\\$(\\d+)")

    /**
     * The four `scala.Product` members every case class declares, a case object's module class
     * included, in both Scala versions: `javap` over `Cc`, `Multi`, `Round`, `Empty` and `Solo$`.
     */
    private val CASE_CLASS_SIGNATURE =
        setOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productArity" to "()I",
            "productElement" to "(I)Ljava/lang/Object;",
            "productPrefix" to "()Ljava/lang/String;",
        )

    /** The names [companionPlumbing] can mark; a class declaring none of them has no partner to read. */
    private val COMPANION_METHODS = setOf("apply", "unapply", "toString", "fromProduct")

    /** The only element types scalac 2.13.15 specialises `Tuple2` on, by descriptor. */
    private val TUPLE2_SPECIALISED = setOf("I", "J", "D", "C", "Z")

    private val WRITE_REPLACE = "writeReplace" to "()Ljava/lang/Object;"

    /**
     * The generated methods of the Scala class [classBytes], keyed by name and descriptor. The
     * caller has already found a `Scala` or `ScalaSig` attribute on the class. [lookup] reads a
     * companion's partner as bytes, never loading it. A lookup that returns null or throws marks
     * nothing on the partner's account.
     *
     * Each method gets at most one mark, tried in this order: [GeneratedBy.STATIC_FORWARDER] (see
     * [ClassShape.staticForwarders]), then [GeneratedBy.CASE_CLASS] on a case class's own plumbing
     * (see [caseClassPlumbing]), then [GeneratedBy.CASE_CLASS] on a companion's (see
     * [companionPlumbing]), then [GeneratedBy.SCALA_OBJECT] (see [ClassShape.writeReplaceMatches]).
     *
     * A class whose name ends in `$` reads its partner only when it declares an instance method
     * named `apply`, `unapply`, `toString` or `fromProduct`: without one, the companion rule has
     * nothing to mark, whatever the partner turns out to be.
     */
    fun of(
        classBytes: ByteArray,
        lookup: (internalName: String) -> ByteArray?,
    ): Map<Pair<String, String>, GeneratedBy> {
        val shape = readShape(classBytes)
        val result = mutableMapOf<Pair<String, String>, GeneratedBy>()
        for (key in shape.staticForwarders()) result.putIfAbsent(key, GeneratedBy.STATIC_FORWARDER)
        caseClassOf(shape)?.let { case -> for (key in caseClassPlumbing(case)) result.putIfAbsent(key, GeneratedBy.CASE_CLASS) }
        if (shape.internalName.endsWith("$") && declaresCompanionCandidate(shape)) {
            val partner =
                partnerName(shape).let { name ->
                    try {
                        lookup(name)?.let(::readShape)
                    } catch (_: Exception) {
                        null
                    }
                }
            partner?.let(::caseClassOf)?.takeIf { !it.isObject }?.let { case ->
                for (key in companionPlumbing(shape, case)) result.putIfAbsent(key, GeneratedBy.CASE_CLASS)
            }
        }
        if (shape.isModuleClass && shape.writeReplaceMatches()) result.putIfAbsent(WRITE_REPLACE, GeneratedBy.SCALA_OBJECT)
        return result
    }

    private fun declaresCompanionCandidate(shape: ClassShape): Boolean =
        shape.methods.any { (key, method) -> !method.isStatic && key.first in COMPANION_METHODS }

    /**
     * The class a companion [shape] pairs with. For an inner or local companion it is the one
     * class other than itself that the companion's own `InnerClasses` attribute lists beside the
     * same outer class, under the same [sourceName] and with no trailing `$`: scalac names a local
     * case class and its companion `Outer$Local$1` and `Outer$Local$2$` in Scala 2.13.15, simple
     * names `Local$1` and `Local$2$`, and `Outer$Local$1` and `Outer$Local$3$` in Scala 3.3.4,
     * simple names `Local` and `Local$`. Otherwise it is the class named like [shape] without the
     * trailing `$`, as for `Cc$` and `Cc`, or `Outer$Inner$` and `Outer$Inner`.
     */
    private fun partnerName(shape: ClassShape): String {
        val self = shape.innerClasses.firstOrNull { it.name == shape.internalName }
        if (self != null) {
            val sourceName = shape.sourceName
            shape.innerClasses
                .filter { entry ->
                    entry.name != shape.internalName &&
                        entry.outerName == self.outerName &&
                        entry.innerName?.endsWith("$") == false &&
                        sourceName(entry.name, entry) == sourceName
                }.singleOrNull()
                ?.let { return it.name }
        }
        return shape.internalName.removeSuffix("$")
    }

    /**
     * The source name scalac gives the class [internalName] in `productPrefix` and a companion's
     * `toString`: the simple name from its `InnerClasses` [entry], or the name after the package
     * for a top-level class, without the trailing `$` of an object's class. A local class, one whose
     * entry names no outer class, also loses the `$<n>` Scala 2.13.15 appends to its simple name
     * (`Local$1` and `Local$2$`, both `Local`).
     */
    private fun sourceName(
        internalName: String,
        entry: InnerClassEntry?,
    ): String {
        val simple = entry?.innerName ?: internalName.substringAfterLast('/')
        val name = if (internalName.endsWith("$")) simple.removeSuffix("$") else simple
        return if (entry != null && entry.outerName == null) name.replace(LOCAL_SUFFIX, "") else name
    }

    private val LOCAL_SUFFIX = Regex("\\$\\d+$")

    /** One entry of a class's `InnerClasses` attribute. */
    private class InnerClassEntry(
        val name: String,
        val outerName: String?,
        val innerName: String?,
    )

    /**
     * One declared method: its access flags and its instructions, [code], null for a method with
     * none. [body] is the same list, and null for a method with a try-catch block too: no shape
     * here has a handler, so only a constructor's opening stores are ever read past one.
     */
    private class MethodShape(
        val access: Int,
    ) {
        var code: List<Insn>? = null
        var hasHandler = false

        val body: List<Insn>? get() = code?.takeIf { !hasHandler }

        /**
         * Whether the method invokes a constructor of its own class on itself: it has more
         * `invokespecial <own class>.<init>` calls than `new <own class>` instructions. Every
         * auxiliary constructor does, while a primary constructor that builds another instance of
         * its own class (`Node`) pairs each such call with a `new`.
         */
        var delegatesToOwnConstructor = false

        val isStatic: Boolean get() = access and Opcodes.ACC_STATIC != 0
        val isPrivate: Boolean get() = access and Opcodes.ACC_PRIVATE != 0
    }

    /** What the marking rules ask of one class, read in one pass over its bytes. */
    private class ClassShape(
        val internalName: String,
        val isScala: Boolean,
        val isScala3: Boolean,
        val isFinal: Boolean,
        val hasOwnModuleField: Boolean,
        val outerFieldDescriptor: String?,
        val methods: Map<Pair<String, String>, MethodShape>,
        val innerClasses: List<InnerClassEntry>,
    ) {
        /** A class compiled for a Scala `object`: its name ends in `$` and it holds its own instance in `MODULE$`. */
        val isModuleClass: Boolean get() = internalName.endsWith("$") && hasOwnModuleField

        /**
         * Whether [descriptor] names this class's primary constructor: a constructor that does not
         * delegate to another of its own class, as an auxiliary constructor always does (see
         * [MethodShape.delegatesToOwnConstructor]).
         */
        fun isPrimaryConstructor(descriptor: String): Boolean = methods["<init>" to descriptor]?.delegatesToOwnConstructor == false

        /** This class's own source name; see [ScalaGeneratedMethods.sourceName]. */
        val sourceName: String get() = sourceName(internalName, innerClasses.firstOrNull { it.name == internalName })

        fun instanceMethods(): List<Pair<Pair<String, String>, List<Insn>>> =
            methods.mapNotNull { (key, method) -> method.body?.takeIf { !method.isStatic }?.let { key to it } }

        /**
         * The static forwarders: a method that is static, not synthetic, not a bridge and has a body,
         * whose instructions are exactly `getstatic <ThisClass>$.MODULE$`, each parameter loaded in
         * declaration order with the load opcode for its type, `invokevirtual <ThisClass>$.<its own
         * name and descriptor>`, and the return opcode for its return type. Both Scala versions emit
         * forwarders with flags `ACC_PUBLIC, ACC_STATIC` and that body, on an object's own class and
         * on a case class for its companion's methods.
         */
        fun staticForwarders(): Set<Pair<String, String>> {
            val excluded = Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE
            val moduleClass = "$internalName$"
            return methods.entries
                .filter { (key, method) -> method.isStatic && method.access and excluded == 0 && key.first != "<clinit>" }
                .filter { (key, method) ->
                    val (name, descriptor) = key
                    val expected =
                        buildList {
                            add(Insn.Field(Opcodes.GETSTATIC, moduleClass, MODULE_FIELD, "L$moduleClass;"))
                            addAll(parameterLoads(descriptor, firstSlot = 0))
                            add(Insn.Call(Opcodes.INVOKEVIRTUAL, moduleClass, name, descriptor))
                            add(Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)))
                        }
                    method.body == expected
                }.mapTo(mutableSetOf()) { it.key }
        }

        /**
         * Whether the class declares a private `writeReplace()Ljava/lang/Object;` whose instructions
         * are exactly `new scala/runtime/ModuleSerializationProxy`, `dup`, `ldc` of the class itself,
         * `invokespecial ModuleSerializationProxy.<init>(Ljava/lang/Class;)V` and `areturn`. Scala 3
         * gives every object that method and Scala 2 every case-class companion.
         */
        fun writeReplaceMatches(): Boolean {
            val method = methods[WRITE_REPLACE] ?: return false
            return method.isPrivate &&
                method.body ==
                listOf(
                    Insn.TypeOperand(Opcodes.NEW, SERIALIZATION_PROXY),
                    Insn.Plain(Opcodes.DUP),
                    Insn.Constant(Type.getObjectType(internalName)),
                    Insn.Call(Opcodes.INVOKESPECIAL, SERIALIZATION_PROXY, "<init>", "(Ljava/lang/Class;)V"),
                    Insn.Plain(Opcodes.ARETURN),
                )
        }
    }

    /**
     * Reads [classBytes] once for [ClassShape]. A class is Scala 3's when it carries a `TASTY`
     * attribute, or when it carries neither `ScalaSig` nor `ScalaInlineInfo`. Over the fixture
     * modules, Scala 3.3.4 writes `Scala` on every class and `TASTY` beside it on a top-level
     * one, and never `ScalaSig` or `ScalaInlineInfo`; Scala 2.13.15 writes `ScalaSig` on a
     * top-level class, `Scala` on a module, inner or local class, and `ScalaInlineInfo` on all of
     * them but an object's mirror class, and never `TASTY`. The top-level attributes alone would
     * leave an inner or local case class, which carries only `Scala` in Scala 3.3.4, undecided.
     */
    private fun readShape(classBytes: ByteArray): ClassShape {
        var internalName = ""
        var isScala = false
        var isFinal = false
        var hasTasty = false
        var hasScala2Attribute = false
        var hasOwnModuleField = false
        var outerFieldDescriptor: String? = null
        val methods = mutableMapOf<Pair<String, String>, MethodShape>()
        val innerClasses = mutableListOf<InnerClassEntry>()

        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaceNames: Array<out String>?,
                ) {
                    internalName = name
                    isFinal = access and Opcodes.ACC_FINAL != 0
                }

                override fun visitAttribute(attribute: Attribute) {
                    if (ScalaClassDetector.isScalaAttribute(attribute)) isScala = true
                    when (attribute.type) {
                        "TASTY" -> hasTasty = true
                        "ScalaSig", "ScalaInlineInfo" -> hasScala2Attribute = true
                    }
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    innerClasses += InnerClassEntry(name, outerName, innerName)
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    if (access and Opcodes.ACC_STATIC != 0 && name == MODULE_FIELD && descriptor == "L$internalName;") {
                        hasOwnModuleField = true
                    }
                    if (access and Opcodes.ACC_STATIC == 0 && name == OUTER_FIELD) outerFieldDescriptor = descriptor
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val method = MethodShape(access)
                    methods[name to descriptor] = method
                    val owner = internalName
                    return BodyRecorder { code, hasHandler ->
                        method.code = code
                        method.hasHandler = hasHandler
                        val constructions = code.orEmpty().count { it == Insn.TypeOperand(Opcodes.NEW, owner) }
                        val ownConstructorCalls =
                            code.orEmpty().count {
                                it is Insn.Call && it.opcode == Opcodes.INVOKESPECIAL && it.owner == owner &&
                                    it.name == "<init>"
                            }
                        method.delegatesToOwnConstructor = ownConstructorCalls > constructions
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val isScala3 = hasTasty || !hasScala2Attribute
        return ClassShape(internalName, isScala, isScala3, isFinal, hasOwnModuleField, outerFieldDescriptor, methods, innerClasses)
    }

    /**
     * One element of a case class: a parameter of its first parameter list, which scalac stores in
     * a field of the same name and reads back through [accessor]. Scala 2.13.15 reads a `private`
     * or `protected` element through a public `<name>$access$<index>`, and every other element, in
     * both versions, through the accessor named like the field; a private accessor is called with
     * `invokespecial` (`javap` over `Priv` and `Hidden`).
     */
    private class Element(
        val index: Int,
        val name: String,
        val type: Type,
        val accessor: String,
        private val accessorPrivate: Boolean,
        private val owner: String,
    ) {
        /** The call that reads this element from an instance already on the stack, from inside [owner]. */
        val read: Insn get() = Insn.Call(if (accessorPrivate) Opcodes.INVOKESPECIAL else Opcodes.INVOKEVIRTUAL, owner, accessor, "()$type")

        /** The same read from another class, which only a non-private accessor allows. */
        val readFromOutside: Insn? get() = if (accessorPrivate) null else read

        val isPrimitive: Boolean get() = type.sort in Type.BOOLEAN..Type.DOUBLE
    }

    /**
     * A case class, or a case object's module class: a class carrying a Scala attribute that
     * declares `canEqual(Ljava/lang/Object;)Z`, `productArity()I`,
     * `productElement(I)Ljava/lang/Object;` and `productPrefix()Ljava/lang/String;`. The interface
     * list is not consulted: Scala 2.13.15 leaves `scala.Product` out of it when a supertype already
     * brings `Product`, as for `case class Round(r: Double) extends Figure`.
     *
     * [arity] is the constant `productArity` returns, and [elements] the fields the class's
     * constructor stores its first [arity] parameters in, each with an accessor. scalac's primary
     * constructor opens with one `aload_0`, parameter load and `putfield` per stored parameter, in
     * parameter order, the first parameter list first; a second list's parameter is stored only if
     * the body uses it, and a body `val` is assigned after the super call (`javap` over `TwoLists`,
     * `Svc`, `BodyVal` and `Outer$Inner`, whose outer reference is stored after its element).
     * Element `i` must be stored from parameter `i`, counted after the outer reference of an inner
     * or local class: Scala 2.13.15 passes an element that overrides a supertype's `val` to the
     * supertype's constructor and stores none of it (`Q`), and a constructor that skips a
     * parameter gives no elements rather than a shifted list.
     * Declaration order is no substitute: Scala 2.13.15 declares a body `lazy val`'s field before
     * the elements' fields. An auxiliary constructor opens with its call to the primary one and
     * contributes nothing. The stores are read even from a constructor with a try-catch block,
     * which a `try` in the class body puts there (`TryBody`), since they come before any handler.
     * Scala 2.13.15 reads a `private` or `protected` element through `<name>$access$<index>`, a name
     * Scala 3.3.4 never gives an element's accessor, so a Scala 3 class always reads its elements
     * through the accessor named like the field. [arity] and [elements] are each null when the
     * class does not have that shape, and then no method whose body depends on the elements is
     * marked.
     */
    private class CaseClass(
        val shape: ClassShape,
    ) {
        val name: String get() = shape.internalName
        val isObject: Boolean get() = shape.isModuleClass

        val arity: Int? =
            (shape.methods["productArity" to "()I"]?.body)?.let { body ->
                val constant = body.firstOrNull() as? Insn.IntConstant
                if (constant != null && body == listOf(constant, Insn.Plain(Opcodes.IRETURN))) constant.value else null
            }

        val elements: List<Element>? =
            arity?.let { count ->
                val stored =
                    shape.methods
                        .filterKeys { it.first == "<init>" }
                        .entries
                        .mapNotNull { (key, method) ->
                            method.code
                                ?.let { storedParameters(key.second, it) }
                                ?.takeIf { fields -> fields.size >= count }
                                ?.take(count)
                        }.distinct()
                        .singleOrNull() ?: return@let null
                stored.mapIndexed { index, (fieldName, fieldDescriptor) ->
                    val descriptor = "()$fieldDescriptor"
                    val access = "$fieldName\$access\$$index"
                    val accessor =
                        when {
                            !shape.isScala3 && shape.methods.containsKey(access to descriptor) -> access
                            shape.methods.containsKey(fieldName to descriptor) -> fieldName
                            else -> return@let null
                        }
                    val private = shape.methods.getValue(accessor to descriptor).isPrivate
                    Element(index, fieldName, Type.getType(fieldDescriptor), accessor, private, name)
                }
            }

        /**
         * The fields, by name and descriptor, that the constructor [descriptor] with [body] stores
         * its parameters in before anything else: the run of `aload_0`, a load of the next
         * parameter's slot and a `putfield` of that parameter's type on this class with which it
         * opens, the first stored parameter being the first one after an outer reference. A
         * constructor whose first parameter has the type of the class's `$outer` field takes an
         * outer reference there.
         */
        private fun storedParameters(
            descriptor: String,
            body: List<Insn>,
        ): List<Pair<String, String>> {
            val parameters = Type.getArgumentTypes(descriptor)
            val first = if (parameters.firstOrNull()?.descriptor == shape.outerFieldDescriptor) 1 else 0
            var slot = 1 + parameters.take(first).sumOf { it.size }
            val stored = mutableListOf<Pair<String, String>>()
            for ((index, start) in (body.indices step 3).withIndex()) {
                val parameter = parameters.getOrNull(first + index) ?: break
                val store = body.getOrNull(start + 2) as? Insn.Field
                if (body[start] != aload(0) ||
                    body.getOrNull(start + 1) != Insn.Var(parameter.getOpcode(Opcodes.ILOAD), slot) ||
                    store == null ||
                    store.opcode != Opcodes.PUTFIELD ||
                    store.owner != name ||
                    store.descriptor != parameter.descriptor
                ) {
                    break
                }
                stored += store.name to store.descriptor
                slot += parameter.size
            }
            return stored
        }

        fun declares(
            name: String,
            descriptor: String,
        ): Boolean = shape.methods.containsKey(name to descriptor)

        /** Whether the class's `canEqual` is the one scalac writes: `aload_1; instanceof <Class>; ireturn`. */
        val hasGeneratedCanEqual: Boolean
            get() = shape.methods["canEqual" to "(L$OBJECT;)Z"]?.takeIf { !it.isStatic }?.body == generatedCanEqual(name)

        /**
         * Whether scalac may have left the `canEqual` call out of this class's `equals`. It does so
         * when the class is final and its `canEqual` is scalac's own: Scala 2.13.15's `equalsCore`
         * tests `clazz.isFinal && syntheticCanEqual`, and Scala 3.3.4's
         * `SyntheticMembers.equalsBody` keeps the call unless the class is final and declares no
         * `canEqual` of the adopter's. Bytecode shows only that a `canEqual` has scalac's body, and
         * an adopter's `o.isInstanceOf[UC]` compiles to that same body while scalac still calls it
         * (`UC`), so for a final class with such a `canEqual` the call may be present or absent. In
         * every other class it is present (`UC2`, whose `canEqual` is `false`).
         */
        val equalsMayOmitCanEqual: Boolean get() = shape.isFinal && hasGeneratedCanEqual
    }

    private fun generatedCanEqual(self: String): List<Insn> =
        listOf(aload(1), Insn.TypeOperand(Opcodes.INSTANCEOF, self), Insn.Plain(Opcodes.IRETURN))

    private fun caseClassOf(shape: ClassShape): CaseClass? =
        if (shape.isScala && CASE_CLASS_SIGNATURE.all { shape.methods[it]?.isStatic == false }) CaseClass(shape) else null

    /**
     * The plumbing methods of [case]: each instance method whose name and descriptor are one of the
     * members below and whose body is the one scalac writes for it in this class. A field
     * accessor is never one; the adopter declared it. Neither is a member the adopter overrode,
     * unless the override is instruction for instruction what scalac writes, in which case it is
     * the generated code. A shape only one Scala version writes counts only in a class of that
     * version (see [readShape]). Shapes, read from `javap -c` over both versions:
     *
     * - `canEqual`: `aload_1; instanceof <Class>; ireturn`.
     * - `productPrefix`: `ldc "<source name>"; areturn`.
     * - `productArity`: the element count; `ireturn`. Marked only when `productElement` is marked
     *   too, since the bounds of its dispatch carry the count scalac knew: a hand-written
     *   `productArity` returning fewer than the elements (`Lie2`) finds that many stored fields and
     *   would otherwise pass. A class whose elements are unknown has none.
     * - `productIterator`: `ScalaRunTime$.typedProductIterator(this)` (2.13) or `Product.productIterator$(this)` (3).
     * - `productElementNames`: `Product.productElementNames$(this)`, in both.
     * - `toString`: `ScalaRunTime$._toString(this)`; for a case object `ldc "<source name>"; areturn`.
     * - `hashCode`: see [matchesHashCode].
     * - `equals`: see [matchesEqualsScala2] and [matchesEqualsScala3].
     * - `productElement` and `productElementName`: see [matchesIndexed]. A Scala 2 case object's
     *   `productElementName` is `Product.productElementName$(this, n)` instead.
     * - `copy`: see [matchesConstruction].
     * - Scala 3's `_N()`: `aload_0`, the element's read, its return. Only in a Scala 3 class (see
     *   [readShape]), since Scala 2.13.15 never writes one.
     * - Scala 2's `<name>$access$<index>()`: `aload_0; getfield <name>`, its return. Only in a
     *   Scala 2 class, since Scala 3.3.4 never writes one.
     */
    private fun caseClassPlumbing(case: CaseClass): Set<Pair<String, String>> {
        val self = case.name
        val scala3 = case.shape.isScala3
        val sourceName = listOf(Insn.Constant(case.shape.sourceName), areturn())
        val productElementMatches =
            case.shape.methods["productElement" to "(I)L$OBJECT;"]
                ?.takeIf { !it.isStatic }
                ?.body
                ?.let { matchesIndexed(case, it, names = false) } == true
        return case.shape
            .instanceMethods()
            .filter { (key, body) ->
                val (name, descriptor) = key
                when (key) {
                    "canEqual" to "(L$OBJECT;)Z" -> {
                        body == generatedCanEqual(self)
                    }

                    "productPrefix" to "()L$STRING;" -> {
                        body == sourceName
                    }

                    "productArity" to "()I" -> {
                        case.arity != null && case.elements?.size == case.arity && productElementMatches
                    }

                    "productIterator" to "()$ITERATOR" -> {
                        if (scala3) {
                            body == productForwarder("productIterator\$", "(L$PRODUCT;)$ITERATOR")
                        } else {
                            body == runtimeCall("typedProductIterator", "(L$PRODUCT;)$ITERATOR")
                        }
                    }

                    "productElementNames" to "()$ITERATOR" -> {
                        body == productForwarder("productElementNames\$", "(L$PRODUCT;)$ITERATOR")
                    }

                    "toString" to "()L$STRING;" -> {
                        if (case.isObject) body == sourceName else body == runtimeCall("_toString", "(L$PRODUCT;)L$STRING;")
                    }

                    "hashCode" to "()I" -> {
                        matchesHashCode(case, body)
                    }

                    "equals" to "(L$OBJECT;)Z" -> {
                        !case.isObject && if (scala3) matchesEqualsScala3(case, body) else matchesEqualsScala2(case, body)
                    }

                    "productElement" to "(I)L$OBJECT;" -> {
                        productElementMatches
                    }

                    "productElementName" to "(I)L$STRING;" -> {
                        matchesIndexed(case, body, names = true) ||
                            (
                                !scala3 &&
                                    case.isObject &&
                                    body == productForwarder("productElementName\$", "(L$PRODUCT;I)L$STRING;", withIndex = true)
                            )
                    }

                    else -> {
                        when {
                            name == "copy" && Type.getReturnType(descriptor).descriptor == "L$self;" -> {
                                !case.isObject &&
                                    matchesConstruction(body, self, descriptor, case.shape, outerOwner = self, outerAccessor = true)
                            }

                            ELEMENT_ALIAS.matches(name) -> {
                                case.shape.isScala3 && matchesElementAlias(case, name, descriptor, body)
                            }

                            ACCESS_ACCESSOR.matches(name) -> {
                                !case.shape.isScala3 && matchesAccessAccessor(case, name, descriptor, body)
                            }

                            else -> {
                                false
                            }
                        }
                    }
                }
            }.mapTo(mutableSetOf()) { it.first }
    }

    /** `getstatic ScalaRunTime$.MODULE$; aload_0; invokevirtual ScalaRunTime$.<name>`, and the return for its result. */
    private fun runtimeCall(
        name: String,
        descriptor: String,
    ): List<Insn> =
        listOf(
            Insn.Field(Opcodes.GETSTATIC, RUNTIME, MODULE_FIELD, "L$RUNTIME;"),
            aload(0),
            Insn.Call(Opcodes.INVOKEVIRTUAL, RUNTIME, name, descriptor),
            Insn.Plain(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN)),
        )

    /** A mixin forwarder to `scala.Product`'s static `<name>`, passing `this` and, [withIndex], the `int` argument. */
    private fun productForwarder(
        name: String,
        descriptor: String,
        withIndex: Boolean = false,
    ): List<Insn> =
        listOfNotNull(
            aload(0),
            if (withIndex) Insn.Var(Opcodes.ILOAD, 1) else null,
            Insn.Call(Opcodes.INVOKESTATIC, PRODUCT, name, descriptor),
            areturn(),
        )

    /** Scala 3's `_N()`: `aload_0`, a read of element `N - 1`, and the return for its type. */
    private fun matchesElementAlias(
        case: CaseClass,
        name: String,
        descriptor: String,
        body: List<Insn>,
    ): Boolean {
        val index =
            ELEMENT_ALIAS
                .matchEntire(name)!!
                .groupValues[1]
                .toIntOrNull()
                ?.minus(1) ?: return false
        val element = case.elements?.getOrNull(index) ?: return false
        return descriptor == "()${element.type}" &&
            body == listOf(aload(0), element.read, Insn.Plain(element.type.getOpcode(Opcodes.IRETURN)))
    }

    /** Scala 2's `<name>$access$<index>()`: `aload_0; getfield <name>` and the return for its type. */
    private fun matchesAccessAccessor(
        case: CaseClass,
        name: String,
        descriptor: String,
        body: List<Insn>,
    ): Boolean {
        val groups = ACCESS_ACCESSOR.matchEntire(name)!!.groupValues
        val element = case.elements?.getOrNull(groups[2].toIntOrNull() ?: return false) ?: return false
        return element.name == groups[1] &&
            element.accessor == name &&
            descriptor == "()${element.type}" &&
            body ==
            listOf(
                aload(0),
                Insn.Field(Opcodes.GETFIELD, case.name, element.name, element.type.descriptor),
                Insn.Plain(element.type.getOpcode(Opcodes.IRETURN)),
            )
    }

    /**
     * Whether [body] is `new <target>; dup`, optionally the outer reference, every parameter of
     * [descriptor] loaded in order from slot 1, `invokespecial <target>.<init>` taking the outer
     * reference's type and those parameters, and `areturn`; and that constructor is [targetShape]'s
     * primary one (see [ClassShape.isPrimaryConstructor]), so that a hand-written `copy` or
     * `apply` that builds through an auxiliary constructor is left alone. This is a case class's
     * `copy` and a companion's `apply` in both versions. The
     * outer reference of an inner or local case class is `aload_0; getfield <outerOwner>.$outer`,
     * or, in Scala 2.13.15's `copy` of an inner class, `aload_0; invokevirtual <outerOwner>.<...>$$outer()`
     * when [outerAccessor] allows it (`javap` over `Outer$Inner` and the local `Local`).
     */
    private fun matchesConstruction(
        body: List<Insn>,
        target: String,
        descriptor: String,
        targetShape: ClassShape,
        outerOwner: String,
        outerAccessor: Boolean,
    ): Boolean {
        val m = Match(body)
        m.step(Insn.TypeOperand(Opcodes.NEW, target))
        m.step(Insn.Plain(Opcodes.DUP))
        val outer = m.optional { outerReference(outerOwner, outerAccessor) }
        for (load in parameterLoads(descriptor, firstSlot = 1)) m.step(load)
        val constructor = "(${outer.orEmpty()}${parameterPart(descriptor)})V"
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, target, "<init>", constructor))
        m.step(areturn())
        return m.matched && targetShape.isPrimaryConstructor(constructor)
    }

    /**
     * Reads an inner class's outer reference from `this`: `aload_0` then `getfield
     * <owner>.$outer`, or, when [accessor] allows it, `invokevirtual <owner>.<name>()` for a method
     * whose name ends in `$$outer`. Returns the reference's descriptor.
     */
    private fun Match.outerReference(
        owner: String,
        accessor: Boolean,
    ): String? {
        step(aload(0))
        return take { insn ->
            when {
                insn is Insn.Field && insn.opcode == Opcodes.GETFIELD && insn.owner == owner && insn.name == OUTER_FIELD -> insn.descriptor
                accessor && insn is Insn.Call && insn.isOuterAccessor(owner) -> Type.getReturnType(insn.descriptor).descriptor
                else -> null
            }
        }
    }

    private fun Insn.Call.isOuterAccessor(owner: String): Boolean =
        opcode == Opcodes.INVOKEVIRTUAL && this.owner == owner && name.endsWith("\$\$outer") && descriptor.startsWith("()L")

    /**
     * `hashCode`, in three shapes read from both versions. A case object's is `ldc <hash of its
     * source name>; ireturn` (`Solo$`: 2582783, `"Solo".hashCode()`). A case class with no element
     * of a primitive type, `Empty` and `One` among them, has `ScalaRunTime$._hashCode(this)`. Any
     * other case class has scalac's fold: seed `0xcafebabe`, `Statics.mix` with
     * `productPrefix().hashCode()`, one `Statics.mix` per element in order, and
     * `Statics.finalizeHash(acc, <arity>)`. Each element is read through its accessor and hashed
     * with `Statics.longHash`, `doubleHash` or `floatHash` for `long`, `double` and `float`,
     * `anyHash` for a reference, `1231` or `1237` for a `boolean`, and as it is for `int`, `char`,
     * `byte` and `short` (`javap` over `Mixed`, `Hidden` and `Priv`).
     */
    private fun matchesHashCode(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean {
        if (case.isObject) return body == listOf(Insn.IntConstant(case.shape.sourceName.hashCode()), Insn.Plain(Opcodes.IRETURN))
        val elements = case.elements ?: return false
        if (elements.none { it.isPrimitive }) {
            return body == runtimeCall("_hashCode", "(L$PRODUCT;)I")
        }
        val mix = Insn.Call(Opcodes.INVOKESTATIC, STATICS, "mix", "(II)I")
        val m = Match(body, firstLocal = 1)
        m.step(Insn.IntConstant(HASH_SEED))
        m.store(Opcodes.ISTORE, "acc")
        m.load(Opcodes.ILOAD, "acc")
        m.step(aload(0))
        m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, case.name, "productPrefix", "()L$STRING;"))
        m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, STRING, "hashCode", "()I"))
        m.step(mix)
        m.store(Opcodes.ISTORE, "acc")
        for (element in elements) {
            m.load(Opcodes.ILOAD, "acc")
            m.step(aload(0))
            m.step(element.read)
            when (element.type.sort) {
                Type.BOOLEAN -> {
                    m.jump(Opcodes.IFEQ, "false${element.index}")
                    m.step(Insn.IntConstant(1231))
                    m.jump(Opcodes.GOTO, "mix${element.index}")
                    m.label("false${element.index}")
                    m.step(Insn.IntConstant(1237))
                    m.label("mix${element.index}")
                }

                Type.INT, Type.CHAR, Type.BYTE, Type.SHORT -> {
                    Unit
                }

                Type.LONG -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "longHash", "(J)I"))
                }

                Type.DOUBLE -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "doubleHash", "(D)I"))
                }

                Type.FLOAT -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "floatHash", "(F)I"))
                }

                Type.OBJECT, Type.ARRAY -> {
                    m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "anyHash", "(L$OBJECT;)I"))
                }

                else -> {
                    return false
                }
            }
            m.step(mix)
            m.store(Opcodes.ISTORE, "acc")
        }
        m.load(Opcodes.ILOAD, "acc")
        m.step(Insn.IntConstant(elements.size))
        m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "finalizeHash", "(II)I"))
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /**
     * The element comparisons of `equals`, the same in both versions: every element of a primitive
     * type first, then every other one, each in element order, each read through its accessor
     * from `this` and from the other instance in slot [other] (`javap` over `Mixed`, whose `String`
     * element comes before its `int` in the source and after it here). A mismatch jumps to [fail]:
     * `if_icmpne` for `int`, `boolean`, `char`, `byte` and `short`; `lcmp`, `dcmpl` or `fcmpl` then
     * `ifne` for `long`, `double` and `float`; for a reference, either `BoxesRunTime.equals` then
     * `ifeq`, which scalac writes for a generic element, or the null-safe `Object.equals` it writes
     * for `String` and `Option` elements.
     */
    private fun Match.compareElements(
        elements: List<Element>,
        other: String,
        fail: String,
    ) {
        for (element in elements.filter { it.isPrimitive } + elements.filterNot { it.isPrimitive }) {
            val i = element.index
            step(aload(0))
            step(element.read)
            load(Opcodes.ALOAD, other)
            step(element.read)
            when (element.type.sort) {
                Type.INT, Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT -> {
                    jump(Opcodes.IF_ICMPNE, fail)
                }

                Type.LONG, Type.DOUBLE, Type.FLOAT -> {
                    step(
                        Insn.Plain(
                            when (element.type.sort) {
                                Type.LONG -> Opcodes.LCMP
                                Type.DOUBLE -> Opcodes.DCMPL
                                else -> Opcodes.FCMPL
                            },
                        ),
                    )
                    jump(Opcodes.IFNE, fail)
                }

                Type.OBJECT, Type.ARRAY -> {
                    either(
                        {
                            step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "equals", "(L$OBJECT;L$OBJECT;)Z"))
                            jump(Opcodes.IFEQ, fail)
                        },
                        {
                            store(Opcodes.ASTORE, "that$i")
                            step(Insn.Plain(Opcodes.DUP))
                            jump(Opcodes.IFNONNULL, "nonNull$i")
                            step(Insn.Plain(Opcodes.POP))
                            load(Opcodes.ALOAD, "that$i")
                            jump(Opcodes.IFNULL, "next$i")
                            jump(Opcodes.GOTO, fail)
                            label("nonNull$i")
                            load(Opcodes.ALOAD, "that$i")
                            step(Insn.Call(Opcodes.INVOKEVIRTUAL, OBJECT, "equals", "(L$OBJECT;)Z"))
                            jump(Opcodes.IFEQ, fail)
                            label("next$i")
                        },
                    )
                }

                else -> {
                    fail()
                }
            }
        }
    }

    /**
     * Scala 2.13.15's `equals`, read from `javap -c` over `Mixed`, `One`, `Priv`, `Empty`,
     * `Outer$Inner` and the final `FT`, `F1`, `FE`, `FinalOuter$FIn` and `FinalHolder$FObj`:
     *
     * ```
     * aload_0; aload_1; if_acmpeq TRUE          (absent with no elements)
     * aload_1; astore a; aload a; instanceof X; ifeq NO
     * [aload a; checkcast X; <outer>; aload_0; <outer>; if_acmpne NO]   (an inner class)
     * [iconst_1; ifeq NO]                       (an inner class, when X is final)
     * iconst_1; goto TEST
     * NO: goto NO2
     * NO2: iconst_0; goto TEST
     * TEST: ifeq FALSE
     * aload_1; checkcast X; astore b            (with no elements: aload_1; checkcast X, and no b)
     * <comparisons against b, failing to FALSE>
     * aload b; aload_0; invokevirtual canEqual; ifeq FALSE   (may be absent; see [CaseClass.equalsMayOmitCanEqual])
     * TRUE: iconst_1; goto END
     * FALSE: iconst_0
     * END: ireturn
     * ```
     *
     * A final class with no elements has its own shape instead, whatever its `canEqual`
     * (`FE`, and `FZC`, which declares its own): `aload_1; astore a; aload a; instanceof X;
     * ifeq NO; [iconst_1; ifeq NO]; iconst_1; ireturn; NO: goto NO2; NO2: iconst_0; ireturn`, the
     * bracketed part only for an inner class (`FinalOuter$FInEmpty`).
     */
    private fun matchesEqualsScala2(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = case.elements ?: return false
        val self = case.name
        val final = case.shape.isFinal
        val m = Match(body)
        if (final && elements.isEmpty()) {
            m.step(aload(1))
            m.store(Opcodes.ASTORE, "a")
            m.load(Opcodes.ALOAD, "a")
            m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
            m.jump(Opcodes.IFEQ, "no")
            if (case.shape.outerFieldDescriptor != null) {
                m.optional {
                    step(Insn.IntConstant(1))
                    jump(Opcodes.IFEQ, "no")
                }
            }
            m.step(Insn.IntConstant(1))
            m.step(Insn.Plain(Opcodes.IRETURN))
            m.label("no")
            m.jump(Opcodes.GOTO, "no2")
            m.label("no2")
            m.step(Insn.IntConstant(0))
            m.step(Insn.Plain(Opcodes.IRETURN))
            return m.matched
        }
        if (elements.isNotEmpty()) {
            m.step(aload(0))
            m.step(aload(1))
            m.jump(Opcodes.IF_ACMPEQ, "true")
        }
        m.step(aload(1))
        m.store(Opcodes.ASTORE, "a")
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
        m.jump(Opcodes.IFEQ, "no")
        if (final) {
            m.optional {
                step(Insn.IntConstant(1))
                jump(Opcodes.IFEQ, "no")
            }
        } else {
            m.optional {
                load(Opcodes.ALOAD, "a")
                step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
                take { insn -> (insn as? Insn.Call)?.takeIf { it.isOuterAccessor(self) } }?.let { accessor ->
                    step(aload(0))
                    step(accessor)
                }
                jump(Opcodes.IF_ACMPNE, "no")
            }
        }
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "test")
        m.label("no")
        m.jump(Opcodes.GOTO, "no2")
        m.label("no2")
        m.step(Insn.IntConstant(0))
        m.jump(Opcodes.GOTO, "test")
        m.label("test")
        m.jump(Opcodes.IFEQ, "false")
        m.step(aload(1))
        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
        if (elements.isNotEmpty()) {
            m.store(Opcodes.ASTORE, "b")
            m.compareElements(elements, "b", "false")
        }
        val canEqualCall: Match.() -> Unit = {
            if (elements.isNotEmpty()) load(Opcodes.ALOAD, "b")
            step(aload(0))
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, self, "canEqual", "(L$OBJECT;)Z"))
            jump(Opcodes.IFEQ, "false")
        }
        if (case.equalsMayOmitCanEqual) m.optional(canEqualCall) else m.canEqualCall()
        m.label("true")
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "end")
        m.label("false")
        m.step(Insn.IntConstant(0))
        m.label("end")
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /**
     * Scala 3.3.4's `equals`, read from `javap -c` over `Mixed`, `One`, `Priv`, `Empty`,
     * `Outer$Inner` and the final `FT`, `F1`, `FE`, `FinalOuter$FIn` and `FinalHolder$FObj`:
     *
     * ```
     * aload_0; aload_1; if_acmpeq TRUE
     * aload_1; astore a; aload a; instanceof X; ifeq NO
     * [aload a; checkcast X; invokevirtual <...>$$outer; aload_0; getfield $outer; if_acmpne NO]   (an inner class)
     * aload a; checkcast X; astore b
     * with no elements:  aload b; aload_0; invokevirtual canEqual; goto TEST
     *                    (or iconst_1; goto TEST, see [CaseClass.equalsMayOmitCanEqual])
     * otherwise:         <comparisons against b, failing to MISS>
     *                    aload b; aload_0; invokevirtual canEqual; ifeq MISS   (may be absent likewise)
     *                    iconst_1; goto JOIN; MISS: iconst_0; JOIN: goto TEST
     * NO: iconst_0; goto TEST
     * TEST: ifeq FALSE
     * TRUE: iconst_1; goto END
     * FALSE: iconst_0
     * END: ireturn
     * ```
     */
    private fun matchesEqualsScala3(
        case: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = case.elements ?: return false
        val self = case.name
        val mayOmitCanEqual = case.equalsMayOmitCanEqual
        val m = Match(body)
        m.step(aload(0))
        m.step(aload(1))
        m.jump(Opcodes.IF_ACMPEQ, "true")
        m.step(aload(1))
        m.store(Opcodes.ASTORE, "a")
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.INSTANCEOF, self))
        m.jump(Opcodes.IFEQ, "no")
        m.optional {
            load(Opcodes.ALOAD, "a")
            step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
            take { insn -> (insn as? Insn.Call)?.takeIf { it.isOuterAccessor(self) } }
            outerReference(self, accessor = false)
            jump(Opcodes.IF_ACMPNE, "no")
        }
        m.load(Opcodes.ALOAD, "a")
        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, self))
        m.store(Opcodes.ASTORE, "b")
        val canEqualCall: Match.() -> Unit = {
            load(Opcodes.ALOAD, "b")
            step(aload(0))
            step(Insn.Call(Opcodes.INVOKEVIRTUAL, self, "canEqual", "(L$OBJECT;)Z"))
        }
        if (elements.isEmpty()) {
            if (mayOmitCanEqual) m.either(canEqualCall) { step(Insn.IntConstant(1)) } else m.canEqualCall()
            m.jump(Opcodes.GOTO, "test")
        } else {
            m.compareElements(elements, "b", "miss")
            val canEqualTest: Match.() -> Unit = {
                canEqualCall()
                jump(Opcodes.IFEQ, "miss")
            }
            if (mayOmitCanEqual) m.optional(canEqualTest) else m.canEqualTest()
            m.step(Insn.IntConstant(1))
            m.jump(Opcodes.GOTO, "join")
            m.label("miss")
            m.step(Insn.IntConstant(0))
            m.label("join")
            m.jump(Opcodes.GOTO, "test")
        }
        m.label("no")
        m.step(Insn.IntConstant(0))
        m.jump(Opcodes.GOTO, "test")
        m.label("test")
        m.jump(Opcodes.IFEQ, "false")
        m.label("true")
        m.step(Insn.IntConstant(1))
        m.jump(Opcodes.GOTO, "end")
        m.label("false")
        m.step(Insn.IntConstant(0))
        m.label("end")
        m.step(Insn.Plain(Opcodes.IRETURN))
        return m.matched
    }

    /**
     * `productElement` ([names] false) or `productElementName` ([names] true), read from `javap
     * -c` over `Mixed`, `One`, `Cc`, `Round`, `Empty` and `Outer$Inner` in both versions:
     *
     * ```
     * iload_1; istore k
     * a tableswitch on k over 0 to <arity - 1>,         (every Scala 2.13.15 class, and Scala 3.3.4 with many elements)
     *   or per element i: <i>; iload k; if_icmpne NEXT  (Scala 3.3.4 with few elements)
     * per element, in order: its case
     * DEFAULT: out of range
     * [SHARED: box; areturn]
     * ```
     *
     * with no dispatch at all for a class with no elements. An element's case is `ldc "<name>";
     * areturn` for a name. For a value it is `aload_0` and a read of the element through its
     * accessor (Scala 2.13.15) or `_N` (Scala 3.3.4), then either its `BoxesRunTime.boxTo...` and
     * `areturn`, or `goto SHARED` when every element has one primitive type and Scala 3.3.4 boxes
     * once at the end (`Cc`). Out of range is `iload_1; Statics.ioobe(I)`, `checkcast String` for
     * a name, and `areturn` in Scala 2.13.15, and `new IndexOutOfBoundsException(n.toString)` and
     * `athrow` in Scala 3.3.4, which writes a second, unreachable `athrow` after it when a
     * `tableswitch` shares one box (`P3`, three `double` elements). Each part named for one
     * version above counts only in a class of that version.
     */
    private fun matchesIndexed(
        case: CaseClass,
        body: List<Insn>,
        names: Boolean,
    ): Boolean {
        val elements = case.elements ?: return false
        val count = elements.size
        val scala3 = case.shape.isScala3
        return listOf(true, false).any { tableSwitch ->
            if ((count == 0 || !scala3) && !tableSwitch) return@any false
            val m = Match(body)
            m.step(Insn.Var(Opcodes.ILOAD, 1))
            m.store(Opcodes.ISTORE, "k")
            var shared: Type? = null
            val caseBody = { element: Element ->
                if (names) {
                    m.step(Insn.Constant(element.name))
                    m.step(areturn())
                } else {
                    m.step(aload(0))
                    val alias = "_${element.index + 1}"
                    when {
                        !scala3 -> m.step(element.read)

                        case.declares(
                            alias,
                            "()${element.type}",
                        ) -> m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, case.name, alias, "()${element.type}"))

                        else -> m.fail()
                    }
                    val boxed =
                        m.optional {
                            box(element.type)
                            step(areturn())
                            true
                        }
                    if (boxed == null) {
                        if (!scala3 || !element.isPrimitive || (shared != null && shared != element.type)) m.fail()
                        shared = element.type
                        m.jump(Opcodes.GOTO, "shared")
                    }
                }
            }
            if (count > 0 && tableSwitch) {
                m.load(Opcodes.ILOAD, "k")
                m.tableSwitch(count - 1, elements.map { "case${it.index}" }, "default")
                for (element in elements) {
                    m.label("case${element.index}")
                    caseBody(element)
                }
            } else {
                for (element in elements) {
                    m.step(Insn.IntConstant(element.index))
                    m.load(Opcodes.ILOAD, "k")
                    m.jump(Opcodes.IF_ICMPNE, if (element.index == count - 1) "default" else "test${element.index + 1}")
                    caseBody(element)
                    if (element.index < count - 1) m.label("test${element.index + 1}")
                }
            }
            m.label("default")
            if (scala3) {
                m.step(Insn.TypeOperand(Opcodes.NEW, OUT_OF_BOUNDS))
                m.step(Insn.Plain(Opcodes.DUP))
                m.step(Insn.Var(Opcodes.ILOAD, 1))
                m.step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxToInteger", "(I)Ljava/lang/Integer;"))
                m.step(Insn.Call(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "toString", "()L$STRING;"))
                m.step(Insn.Call(Opcodes.INVOKESPECIAL, OUT_OF_BOUNDS, "<init>", "(L$STRING;)V"))
                m.step(Insn.Plain(Opcodes.ATHROW))
                if (shared != null) m.optional { step(Insn.Plain(Opcodes.ATHROW)) }
            } else {
                m.step(Insn.Var(Opcodes.ILOAD, 1))
                m.step(Insn.Call(Opcodes.INVOKESTATIC, STATICS, "ioobe", "(I)L$OBJECT;"))
                if (names) m.step(Insn.TypeOperand(Opcodes.CHECKCAST, STRING))
                m.step(areturn())
            }
            shared?.let { type ->
                m.label("shared")
                m.box(type)
                m.step(areturn())
            }
            m.matched
        }
    }

    /**
     * The plumbing methods of the companion [companion] of the case class [partner]. Shapes, read
     * from `javap -c` over the companions of `Cc`, `One`, `Empty`, `Mixed`, `Priv`, `Hidden`,
     * `Outer$Inner` and the local `Local` in both versions:
     *
     * - `apply`, returning the partner: see [matchesConstruction], the outer reference read from
     *   the companion's own `$outer`.
     * - `unapply(<partner>)`: in Scala 3.3.4, `aload_1; areturn`, or `iconst_1; ireturn` returning
     *   `boolean` for a class with no elements. In Scala 2.13.15, see [matchesUnapplyScala2].
     * - `toString()`: `ldc "<partner's source name>"; areturn`.
     * - Scala 3.3.4's `fromProduct(scala.Product)`: `new <partner>; dup`, the outer reference, then
     *   per element `aload_1; <index>; invokeinterface Product.productElement(I)` and its
     *   `BoxesRunTime.unboxTo...`, or `checkcast` to its type unless that is `Object`, then
     *   `invokespecial <partner>.<init>; areturn`, the constructor being the partner's primary
     *   one. Its bridge returning `Object` is `aload_0;
     *   aload_1; invokevirtual fromProduct; areturn`.
     *
     * The `unapply` and `fromProduct` shapes of one version count only in a companion of that
     * version (see [readShape]): a Scala 2 companion's hand-written `unapply` returning its
     * argument or a constant `true`, or its `fromProduct`, is the adopter's (`Id$`, `U0$`, `FP$`).
     *
     * A hand-written `apply(String)` in an explicit companion constructs its partner from other
     * values than its own parameters, and is not marked. scalac 2.13.15 and 3.3.4 give no
     * case-class companion a `readResolve`, so none is marked.
     */
    private fun companionPlumbing(
        companion: ClassShape,
        partner: CaseClass,
    ): Set<Pair<String, String>> {
        val partnerType = "L${partner.name};"
        val scala3 = companion.isScala3
        return companion
            .instanceMethods()
            .filter { (key, body) ->
                val (name, descriptor) = key
                when (name) {
                    "apply" -> {
                        Type.getReturnType(descriptor).descriptor == partnerType &&
                            matchesConstruction(
                                body,
                                partner.name,
                                descriptor,
                                partner.shape,
                                companion.internalName,
                                outerAccessor = false,
                            )
                    }

                    "unapply" -> {
                        when (descriptor) {
                            "($partnerType)$partnerType" -> {
                                scala3 && body == listOf(aload(1), areturn())
                            }

                            "($partnerType)Z" -> {
                                partner.elements?.isEmpty() == true &&
                                    if (scala3) {
                                        body == listOf(Insn.IntConstant(1), Insn.Plain(Opcodes.IRETURN))
                                    } else {
                                        matchesUnapplyScala2(partner, body)
                                    }
                            }

                            "($partnerType)Lscala/Option;" -> {
                                !scala3 && partner.elements?.isNotEmpty() == true && matchesUnapplyScala2(partner, body)
                            }

                            else -> {
                                false
                            }
                        }
                    }

                    "toString" -> {
                        descriptor == "()L$STRING;" && body == listOf(Insn.Constant(partner.shape.sourceName), areturn())
                    }

                    "fromProduct" -> {
                        scala3 &&
                            when (descriptor) {
                                "(L$PRODUCT;)$partnerType" -> {
                                    matchesFromProduct(companion, partner, body)
                                }

                                "(L$PRODUCT;)L$OBJECT;" -> {
                                    companion.methods["fromProduct" to "(L$PRODUCT;)$partnerType"]?.body?.let {
                                        matchesFromProduct(companion, partner, it)
                                    } == true &&
                                        body ==
                                        listOf(
                                            aload(0),
                                            aload(1),
                                            Insn.Call(
                                                Opcodes.INVOKEVIRTUAL,
                                                companion.internalName,
                                                "fromProduct",
                                                "(L$PRODUCT;)$partnerType",
                                            ),
                                            areturn(),
                                        )
                                }

                                else -> {
                                    false
                                }
                            }
                    }

                    else -> {
                        false
                    }
                }
            }.mapTo(mutableSetOf()) { it.first }
    }

    /**
     * Scala 2.13.15's `unapply`, read from `javap -c` over the companions of `Empty`, `One`,
     * `Outer$Inner`, `Cc`, `Box`, `Hidden`, `Priv` and `Mixed`:
     *
     * ```
     * aload_1; ifnonnull SOME
     * with no elements: iconst_0; ireturn; SOME: iconst_1; ireturn
     * otherwise:        getstatic scala/None$.MODULE$; areturn
     *                   SOME: new scala/Some; dup; <value>; invokespecial Some.<init>(Object); areturn
     * ```
     *
     * The value of one element is `aload_1`, its read and its box. Of several, it is `new
     * scala/TupleN; dup`, each element's `aload_1`, read and box, and `invokespecial
     * TupleN.<init>`, except that two elements both of type `int`, `long`, `double`, `char` or
     * `boolean` build the specialised `scala/Tuple2$mc<types>$sp` from unboxed values
     * (`Tuple2$mcII$sp` for `Cc`, `Tuple2$mcJZ$sp` for `Hidden`).
     */
    private fun matchesUnapplyScala2(
        partner: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = partner.elements ?: return false
        val reads = elements.map { it.readFromOutside ?: return false }
        val m = Match(body)
        m.step(aload(1))
        m.jump(Opcodes.IFNONNULL, "some")
        if (elements.isEmpty()) {
            m.step(Insn.IntConstant(0))
            m.step(Insn.Plain(Opcodes.IRETURN))
            m.label("some")
            m.step(Insn.IntConstant(1))
            m.step(Insn.Plain(Opcodes.IRETURN))
            return m.matched
        }
        if (elements.size > 22) return false
        m.step(Insn.Field(Opcodes.GETSTATIC, "scala/None$", MODULE_FIELD, "Lscala/None$;"))
        m.step(areturn())
        m.label("some")
        m.step(Insn.TypeOperand(Opcodes.NEW, "scala/Some"))
        m.step(Insn.Plain(Opcodes.DUP))
        val specialised = elements.size == 2 && elements.all { it.type.descriptor in TUPLE2_SPECIALISED }
        if (elements.size == 1) {
            m.step(aload(1))
            m.step(reads.single())
            m.box(elements.single().type)
        } else {
            val tuple =
                if (specialised) {
                    "scala/Tuple2\$mc${elements.joinToString(
                        "",
                    ) { it.type.descriptor }}\$sp"
                } else {
                    "scala/Tuple${elements.size}"
                }
            m.step(Insn.TypeOperand(Opcodes.NEW, tuple))
            m.step(Insn.Plain(Opcodes.DUP))
            for ((element, read) in elements.zip(reads)) {
                m.step(aload(1))
                m.step(read)
                if (!specialised) m.box(element.type)
            }
            val constructor =
                if (specialised) "(${elements.joinToString("") { it.type.descriptor }})V" else "(${"L$OBJECT;".repeat(elements.size)})V"
            m.step(Insn.Call(Opcodes.INVOKESPECIAL, tuple, "<init>", constructor))
        }
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, "scala/Some", "<init>", "(L$OBJECT;)V"))
        m.step(areturn())
        return m.matched
    }

    /** Scala 3.3.4's `fromProduct`; see [companionPlumbing]. */
    private fun matchesFromProduct(
        companion: ClassShape,
        partner: CaseClass,
        body: List<Insn>,
    ): Boolean {
        val elements = partner.elements ?: return false
        val m = Match(body)
        m.step(Insn.TypeOperand(Opcodes.NEW, partner.name))
        m.step(Insn.Plain(Opcodes.DUP))
        val outer = m.optional { outerReference(companion.internalName, accessor = false) }
        for (element in elements) {
            m.step(aload(1))
            m.step(Insn.IntConstant(element.index))
            m.step(Insn.Call(Opcodes.INVOKEINTERFACE, PRODUCT, "productElement", "(I)L$OBJECT;"))
            when (element.type.sort) {
                in Type.BOOLEAN..Type.DOUBLE -> {
                    m.step(unbox(element.type))
                }

                Type.OBJECT, Type.ARRAY -> {
                    if (element.type.internalName !=
                        OBJECT
                    ) {
                        m.step(Insn.TypeOperand(Opcodes.CHECKCAST, element.type.internalName))
                    }
                }

                else -> {
                    m.fail()
                }
            }
        }
        val constructor = "(${outer.orEmpty()}${elements.joinToString("") { it.type.descriptor }})V"
        m.step(Insn.Call(Opcodes.INVOKESPECIAL, partner.name, "<init>", constructor))
        m.step(areturn())
        return m.matched && partner.shape.isPrimaryConstructor(constructor)
    }

    /** `BoxesRunTime.boxTo...` for a primitive [type]; nothing for a reference. */
    private fun Match.box(type: Type) {
        val boxed = boxedName(type) ?: return
        step(Insn.Call(Opcodes.INVOKESTATIC, BOXES, "boxTo${boxed.substringAfterLast('/')}", "($type)L$boxed;"))
    }

    private fun unbox(type: Type): Insn {
        val name =
            when (type.sort) {
                Type.INT -> "Int"
                Type.CHAR -> "Char"
                else -> boxedName(type)!!.substringAfterLast('/')
            }
        return Insn.Call(Opcodes.INVOKESTATIC, BOXES, "unboxTo$name", "(L$OBJECT;)$type")
    }

    private fun boxedName(type: Type): String? =
        when (type.sort) {
            Type.BOOLEAN -> "java/lang/Boolean"
            Type.CHAR -> "java/lang/Character"
            Type.BYTE -> "java/lang/Byte"
            Type.SHORT -> "java/lang/Short"
            Type.INT -> "java/lang/Integer"
            Type.FLOAT -> "java/lang/Float"
            Type.LONG -> "java/lang/Long"
            Type.DOUBLE -> "java/lang/Double"
            else -> null
        }

    private fun parameterLoads(
        descriptor: String,
        firstSlot: Int,
    ): List<Insn> {
        var slot = firstSlot
        return Type.getArgumentTypes(descriptor).map { type -> Insn.Var(type.getOpcode(Opcodes.ILOAD), slot).also { slot += type.size } }
    }

    /** The parameter types of [descriptor], without the parentheses. */
    private fun parameterPart(descriptor: String): String = descriptor.substring(1, descriptor.lastIndexOf(')'))

    private fun aload(slot: Int): Insn = Insn.Var(Opcodes.ALOAD, slot)

    private fun areturn(): Insn = Insn.Plain(Opcodes.ARETURN)

    /**
     * One bytecode instruction, as the matchers compare it. `iconst`, `bipush`, `sipush` and an
     * `ldc` of an `int` are all an [IntConstant]. A jump or switch names its targets by the index
     * of the instruction they land on.
     */
    private sealed interface Insn {
        data class Plain(
            val opcode: Int,
        ) : Insn

        data class IntConstant(
            val value: Int,
        ) : Insn

        data class Var(
            val opcode: Int,
            val slot: Int,
        ) : Insn

        data class Field(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class Call(
            val opcode: Int,
            val owner: String,
            val name: String,
            val descriptor: String,
        ) : Insn

        data class TypeOperand(
            val opcode: Int,
            val type: String,
        ) : Insn

        data class Constant(
            val value: Any?,
        ) : Insn

        data class Jump(
            val opcode: Int,
            val target: Int,
        ) : Insn

        data class TableSwitch(
            val min: Int,
            val max: Int,
            val default: Int,
            val targets: List<Int>,
        ) : Insn

        /** Any instruction no shape here contains, such as `iinc` or `invokedynamic`. */
        data object Other : Insn
    }

    /**
     * Records a method's instructions as [Insn]s and hands them to [onEnd], null for a method
     * with no code, with whether it has a try-catch block. Labels, line numbers, frames and
     * local-variable entries are not instructions; a label only gives a jump its target index.
     */
    private class BodyRecorder(
        private val onEnd: (code: List<Insn>?, hasHandler: Boolean) -> Unit,
    ) : MethodVisitor(Opcodes.ASM9) {
        private val raw = mutableListOf<Any>()
        private val labels = HashMap<Label, Int>()
        private var hasCode = false
        private var hasHandler = false

        private class PendingJump(
            val opcode: Int,
            val target: Label,
        )

        private class PendingSwitch(
            val min: Int,
            val max: Int,
            val default: Label,
            val targets: List<Label>,
        )

        private fun add(insn: Any) {
            raw += insn
        }

        override fun visitCode() {
            hasCode = true
        }

        override fun visitLabel(label: Label) {
            labels[label] = raw.size
        }

        override fun visitInsn(opcode: Int) =
            add(if (opcode in Opcodes.ICONST_M1..Opcodes.ICONST_5) Insn.IntConstant(opcode - Opcodes.ICONST_0) else Insn.Plain(opcode))

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = add(if (opcode == Opcodes.NEWARRAY) Insn.Other else Insn.IntConstant(operand))

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) = add(Insn.Var(opcode, varIndex))

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = add(Insn.Field(opcode, owner, name, descriptor))

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) = add(Insn.Call(opcode, owner, name, descriptor))

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) = add(Insn.TypeOperand(opcode, type))

        override fun visitLdcInsn(value: Any?) = add(if (value is Int) Insn.IntConstant(value) else Insn.Constant(value))

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = add(PendingJump(opcode, label))

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) = add(PendingSwitch(min, max, dflt, labels.toList()))

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) = add(Insn.Other)

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) = add(Insn.Other)

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) = add(Insn.Other)

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) = add(Insn.Other)

        override fun visitTryCatchBlock(
            start: Label,
            end: Label,
            handler: Label,
            type: String?,
        ) {
            hasHandler = true
        }

        override fun visitEnd() {
            if (!hasCode) return onEnd(null, hasHandler)
            onEnd(
                raw.map { insn ->
                    when (insn) {
                        is PendingJump -> {
                            Insn.Jump(insn.opcode, labels.getValue(insn.target))
                        }

                        is PendingSwitch -> {
                            Insn.TableSwitch(
                                insn.min,
                                insn.max,
                                labels.getValue(insn.default),
                                insn.targets.map(labels::getValue),
                            )
                        }

                        else -> {
                            insn as Insn
                        }
                    }
                },
                hasHandler,
            )
        }
    }

    /**
     * Walks a body once, front to back, against an expected shape. Each step consumes one
     * instruction or fails the match; nothing is skipped, so an instruction the shape does not
     * name fails it. A jump binds its label name to its target index the first time the name is
     * seen, and [label] checks that the walk has reached that index; a local-variable slot is
     * bound by name the same way. [optional] and [either] try a part of the shape and roll back
     * when it fails.
     */
    private class Match(
        private val insns: List<Insn>,
        private val firstLocal: Int = 2,
    ) {
        private var pos = 0
        private var ok = true
        private var labels = HashMap<String, Int>()
        private var slots = HashMap<String, Int>()

        val matched: Boolean get() = ok && pos == insns.size

        fun fail() {
            ok = false
        }

        fun step(expected: Insn) {
            if (ok && pos < insns.size && insns[pos] == expected) pos++ else ok = false
        }

        fun <T : Any> take(capture: (Insn) -> T?): T? {
            if (!ok || pos >= insns.size) return null.also { ok = false }
            return capture(insns[pos])?.also { pos++ } ?: null.also { ok = false }
        }

        private fun bind(
            names: HashMap<String, Int>,
            name: String,
            value: Int,
        ): Boolean = names.getOrPut(name) { value } == value

        fun label(name: String) {
            if (ok && !bind(labels, name, pos)) ok = false
        }

        fun jump(
            opcode: Int,
            label: String,
        ) {
            take { insn -> (insn as? Insn.Jump)?.takeIf { it.opcode == opcode && bind(labels, label, it.target) } }
        }

        fun tableSwitch(
            max: Int,
            cases: List<String>,
            default: String,
        ) {
            take { insn ->
                (insn as? Insn.TableSwitch)?.takeIf { switch ->
                    switch.min == 0 &&
                        switch.max == max &&
                        bind(labels, default, switch.default) &&
                        cases.zip(switch.targets).all { (name, target) -> bind(labels, name, target) }
                }
            }
        }

        /** A load of the slot bound to [name]. */
        fun load(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && slots[name] == it.slot } }
        }

        /** A store to a slot at or after [firstLocal], the first slot past `this` and the parameters, bound to [name]. */
        fun store(
            opcode: Int,
            name: String,
        ) {
            take { insn -> (insn as? Insn.Var)?.takeIf { it.opcode == opcode && it.slot >= firstLocal && bind(slots, name, it.slot) } }
        }

        /** Runs [part]; when it fails, rolls back to where it started and returns null. */
        fun <T> optional(part: Match.() -> T): T? {
            if (!ok) return null
            val start = pos
            val savedLabels = HashMap(labels)
            val savedSlots = HashMap(slots)
            val result = part()
            if (ok) return result
            pos = start
            labels = savedLabels
            slots = savedSlots
            ok = true
            return null
        }

        /** Runs [first], or [second] when [first] fails. */
        fun either(
            first: Match.() -> Unit,
            second: Match.() -> Unit,
        ) {
            if (optional { first().let { true } } == null) second()
        }
    }
}
