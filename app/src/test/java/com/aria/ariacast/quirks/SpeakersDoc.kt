package com.aria.ariacast.quirks

/** Renders the known-speaker table as docs/SPEAKERS.md. */
object SpeakersDoc {
    fun render(): String = buildString {
        appendLine("# Known speakers")
        appendLine()
        appendLine("<!-- Generated from app/src/main/java/com/aria/ariacast/quirks/DeviceQuirks.kt. Do not edit by hand:")
        appendLine("     run `UPDATE_SPEAKERS_DOC=1 ./gradlew :app:testGithubDebugUnitTest --tests '*SpeakersDocTest*'`. -->")
        appendLine()
        appendLine("How speakers behave with AriaCast, what you see when something is wrong, and what to do.")
        appendLine("Only behavior with evidence is listed; a speaker that is not here is untested, not broken.")
        appendLine()
        for (verdict in Verdict.entries) {
            appendLine("## ${verdict.label}")
            appendLine()
            val rows = DeviceQuirks.ALL.filter { it.verdict == verdict }
            if (rows.isEmpty()) {
                appendLine("Nothing recorded yet.")
                appendLine()
                continue
            }
            appendLine("| Speaker | Protocol | If it goes wrong | What to do | Since | Evidence |")
            appendLine("|---|---|---|---|---|---|")
            rows.forEach { k ->
                appendLine(
                    "| ${cell(k.speaker)} | ${cell(k.protocol)} | ${cell(k.symptoms.joinToString("<br>"))} | " +
                        "${cell(k.whatToDo ?: "")} | ${cell(k.since ?: "")} | ${cell(k.evidence)} |"
                )
            }
            appendLine()
        }
        appendLine("## Add a speaker")
        appendLine()
        appendLine("Long-press the speaker in the Send tab and choose **Report speaker** (or use the button in the")
        appendLine("connection doctor). It shares who the speaker is and the log lines about it, with addresses")
        appendLine("shortened. Paste that into a [new issue](https://github.com/AriaCast/AriaCast-app/issues/new) and")
        appendLine("say whether it worked.")
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
}
