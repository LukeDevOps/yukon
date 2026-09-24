package com.example.target.keypairs

import io.github.lukedevops.yukon.instrumentation.branch.BranchKeys
import io.github.lukedevops.yukon.instrumentation.branch.BranchSite
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.SimpleRemapper
import java.io.File

/**
 * One fixture build's analysed sites and branch keys, named [name] for readable test failures.
 *
 * The key of one method's site is looked up by the site's position among its own method's
 * entries, not by [BranchSite.siteIndex]: a test states which site it means the same way a
 * reader would, "method `m`'s first site", without needing to know the class-wide index an
 * earlier edit may have shifted.
 */
class KeyedBuild(
    val name: String,
    val sites: List<BranchSite>,
    private val keys: Map<Pair<Int, Int>, String>,
) {
    private fun siteAt(
        methodName: String,
        methodOrdinal: Int,
    ): BranchSite = sites.filter { it.methodName == methodName }[methodOrdinal]

    /** The branch key of [methodName]'s [methodOrdinal]-th tracked site, outcome [outcome]; null when unnamed. */
    fun keyOf(
        methodName: String,
        methodOrdinal: Int = 0,
        outcome: Int = 0,
    ): String? = keys[siteAt(methodName, methodOrdinal).siteIndex to outcome]

    /**
     * The class-wide branch index [methodName]'s [methodOrdinal]-th site's [outcome] would get:
     * the outcome slots of every earlier site in the class, dropped or kept, plus [outcome]
     * itself. Mirrors the running total `KeptBranchSite.of` keeps while it numbers outcomes.
     */
    fun branchIndexOf(
        methodName: String,
        methodOrdinal: Int = 0,
        outcome: Int = 0,
    ): Int {
        val site = siteAt(methodName, methodOrdinal)
        return sites.filter { it.siteIndex < site.siteIndex }.sumOf { it.outcomeCount } + outcome
    }
}

/** Reads a compiled Kotlin test fixture's own bytes from Gradle's test class output. */
fun kotlinFixtureBytes(simpleName: String): ByteArray =
    File("build/classes/kotlin/test/com/example/target/keypairs/$simpleName.class").readBytes()

/** Reads a compiled Java test fixture's own bytes from Gradle's test class output. */
fun javaFixtureBytes(simpleName: String): ByteArray =
    File("build/classes/java/test/com/example/target/keypairs/$simpleName.class").readBytes()

/**
 * Renames [bytes]'s own class from [fromInternal] to [toInternal] with ByteBuddy's shaded ASM
 * `ClassRemapper`, carrying every self-reference (a field of its own type, a call to its own
 * method, its own constructor descriptor) along with it.
 */
fun renamedTo(
    bytes: ByteArray,
    fromInternal: String,
    toInternal: String,
): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(ClassRemapper(writer, SimpleRemapper(fromInternal, toInternal)), 0)
    return writer.toByteArray()
}

/**
 * Renames [v1SimpleName] and [v2SimpleName]'s compiled classes to one common name and analyses
 * each under it, so [BranchKeys.compute] sees the same class name for both builds, the same way a
 * service's own class name never changes across a release. [bytesOf] chooses which language's test
 * output to read from; [includePackages] controls in-scope inline resolution the same way
 * `InlinedCopyAnalysisTest` does.
 */
fun keyedBuildsOf(
    v1SimpleName: String,
    v2SimpleName: String,
    commonSimpleName: String = "Common",
    bytesOf: (String) -> ByteArray = ::kotlinFixtureBytes,
    includePackages: List<String> = listOf("com.example"),
    methodFilter: (name: String, descriptor: String) -> Boolean = { _, _ -> true },
): Pair<KeyedBuild, KeyedBuild> {
    val v1Internal = "com/example/target/keypairs/$v1SimpleName"
    val v2Internal = "com/example/target/keypairs/$v2SimpleName"
    val commonInternal = "com/example/target/keypairs/$commonSimpleName"
    val commonDotted = commonInternal.replace('/', '.')

    val v1Bytes = renamedTo(bytesOf(v1SimpleName), v1Internal, commonInternal)
    val v2Bytes = renamedTo(bytesOf(v2SimpleName), v2Internal, commonInternal)

    val v1Analysis = BranchSiteAnalyzer.analyze(v1Bytes, includePackages = includePackages, methodFilter = methodFilter)
    val v2Analysis = BranchSiteAnalyzer.analyze(v2Bytes, includePackages = includePackages, methodFilter = methodFilter)

    val v1Keys = BranchKeys.compute(v1Analysis.sites, commonDotted)
    val v2Keys = BranchKeys.compute(v2Analysis.sites, commonDotted)

    return KeyedBuild(v1SimpleName, v1Analysis.sites, v1Keys) to KeyedBuild(v2SimpleName, v2Analysis.sites, v2Keys)
}
