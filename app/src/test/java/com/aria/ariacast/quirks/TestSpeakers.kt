package com.aria.ariacast.quirks

import java.io.File

/** The real list, read from data/speakers.json, put in use for a test. Tests run from app/. */
object TestSpeakers {
    val file = File("../data/speakers.json")

    fun load(): KnownSpeakersFile {
        val parsed = KnownSpeakersFile.parse(file.readText()) ?: error("data/speakers.json does not parse")
        KnownSpeakersStore.setForTesting(parsed)
        return parsed
    }
}
