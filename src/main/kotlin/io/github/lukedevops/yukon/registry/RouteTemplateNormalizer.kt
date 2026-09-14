package io.github.lukedevops.yukon.registry

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
     * [contextPath] (a servlet context path, for example) before the rest of the grammar runs.
     *
     * The result always starts with a slash and never ends with one, except the root template
     * `/`, which is also what an empty or blank [verbatim] normalises to. Runs of slashes
     * collapse to one. A path parameter such as `{id}` keeps its name; any regex constraint
     * inside the braces is dropped, and surrounding whitespace is trimmed. Ktor's `{name?}`
     * optional marker is kept. Any wildcard or tail segment, whichever framework's spelling wrote
     * it, collapses to one `*` segment: `*`, `**`, `{...}`, `{name...}`, and `{*name}` all mean
     * the same thing here. A `*` written inside a segment rather than as the whole segment, such
     * as a file extension wildcard like `*.txt`, is left exactly as written, since it is not a
     * whole-segment wildcard.
     */
    fun normalize(
        verbatim: String,
        contextPath: String? = null,
    ): String {
        val combined = "${contextPath.orEmpty()}/$verbatim"
        val segments = combined.split("/").filter { it.isNotBlank() }.map(::normalizeSegment)
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }

    /** Uppercases and trims [verb]. A null, blank, or already-`*` verb means unconstrained. */
    fun normalizeVerb(verb: String?): String {
        val trimmed = verb?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed == "*") "*" else trimmed.uppercase()
    }

    private fun normalizeSegment(segment: String): String {
        if (segment == "*" || segment == "**") return "*"
        if (segment.startsWith("{") && segment.endsWith("}")) {
            val inner = segment.substring(1, segment.length - 1).trim()
            if (inner.endsWith("...") || inner.startsWith("*")) return "*"
            return "{${inner.substringBefore(":").trim()}}"
        }
        return segment
    }
}
