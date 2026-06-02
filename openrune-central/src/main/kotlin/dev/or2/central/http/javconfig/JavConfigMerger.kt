package dev.or2.central.http.javconfig

/**
 * Merges overrides into a Jagex `.ws` jav config body. Lines are matched by logical key
 * (`title`, `codebase`, `param=25`, `msg=ok`, …). Only keys present in [overrides] are changed.
 */
object JavConfigMerger {

    fun lineKey(line: String): String? {
        val trimmed = line.trimEnd('\r').trim()
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("param=") -> {
                val second = trimmed.indexOf('=', startIndex = "param=".length)
                if (second < 0) return null
                trimmed.substring(0, second)
            }
            trimmed.startsWith("msg=") -> {
                val second = trimmed.indexOf('=', startIndex = "msg=".length)
                if (second < 0) return null
                trimmed.substring(0, second)
            }
            else -> {
                val eq = trimmed.indexOf('=')
                if (eq <= 0) return null
                trimmed.substring(0, eq)
            }
        }
    }

    fun valueForLine(line: String, key: String): String? {
        val trimmed = line.trimEnd('\r').trim()
        return when {
            key.startsWith("param=") -> {
                val prefix = "$key="
                if (!trimmed.startsWith(prefix)) return null
                trimmed.removePrefix(prefix)
            }
            key.startsWith("msg=") -> {
                val prefix = "$key="
                if (!trimmed.startsWith(prefix)) return null
                trimmed.removePrefix(prefix)
            }
            else -> {
                val prefix = "$key="
                if (!trimmed.startsWith(prefix)) return null
                trimmed.removePrefix(prefix)
            }
        }
    }

    fun formatLine(key: String, value: String): String =
        when {
            key.startsWith("param=") -> "param=${key.removePrefix("param=")}=$value"
            key.startsWith("msg=") -> "msg=${key.removePrefix("msg=")}=$value"
            else -> "$key=$value"
        }

    fun merge(remoteBody: String, overrides: Map<String, String>): String {
        if (overrides.isEmpty()) {
            return normalizeNewlines(remoteBody)
        }

        val lines = normalizeNewlines(remoteBody).split('\n').toMutableList()
        val applied = mutableSetOf<String>()

        for (i in lines.indices) {
            val key = lineKey(lines[i]) ?: continue
            val override = overrides[key] ?: continue
            lines[i] = formatLine(key, override)
            applied.add(key)
        }

        for ((key, value) in overrides) {
            if (key in applied) continue
            lines.add(formatLine(key, value))
        }

        return lines.joinToString("\n")
    }

    fun parseOverrideEntryLine(line: String): Pair<String, String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val key = lineKey(trimmed) ?: return null
        val value = valueForLine(trimmed, key) ?: return null
        return key to value
    }

    fun configPropSuffixToLineKey(suffix: String): String =
        when {
            suffix.startsWith("param.") ->
                "param=${suffix.removePrefix("param.")}"
            suffix.startsWith("msg.") ->
                "msg=${suffix.removePrefix("msg.")}"
            else -> suffix
        }

    private fun normalizeNewlines(body: String): String =
        body.replace("\r\n", "\n").replace('\r', '\n')
}
