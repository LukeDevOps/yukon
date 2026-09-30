package dev.otherlode.config

/**
 * Derives an agent option's system property and environment variable names from its camelCase
 * key.
 *
 * Both names come from the same word split, so one option name always maps to exactly one
 * property name and one environment variable name. There is no separate list of names to keep in
 * sync by hand, and no way for a property and an environment variable to drift apart for the same
 * option.
 */
object OptionNames {
    private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /** `serviceName` becomes `otherlode.service.name`. */
    fun systemProperty(optionName: String): String = wordsOf(optionName).joinToString(".", prefix = "otherlode.") { it.lowercase() }

    /** `serviceName` becomes `OTHERLODE_SERVICE_NAME`. */
    fun environmentVariable(optionName: String): String = wordsOf(optionName).joinToString("_", prefix = "OTHERLODE_") { it.uppercase() }

    private fun wordsOf(optionName: String): List<String> = optionName.split(CAMEL_CASE_BOUNDARY)
}
