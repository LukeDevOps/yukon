package io.github.lukedevops.yukon.instrumentation.branch

import io.github.lukedevops.yukon.export.GeneratedBy

/**
 * The case classes and companions of the `:fixtures-scala2` and `:fixtures-scala3` modules, and
 * the members of each that the adopter wrote, for the ADR 0048 tests of the transform and of the
 * static baseline. Every plumbing member not listed here as hand-written is scalac's.
 */
object ScalaCaseClassFixtures {
    /** Every fixture case class, by simple name, with its element accessors. */
    val CASE_CLASSES: Map<String, List<String>> =
        mapOf(
            "Cc" to listOf("a", "b"),
            "Written" to listOf("a", "b"),
            "Multi" to listOf("a", "b"),
            "Round" to listOf("r"),
            "Box" to listOf("w", "h"),
            "Empty" to emptyList(),
            "BodyVal" to listOf("a", "later"),
            "HandCopy" to listOf("a"),
            "Svc" to listOf("a", "b"),
            "TwoLists" to listOf("a"),
            "Outer\$Inner" to listOf("a"),
            "Outer\$Local\$1" to listOf("a"),
            "Priv" to listOf("a", "b"),
            "Mixed" to listOf("l", "d", "z", "s", "i", "f", "c", "by", "sh", "t", "o"),
            "One" to listOf("s"),
            "Hidden" to listOf("secret", "open"),
            "Lazy" to listOf("a", "b", "twice"),
            "P3" to listOf("x", "y", "z"),
            "FT" to listOf("a", "b"),
            "FE" to emptyList(),
            "F1" to listOf("s"),
            "FinalOuter\$FIn" to listOf("a", "b"),
            "FinalOuter\$FInEmpty" to emptyList(),
            "UC" to listOf("a", "b"),
            "UC2" to listOf("a", "b"),
            "FZC" to emptyList(),
            "FinalHolder\$FObj" to listOf("a"),
            "NoCanEqual" to listOf("a", "b"),
            "Node" to listOf("a", "child"),
            "TryBody" to listOf("a"),
        )

    /**
     * The members of a fixture case class the adopter wrote, by name. `HandCopy.copy` and
     * `UC.canEqual` are written by hand too, but each body is instruction for instruction the one
     * scalac writes, so it is scalac's code and is not listed.
     */
    val HAND_WRITTEN: Map<String, Set<String>> =
        mapOf(
            "Multi" to setOf("toString"),
            "BodyVal" to setOf("toString"),
            "HandCopy" to setOf("toString", "hashCode"),
            "Svc" to setOf("toString"),
            "Written" to setOf("toString"),
            "NoCanEqual" to setOf("equals"),
            "UC2" to setOf("canEqual", "equals"),
            "FZC" to setOf("canEqual"),
        )

    /** The companion members the adopter wrote, by class, name and descriptor. */
    val HAND_WRITTEN_COMPANION: Set<Triple<String, String, String>> =
        setOf(Triple("Written\$", "apply", "(Ljava/lang/String;)Lcom/example/scalatarget/Written;"))

    private val PLUMBING =
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

    /** The names of the companion members scalac writes. */
    val COMPANION_PLUMBING = setOf("apply", "unapply", "toString", "fromProduct")

    /** Whether an instance method of a case class named [name] is plumbing: one scalac writes by that name. */
    fun isPlumbing(name: String): Boolean = name in PLUMBING || Regex("_\\d+").matches(name) || Regex(".+\\\$access\\\$\\d+").matches(name)

    /**
     * The companion of the case class [simpleName] in fixture module [module]. A local case class's
     * companion is numbered after it: `Outer$Local$2$` from Scala 2.13.15 and `Outer$Local$3$`
     * from Scala 3.3.4.
     */
    fun companionOf(
        module: String,
        simpleName: String,
    ): String =
        when {
            simpleName != "Outer\$Local\$1" -> "$simpleName\$"
            module == "scala2" -> "Outer\$Local\$2\$"
            else -> "Outer\$Local\$3\$"
        }

    /** Every fixture case class and its companion, by simple name, in [module]. */
    fun allClasses(module: String): List<String> = CASE_CLASSES.keys.flatMap { listOf(it, companionOf(module, it)) }

    /**
     * The mark an instance method of the fixture class [simpleName] must carry in [module], or
     * null when the method is none of this table's business, such as a constructor or an
     * `$outer` accessor.
     */
    fun expectedMark(
        module: String,
        simpleName: String,
        name: String,
        descriptor: String,
    ): GeneratedBy? {
        CASE_CLASSES[simpleName]?.let { accessors ->
            return when {
                name in HAND_WRITTEN[simpleName].orEmpty() -> GeneratedBy.NONE
                name in accessors -> GeneratedBy.NONE
                isPlumbing(name) -> GeneratedBy.CASE_CLASS
                else -> null
            }
        }
        if (CASE_CLASSES.keys.none { companionOf(module, it) == simpleName }) return null
        return when {
            Triple(simpleName, name, descriptor) in HAND_WRITTEN_COMPANION -> GeneratedBy.NONE
            name in COMPANION_PLUMBING -> GeneratedBy.CASE_CLASS
            else -> null
        }
    }
}
