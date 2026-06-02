package dev.or2.central.account

import dev.or2.central.display.DisplayNameFormatResult
import dev.or2.central.display.DisplayNamePolicy
import dev.or2.sql.OpenRuneSql
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

public object AccountNameAuthPolicy {

    public const val MAX_CANONICAL_LENGTH: Int = 12
    public const val MAX_RAW_UTF_BYTES: Int = 64

    private const val DECEPTIVE_FRAGMENTS_BASE =
        "profanity/account_name_deceptive_fragments.txt"

    private const val DECEPTIVE_FRAGMENTS_CUSTOM =
        "profanity/account_name_deceptive_fragments_custom.txt"

    private val deceptiveFragments: Set<String> =
        loadDeceptiveFragmentsFromClasspath()

    public fun preloadWorldLinkDeceptiveFragments() {
        deceptiveFragmentsForListing()
    }


    public fun deceptiveFragmentsForListing(): Set<String> = deceptiveFragments

    public fun parseBadWordLines(text: String): Set<String> =
        text.lineSequence().map(::stripCommentAndTrim).filter(String::isNotEmpty).toSet()


    public fun rawWorldLinkUsernameHasIllegalCharacters(rawTrimmed: String): Boolean =
        rawTrimmed.any(::isIllegalLoginCharacter)

    /**
     * Removes punctuation, collapses whitespace, and trims the result.
     *
     * Example:
     * `Rs@Player` -> `RsPlayer`
     */
    public fun canonicalize(rawTrimmed: String): String {
        val result = StringBuilder()
        var lastWasSpace = false

        for (ch in rawTrimmed) {
            when {
                ch.isLetterOrDigit() -> {
                    result.append(ch)
                    lastWasSpace = false
                }

                ch.isWhitespace() -> {
                    if (result.isNotEmpty() && !lastWasSpace) {
                        result.append(' ')
                        lastWasSpace = true
                    }
                }

                else -> {
                    lastWasSpace = false
                }
            }
        }

        return result.toString().trim()
    }

    public fun collisionKey(canonical: String): String =
        buildString {
            canonical.forEach { ch ->
                if (ch.isLetterOrDigit()) {
                    append(ch.lowercaseChar())
                }
            }
        }

    public fun validateCanonical(
        canonical: String,
        badWordRoots: Set<String>,
    ): DisplayNameFormatResult {
        if (canonical.isEmpty()) {
            return DisplayNameFormatResult.Empty
        }

        if (canonical.length > MAX_CANONICAL_LENGTH) {
            return DisplayNameFormatResult.TooLong(canonical.length)
        }

        findDeceptiveFragment(canonical)?.let { fragment ->
            return if (fragment.equals("mod", ignoreCase = true)) {
                DisplayNameFormatResult.ContainsMod
            } else {
                DisplayNameFormatResult.Deceptive(fragment)
            }
        }

        DisplayNamePolicy.findProfanity(canonical, badWordRoots)?.let {
            return DisplayNameFormatResult.Profanity(it)
        }

        return DisplayNameFormatResult.Ok
    }

    public fun policyScriptLines(
        reason: DisplayNameFormatResult,
    ): Triple<String, String, String> =
        when (reason) {
            DisplayNameFormatResult.Empty ->
                Triple(
                    "That name is not usable.",
                    "Use letters, numbers, and spaces only.",
                    "Try a different name.",
                )

            is DisplayNameFormatResult.TooLong ->
                Triple(
                    "That name is too long.",
                    "Account names can be at most $MAX_CANONICAL_LENGTH characters after cleaning.",
                    "Shorten it and try again.",
                )

            DisplayNameFormatResult.ContainsMod ->
                Triple(
                    "That name is not allowed.",
                    "Names cannot contain the sequence \"mod\".",
                    "Pick a different name.",
                )

            is DisplayNameFormatResult.Deceptive ->
                Triple(
                    "That name is not allowed.",
                    "It looks like staff or official branding.",
                    "Pick a different name.",
                )

            is DisplayNameFormatResult.Profanity ->
                Triple(
                    "That name is not allowed.",
                    "It contains blocked language.",
                    "Pick a different name.",
                )

            DisplayNameFormatResult.InvalidLoginCharacters ->
                Triple(
                    "That name is not usable.",
                    "Remove odd symbols or try a simpler spelling.",
                    "",
                )

            DisplayNameFormatResult.Ok ->
                Triple("", "", "")
        }

    private fun findDeceptiveFragment(canonical: String): String? {
        val lower = canonical.lowercase()

        return deceptiveFragments.firstOrNull { fragment ->
            fragment.isNotEmpty() &&
                    lower.contains(fragment.lowercase())
        }
    }

    private fun stripCommentAndTrim(line: String): String =
        line.substringBefore('#').trim()

    private fun isIllegalLoginCharacter(ch: Char): Boolean =
        !ch.isLetterOrDigit() &&
                ch != ' ' &&
                ch != '\t'

    private fun readResourceLines(resourcePath: String): Set<String> {
        val stream =
            OpenRuneSql::class.java.classLoader
                .getResourceAsStream(resourcePath)
                ?: return emptySet()

        return stream.use { input ->
            val text =
                InputStreamReader(input, StandardCharsets.UTF_8)
                    .use(InputStreamReader::readText)

            parseBadWordLines(text)
        }
    }

    private fun loadDeceptiveFragmentsFromClasspath(): Set<String> {
        val base = readResourceLines(DECEPTIVE_FRAGMENTS_BASE)
        val custom = readResourceLines(DECEPTIVE_FRAGMENTS_CUSTOM)

        return custom + base
    }
}