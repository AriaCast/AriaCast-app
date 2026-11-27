package com.aria.ariacast.quirks

/** Renders the list of known speakers as docs/SPEAKERS.md. */
object SpeakersDoc {
    fun render(list: KnownSpeakersFile): String = buildString {
        appendLine("# Known speakers")
        appendLine()
        appendLine("<!-- Generated from data/speakers.json (list version ${list.version}). Do not edit by hand:")
        appendLine("     change the JSON, then run `UPDATE_SPEAKERS_DOC=1 ./gradlew :app:testGithubDebugUnitTest --tests '*SpeakersDocTest*'`. -->")
        appendLine()
        appendLine("How speakers behave with AriaCast, what you see when something is wrong, and what to do.")
        appendLine("Only behavior with evidence is listed; a speaker that is not here is untested, not broken.")
        appendLine("The app reads the same list (Settings → Known speakers) and keeps it up to date.")
        appendLine()
        for (verdict in Verdict.entries) {
            appendLine("## ${verdict.label}")
            appendLine()
            val rows = list.speakers.filter { it.verdict == verdict }
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
        appendLine("In the app, long-press the speaker in the Send tab and choose **Report speaker**, or answer the")
        appendLine("question that appears the first time you cast to a new one. It opens a pre-filled")
        appendLine("[issue](https://github.com/AriaCast/AriaCast-app/issues/new?template=speaker-report.yml); the logs are")
        appendLine("copied for you to paste in. A maintainer reviews it and it joins the list.")
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")
}
