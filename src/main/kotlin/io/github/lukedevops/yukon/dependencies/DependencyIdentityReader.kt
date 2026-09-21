package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import java.util.jar.Attributes

/** A jar's identities, where they were read from, and how many classes it holds. */
internal data class JarIdentity(
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val classCount: Int,
)

/**
 * Reads a jar's identity by the ADR 0030 rule: every `pom.properties` that names an artifact, else
 * the filename with its version suffix split off. The manifest's `Implementation-Title` is never
 * an identity, since it is a display name that several jars can share (three Tomcat jars all say
 * `Apache Tomcat`); its `Implementation-Version` only fills in a version the filename lacks.
 */
internal object DependencyIdentityReader {
    private val VERSIONED_STEM = Regex("^(.+?)-(\\d[^-]*(?:-.+)?)$")
    private val ARCHIVE_EXTENSION = Regex("\\.(jar|zip)$", RegexOption.IGNORE_CASE)

    /** [fileName] is the jar's own name, without any directory or outer-jar path. */
    fun identify(
        contents: JarContents,
        fileName: String,
    ): JarIdentity {
        val classCount = contents.classNames.size
        val fromPoms = fromPomProperties(contents)
        if (fromPoms.isNotEmpty()) return JarIdentity(fromPoms, DependencyIdentitySource.POM_PROPERTIES, classCount)
        val fromFilename = fromFilename(fileName)
        val identity = if (fromFilename.version != null) fromFilename else fromFilename.copy(version = manifestVersion(contents))
        return JarIdentity(listOf(identity), DependencyIdentitySource.FILENAME, classCount)
    }

    /**
     * One identity per `pom.properties` with an `artifactId`, sorted by group then artifact, the
     * first of any repeated pair kept. A shaded jar carries one file per bundled library.
     */
    private fun fromPomProperties(contents: JarContents): List<DependencyIdentity> =
        contents.pomProperties
            .mapNotNull { properties ->
                val artifactId = properties.getProperty("artifactId")?.trim()
                if (artifactId.isNullOrEmpty()) return@mapNotNull null
                DependencyIdentity(
                    properties.getProperty("groupId")?.trim()?.ifEmpty { null },
                    artifactId,
                    properties.getProperty("version")?.trim()?.ifEmpty { null },
                )
            }.distinctBy { it.groupId to it.artifactId }
            .sortedWith(compareBy({ it.groupId.orEmpty() }, { it.artifactId }))

    private fun manifestVersion(contents: JarContents): String? =
        contents.manifest
            ?.mainAttributes
            ?.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
            ?.trim()
            ?.ifEmpty { null }

    /**
     * Strips a `.jar` or `.zip` extension, then splits at the first `-` followed by a digit when the rest of the name
     * can be a version: `guava-33.0.0-jre.jar` is `guava` at `33.0.0-jre`. A name with no such
     * split is all artifact, with no version.
     */
    fun fromFilename(fileName: String): DependencyIdentity {
        val stem = ARCHIVE_EXTENSION.replace(fileName, "")
        val match = VERSIONED_STEM.matchEntire(stem) ?: return DependencyIdentity(null, stem, null)
        return DependencyIdentity(null, match.groupValues[1], match.groupValues[2])
    }
}
