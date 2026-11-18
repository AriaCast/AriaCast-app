package com.aria.ariacast

/**
 * Turns the packet log into a report ready to paste into a GitHub issue: what the app and
 * phone are, which speakers the cast went to (with the model/features details a maintainer
 * needs), the lines that look like problems, then the full log, oldest first, in a
 * collapsible block. Pure Kotlin so it can be unit-tested.
 */
object PacketLogExport {
    /** Logged by AudioCastService when a cast starts; "last session" exports start here. */
    const val SESSION_MARKER = "Cast session started"
    /** Logged once per speaker of a session, followed by its details. */
    const val SPEAKER_PREFIX = "Speaker: "

    enum class Scope { LAST_SESSION, ALL }

    data class Environment(
        val appVersion: String,
        val variant: String,
        val android: String,
        val device: String,
        /** Protocol name to whether discovery/serving is on. */
        val protocols: List<Pair<String, Boolean>>,
    )

    /** Details worth reporting from a speaker's discovery record (TXT keys and our own fields). */
    private val SPEAKER_KEYS = listOf("model", "am", "srcvers", "vs", "features", "ft", "flags", "sf", "pw", "et", "cn", "md")

    fun speakerLine(name: String, platform: String?, host: String, port: Int, extra: String?): String {
        val fields = ExtraFields.parse(extra)
        val details = SPEAKER_KEYS.mapNotNull { k -> fields[k]?.let { "$k=$it" } }
        return buildString {
            append(SPEAKER_PREFIX).append(name).append(" · ").append(platform ?: "AriaCast")
            append(" · ").append(host).append(if (port > 0) ":$port" else "")
            if (details.isNotEmpty()) append(" · ").append(details.joinToString(" "))
        }
    }

    private val PROBLEM = Regex(
        "\\b(fail(ed|ure|s)?|error|rejected|refused|denied|dropped|lost|timeout|timed out|crash(ed)?|unsupported|" +
            "not initialized|required|unauthorized|busy|silent|underruns=[1-9])",
        RegexOption.IGNORE_CASE,
    )

    /** [logsNewestFirst] as PacketLogger keeps them. */
    fun build(env: Environment, logsNewestFirst: List<PacketLog>, scope: Scope, exportedAt: String): String {
        val all = logsNewestFirst.asReversed()
        val markerAt = all.indexOfLast { it.message.startsWith(SESSION_MARKER) }
        val lastSessionFound = markerAt >= 0
        val logs = if (scope == Scope.LAST_SESSION && lastSessionFound) all.drop(markerAt) else all
        val speakers = logs.filter { it.message.startsWith(SPEAKER_PREFIX) }
            .map { it.message.removePrefix(SPEAKER_PREFIX) }.distinct()
        val problems = logs.filter { PROBLEM.containsMatchIn(it.message) && !it.message.startsWith(SPEAKER_PREFIX) }

        return buildString {
            appendLine("### AriaCast diagnostic report")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| App | ${cell(env.appVersion)} (${cell(env.variant)}) |")
            appendLine("| Phone | ${cell(env.device)}, ${cell(env.android)} |")
            appendLine("| Protocols on | ${cell(env.protocols.filter { it.second }.joinToString(", ") { it.first }.ifEmpty { "AriaCast only" })} |")
            appendLine("| Log | ${scopeLabel(scope, lastSessionFound)}, ${logs.size} lines, exported $exportedAt |")
            appendLine()
            if (speakers.isNotEmpty()) {
                appendLine("#### Speakers")
                speakers.forEach { appendLine("- `${it.replace("`", "'")}`") }
                appendLine()
            }
            appendLine("#### Possible problems (${problems.size})")
            if (problems.isEmpty()) appendLine("None spotted in the log.")
            else {
                appendLine("```text")
                problems.takeLast(MAX_PROBLEMS).forEach { appendLine(line(it)) }
                if (problems.size > MAX_PROBLEMS) appendLine("… ${problems.size - MAX_PROBLEMS} earlier ones in the full log")
                appendLine("```")
            }
            appendLine()
            appendLine("<details><summary>Full log (${logs.size} lines, oldest first)</summary>")
            appendLine()
            appendLine("```text")
            logs.forEach { appendLine(line(it)) }
            appendLine("```")
            appendLine()
            appendLine("</details>")
        }
    }

    private const val MAX_PROBLEMS = 40

    private fun scopeLabel(scope: Scope, found: Boolean) = when {
        scope == Scope.ALL -> "everything"
        found -> "last cast session"
        else -> "everything (no cast session in the log)"
    }

    internal fun line(l: PacketLog): String =
        "${l.timestamp} ${l.direction.name.padEnd(3)} ${l.type.name.padEnd(9)}" +
            (if (l.count > 1) " x${l.count}" else "") + " " + l.message.replace("```", "'''")

    private fun cell(s: String) = s.replace("|", "/")
}
