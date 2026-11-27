package com.aria.ariacast.quirks

/**
 * A copy of the real list (AriaCast/known-speakers), read from the test resources and put in use
 * for a test. It is a snapshot: refresh src/test/resources/speakers.json when the list gains
 * entries a test relies on.
 */
object TestSpeakers {
    fun text(name: String): String =
        TestSpeakers::class.java.getResource("/$name")?.readText() ?: error("missing test resource $name")

    fun load(): KnownSpeakersFile {
        val parsed = KnownSpeakersFile.parse(text("speakers.json")) ?: error("speakers.json does not parse")
        KnownSpeakersStore.setForTesting(parsed)
        return parsed
    }
}
