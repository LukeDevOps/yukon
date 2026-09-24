package io.github.lukedevops.yukon.instrumentation.branch

import java.security.MessageDigest

/**
 * Derives the branch key of each kept branch outcome, and the site key of each kept site, from the
 * site's condition fingerprint. See ADRs 0031 and 0037.
 *
 * [compute] returns a key for every kept outcome it can name safely, keyed by
 * `(siteIndex, outcome offset)`. An outcome missing from the result has no key: the agent could
 * not name it safely, and a collector treats it as an outcome it has never seen.
 *
 * [computeSiteKeys] returns a key for every kept site, keyed by `siteIndex`. It uses the same rules,
 * so a site has a key exactly when its outcomes have keys. A site key digests the same input as a
 * branch key without the outcome token, under its own derivation tag, so it never equals a branch
 * key.
 *
 * Two tracked sites in one method collide when they share a [BranchSite.conditionFingerprint] and
 * [BranchSite.inlinedFromClassName]. Dropped sites count toward a collision, since whether a kept
 * site gets a key must depend on the method's bytecode alone, not on scope configuration. A site
 * with a null fingerprint never gets a key and never takes part in a collision group, so it costs
 * no other site its key.
 */
object BranchKeys {
    /** The derivation's version, folded into the digest input so a change to the derivation changes every key. */
    private const val DERIVATION_TAG = "v1"

    /** The site key derivation's own tag and version. It differs from [DERIVATION_TAG], so a site key never equals a branch key. */
    private const val SITE_DERIVATION_TAG = "site-v1"

    private const val SEPARATOR = "\u0000"

    /**
     * One method's tracked sites collide when they share a [conditionFingerprint] and
     * [inlinedFromClassName]. A switch's [conditionFingerprint] never covers its case keys, so
     * this identity does not need them.
     */
    private data class CollisionIdentity(
        val methodName: String,
        val methodDescriptor: String,
        val conditionFingerprint: String,
        val inlinedFromClassName: String?,
    )

    /**
     * Computes the branch key of every kept outcome of [sites], one class's analysed branch
     * sites, keyed by `(siteIndex, outcome offset)`. [className] is the class's own name, dotted.
     */
    fun compute(
        sites: List<BranchSite>,
        className: String,
    ): Map<Pair<Int, Int>, String> {
        val keys = mutableMapOf<Pair<Int, Int>, String>()
        for (nameable in nameableSites(sites)) {
            nameable.outcomeTokens.forEachIndexed { offset, outcomeToken ->
                keys[nameable.site.siteIndex to offset] =
                    digest(DERIVATION_TAG, className, nameable.site, nameable.fingerprint, outcomeToken)
            }
        }
        return keys
    }

    /**
     * Computes the site key of every kept site of [sites], one class's analysed branch sites, keyed
     * by `siteIndex`. [className] is the class's own name, dotted. A site is missing from the
     * result exactly when [compute] gives none of its outcomes a key.
     */
    fun computeSiteKeys(
        sites: List<BranchSite>,
        className: String,
    ): Map<Int, String> =
        nameableSites(sites).associate { nameable ->
            nameable.site.siteIndex to digest(SITE_DERIVATION_TAG, className, nameable.site, nameable.fingerprint, null)
        }

    /** A kept site that [compute] and [computeSiteKeys] can name, with its fingerprint and outcome tokens. */
    private class NameableSite(
        val site: BranchSite,
        val fingerprint: String,
        val outcomeTokens: List<String>,
    )

    /**
     * The kept sites of [sites] that can be named safely: each has a fingerprint, shares no
     * [CollisionIdentity] with another tracked site, dropped sites included, and has outcome tokens.
     */
    private fun nameableSites(sites: List<BranchSite>): List<NameableSite> {
        val collisionCounts = mutableMapOf<CollisionIdentity, Int>()
        for (site in sites) {
            val fingerprint = site.conditionFingerprint ?: continue
            val identity = CollisionIdentity(site.methodName, site.methodDescriptor, fingerprint, site.inlinedFromClassName)
            collisionCounts[identity] = (collisionCounts[identity] ?: 0) + 1
        }

        val nameable = mutableListOf<NameableSite>()
        for (site in sites) {
            if (site.dropReason != null) continue
            val fingerprint = site.conditionFingerprint ?: continue
            val identity = CollisionIdentity(site.methodName, site.methodDescriptor, fingerprint, site.inlinedFromClassName)
            if (collisionCounts.getValue(identity) > 1) continue
            val outcomeTokens = outcomeTokensOf(site) ?: continue
            nameable += NameableSite(site, fingerprint, outcomeTokens)
        }
        return nameable
    }

    /**
     * The outcome token of each of [site]'s outcomes, in offset order, or null when the site
     * cannot be named safely. A conditional's two outcomes are `taken` and `fallthrough`,
     * matching the taken and fall-through edges [BranchProbeMethodVisitor] emits. A switch's
     * `caseKeys` must carry exactly one entry per case outcome, with the default last; a switch
     * whose `caseKeys` is null or the wrong size gets no token for any of its outcomes.
     */
    private fun outcomeTokensOf(site: BranchSite): List<String>? {
        val caseKeys = site.caseKeys
        if (caseKeys == null) {
            if (site.outcomeCount != 2) return null
            return listOf("taken", "fallthrough")
        }
        if (caseKeys.size != site.outcomeCount - 1) return null
        return caseKeys.map { "case:$it" } + "default"
    }

    /**
     * Hex of the first 16 bytes of the SHA-256 digest of [className], [site]'s method name,
     * method descriptor, origin class, [fingerprint] and [outcomeToken], joined with a
     * `\u0000` separator none of them can contain. [tag] leads the text, so a later change to a
     * derivation changes the digest input, not only its output. A site key passes a null
     * [outcomeToken] and adds nothing after [fingerprint].
     */
    private fun digest(
        tag: String,
        className: String,
        site: BranchSite,
        fingerprint: String,
        outcomeToken: String?,
    ): String {
        val text =
            listOfNotNull(
                tag,
                className,
                site.methodName,
                site.methodDescriptor,
                site.inlinedFromClassName ?: "",
                fingerprint,
                outcomeToken,
            ).joinToString(SEPARATOR)
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }
}
