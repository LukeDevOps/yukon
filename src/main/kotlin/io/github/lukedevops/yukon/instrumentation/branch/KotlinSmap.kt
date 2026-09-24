package io.github.lukedevops.yukon.instrumentation.branch

/**
 * One `*L` entry of a JSR-045 SMAP: [repeatCount] consecutive input lines starting at
 * [inputStartLine], in file [fileId], map to output lines starting at [outputStartLine], each
 * input line taking [outputLineIncrement] consecutive output lines.
 */
internal data class SmapEntry(
    val inputStartLine: Int,
    val fileId: Int,
    val outputStartLine: Int,
    val outputLineIncrement: Int,
    val repeatCount: Int,
) {
    /**
     * The zero-based position of [outputLine]'s input line within this entry, or null outside
     * it. Every output line inside one input line's block of [outputLineIncrement] resolves to
     * that input line, which is what the JSR-045 wording "each input line maps to
     * OutputLineIncrement output lines" means; kotlinc writes no increment in the `Kotlin`
     * stratum, but the parser follows the spec rather than the one producer it has seen.
     */
    fun indexOf(outputLine: Int): Int? {
        if (outputLineIncrement <= 0) return null
        val offset = outputLine - outputStartLine
        if (offset < 0) return null
        val index = offset / outputLineIncrement
        return index.takeIf { it < repeatCount }
    }
}

/**
 * Where an output line's bytecode came from: [inputLine] of [originClassName], dotted, in the file
 * the `*F` entry names, [sourceFile]. [sourceFile] is a file name such as `Collections.kt`, never a
 * path.
 */
data class SmapOrigin(
    val inputLine: Int,
    val originClassName: String,
    val sourceFile: String,
)

/**
 * The `Kotlin` stratum of a class's `SourceDebugExtension`, parsed by [KotlinSmapParser]. Maps an
 * output line (the line a jump or switch instruction carries in the class's own bytecode) to
 * where kotlinc copied it from, when that line is not the class's own code as written.
 */
class KotlinSmap internal constructor(
    private val entries: List<SmapEntry>,
    private val fileNamesById: Map<Int, String>,
    private val sourceFilesById: Map<Int, String> = emptyMap(),
) {
    /**
     * Where [outputLine] came from, or null when it is the class's own code: either no entry
     * covers it, or the covering entry maps it to itself in file id 1, the identity mapping every
     * unmodified source line gets.
     */
    fun originOf(outputLine: Int): SmapOrigin? {
        for (entry in entries) {
            val index = entry.indexOf(outputLine) ?: continue
            val inputLine = entry.inputStartLine + index
            if (entry.fileId == 1 && inputLine == outputLine) return null
            val fileName = fileNamesById[entry.fileId] ?: return null
            return SmapOrigin(inputLine, fileName.replace('/', '.'), sourceFilesById[entry.fileId] ?: fileName)
        }
        return null
    }

    companion object {
        val EMPTY = KotlinSmap(emptyList(), emptyMap())
    }
}

/**
 * Parses the `Kotlin` stratum of a class's `SourceDebugExtension`, the JSR-045 SMAP kotlinc
 * writes to record where an inlined copy's bytecode came from. Only the `*S Kotlin` stratum is
 * read; a `*S KotlinDebug` stratum, which maps the other direction, is skipped entirely.
 */
object KotlinSmapParser {
    private val lineEntryPattern = Regex("""^(\d+)(?:#(\d+))?(?:,(\d+))?:(\d+)(?:,(\d+))?$""")

    fun parse(debug: String?): KotlinSmap {
        if (debug.isNullOrEmpty()) return KotlinSmap.EMPTY
        val lines = debug.split('\n')
        var i = findKotlinStratum(lines) ?: return KotlinSmap.EMPTY
        if (i >= lines.size || lines[i] != "*F") return KotlinSmap.EMPTY
        i++

        val fileNamesById = mutableMapOf<Int, String>()
        val sourceFilesById = mutableMapOf<Int, String>()
        i = parseFileSection(lines, i, fileNamesById, sourceFilesById)

        if (i >= lines.size || lines[i] != "*L") return KotlinSmap(emptyList(), fileNamesById, sourceFilesById)
        i++

        val entries = mutableListOf<SmapEntry>()
        var lastFileId = 1
        while (i < lines.size && !lines[i].startsWith("*")) {
            val match = lineEntryPattern.matchEntire(lines[i])
            if (match != null) {
                val fileId = match.groupValues[2].toIntOrNull() ?: lastFileId
                lastFileId = fileId
                entries +=
                    SmapEntry(
                        inputStartLine = match.groupValues[1].toInt(),
                        fileId = fileId,
                        outputStartLine = match.groupValues[4].toInt(),
                        outputLineIncrement = match.groupValues[5].toIntOrNull() ?: 1,
                        repeatCount = match.groupValues[3].toIntOrNull() ?: 1,
                    )
            }
            i++
        }
        return KotlinSmap(entries, fileNamesById, sourceFilesById)
    }

    /** The index right after a `*S Kotlin` line, or null if the SMAP has no such stratum. */
    private fun findKotlinStratum(lines: List<String>): Int? {
        val index = lines.indexOf("*S Kotlin")
        return if (index < 0) null else index + 1
    }

    /**
     * Reads `*F` file entries starting at [start], until the `*L` line, returning the index of
     * that line. A `+ <id> <name>` entry is followed by a path line, kotlinc's spelling of the
     * origin class's internal name; a bare `<id> <name>` entry has no path line to skip. Each
     * entry's `<name>` goes into [sourceFilesById], and its path, or its name when it has no path,
     * into [fileNamesById].
     */
    private fun parseFileSection(
        lines: List<String>,
        start: Int,
        fileNamesById: MutableMap<Int, String>,
        sourceFilesById: MutableMap<Int, String>,
    ): Int {
        var i = start
        while (i < lines.size && lines[i] != "*L") {
            val line = lines[i]
            if (line.startsWith("+ ")) {
                val entry = line.substring(2)
                val id = entry.substringBefore(' ').toIntOrNull()
                if (id != null) sourceFilesById[id] = entry.substringAfter(' ', "")
                i++
                if (id != null && i < lines.size) fileNamesById[id] = lines[i]
                i++
            } else {
                val id = line.substringBefore(' ').toIntOrNull()
                if (id != null) {
                    fileNamesById[id] = line.substringAfter(' ', line)
                    sourceFilesById[id] = line.substringAfter(' ', line)
                }
                i++
            }
        }
        return i
    }
}
