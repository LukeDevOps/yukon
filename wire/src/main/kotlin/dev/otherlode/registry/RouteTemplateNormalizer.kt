package dev.otherlode.registry

/**
 * Turns a framework's own route spelling into one identity template, so the same endpoint
 * compares equal across frameworks and across differently spelled path parameters.
 *
 * The grammar is narrow on purpose: strip regex constraints, collapse wildcards, keep everything
 * else as written. A framework's original spelling still travels on the wire for display; only
 * the identity used to merge endpoints across instances and frameworks goes through this.
 */
object RouteTemplateNormalizer {
    /**
     * Produces the identity route template for [verbatim], optionally prefixed with
     * [contextPath] before the rest of the grammar runs.
     *
     * The result always starts with a slash and never ends with one, except the root template
     * `/`, which is also what an empty or blank [verbatim] normalises to. Runs of slashes
     * collapse to one, and a slash inside a parameter's braces belongs to its regex, not to the
     * path. Every path parameter such as `{id}` keeps its name and the literal text around it,
     * so `{id:\d+}.{ext}` becomes `{id}.{ext}`; any regex constraint inside the braces is
     * dropped, and surrounding whitespace is trimmed. Ktor's `{name?}` optional marker is kept.
     * Any wildcard or tail segment, whichever framework's spelling wrote it, collapses to one `*`
     * segment: `*`, `**`, `{...}`, `{name...}`, and `{*name}` all mean the same thing here. A `*`
     * written inside a segment rather than as the whole segment, such as a file extension
     * wildcard like `*.txt`, is left exactly as written, since it is not a whole-segment wildcard.
     * An unclosed brace is kept as written.
     */
    fun normalize(
        verbatim: String,
        contextPath: String? = null,
    ): String {
        val combined = "${contextPath.orEmpty()}/$verbatim"
        val segments = splitOutsideBraces(combined).filter { it.isNotBlank() }.map(::normalizeSegment)
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }

    /** Uppercases and trims [verb]. A null, blank, or already-`*` verb means unconstrained. */
    fun normalizeVerb(verb: String?): String {
        val trimmed = verb?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == "*") "*" else trimmed.uppercase()
    }

    private fun splitOutsideBraces(path: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (c in path) {
            when {
                c == '/' && depth == 0 -> {
                    segments += current.toString()
                    current.setLength(0)
                }

                else -> {
                    if (c == '{') depth++
                    if (c == '}' && depth > 0) depth--
                    current.append(c)
                }
            }
        }
        segments += current.toString()
        return segments
    }

    private fun normalizeSegment(segment: String): String {
        if (segment == "*" || segment == "**") return "*"
        val out = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val close = if (segment[i] == '{') matchingBrace(segment, i) else -1
            if (close < 0) {
                out.append(segment[i])
                i++
                continue
            }
            val inner = segment.substring(i + 1, close).trim()
            val wholeSegment = i == 0 && close == segment.length - 1
            if (wholeSegment && (inner.endsWith("...") || inner.startsWith("*"))) return "*"
            out.append('{').append(inner.substringBefore(":").trim()).append('}')
            i = close + 1
        }
        return out.toString()
    }

    /** The index of the brace closing the one at [open], or -1 when it never closes. */
    private fun matchingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        for (i in open until text.length) {
            if (text[i] == '{') depth++
            if (text[i] == '}' && --depth == 0) return i
        }
        return -1
    }
}
