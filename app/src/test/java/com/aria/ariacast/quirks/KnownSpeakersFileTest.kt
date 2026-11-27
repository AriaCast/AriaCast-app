package com.aria.ariacast.quirks

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownSpeakersFileTest {
    private fun entry(
        id: String = "wiim-dlna",
        status: String = "supported",
        mentions: String = """["wiim"]""",
        evidence: String = "an issue",
        extra: String = "",
    ) = """{"id":"$id","speaker":"WiiM","protocol":"DLNA","status":"$status","match":{"platform":"DLNA","mentions":$mentions},"evidence":"$evidence"$extra}"""

    private fun file(vararg entries: String, schema: Int = 2, version: Int = 3) =
        """{"schema":$schema,"version":$version,"updated":"2026-11-27","speakers":[${entries.joinToString(",")}]}"""

    @Test fun readsAGoodFile() {
        val list = KnownSpeakersFile.parse(file(entry()))!!
        assertEquals(3, list.version)
        assertEquals("wiim-dlna", list.speakers.single().id)
        assertEquals(Status.SUPPORTED, list.speakers.single().status)
        assertTrue(list.skipped.isEmpty())
    }

    @Test fun refusesWholeFilesThatCannotBeTrusted() {
        assertNull(KnownSpeakersFile.parse("not json"))
        assertNull(KnownSpeakersFile.parse(file(entry(), schema = 3)))
        assertNull(KnownSpeakersFile.parse(file(entry(), version = -1)))
        assertNull(KnownSpeakersFile.parse(file(entry(evidence = "x".repeat(KnownSpeakersFile.MAX_BYTES)))))
        assertNull(KnownSpeakersFile.parse(""))
    }

    @Test fun skipsABadEntryAndKeepsTheRest() {
        val list = KnownSpeakersFile.parse(
            file(
                entry(id = "ok-one"),
                entry(id = "BAD ID"),
                entry(id = "ok-one"),
                entry(id = "weird-status", status = "great"),
                entry(id = "no-words", mentions = "[]"),
                entry(id = "short-words", mentions = """["ab","x"]"""),
                entry(id = "no-evidence", evidence = " "),
                entry(id = "ok-two"),
            )
        )!!
        assertEquals(listOf("ok-one", "ok-two"), list.speakers.map { it.id })
        assertEquals(
            listOf("BAD ID: bad id", "ok-one: duplicate id", "weird-status: unknown status", "no-words: needs at least one word to match",
                "short-words: needs at least one word to match", "no-evidence: no evidence"),
            list.skipped,
        )
    }

    @Test fun anEntryCannotMatchEverything() {
        // Without a word to look for, an entry with a setting would apply to every speaker.
        assertTrue(KnownSpeakersFile.parse(file(entry(mentions = "[]", extra = ""","config":{"sendMetadata":false}""")))!!.speakers.isEmpty())
    }

    @Test fun mentionsAreLowercasedAndMatchCaseInsensitively() {
        val k = KnownSpeakersFile.parse(file(entry(mentions = """["WiiM"]""")))!!.speakers.single()
        assertEquals(listOf("wiim"), k.match.mentions)
        assertTrue(k.appliesTo(DeviceIdentity("DLNA", "LIVING ROOM WIIM")))
        assertFalse(k.appliesTo(DeviceIdentity("AirPlay2", "WiiM")))
        assertFalse(k.appliesTo(DeviceIdentity("DLNA", "Sonos")))
    }

    @Test fun anEntryWithoutAProtocolMatchesAnyProtocol() {
        val k = KnownSpeakersFile.parse(
            """{"schema":2,"version":1,"speakers":[{"id":"any-proto","speaker":"S","protocol":"p","status":"unsupported","match":{"mentions":["thing"]},"evidence":"e"}]}"""
        )!!.speakers.single()
        assertTrue(k.appliesTo(DeviceIdentity("DLNA", "a thing")))
        assertTrue(k.appliesTo(DeviceIdentity(null, "a thing")))
    }

    @Test fun configValuesOutsideTheirLimitsAreDroppedNotClamped() {
        val config = KnownSpeakersFile.parse(
            file(entry(extra = ""","config":{"defaultDelayMs":99999,"dlnaContentType":"text/html","sendMetadata":false,"dlnaSkipInitialStop":true,"unknownKnob":1}"""))
        )!!.speakers.single().config
        assertNull(config.defaultDelayMs)
        assertNull(config.dlnaContentType)
        assertEquals(false, config.sendMetadata)
        assertEquals(true, config.dlnaSkipInitialStop)
    }

    @Test fun validConfigValuesAreKept() {
        val config = KnownSpeakersFile.parse(
            file(entry(extra = ""","config":{"defaultDelayMs":350,"dlnaContentType":"audio/x-wav","dlnaPlayAfterSetUri":false}"""))
        )!!.speakers.single().config
        assertEquals(350, config.defaultDelayMs)
        assertEquals("audio/x-wav", config.dlnaContentType)
        assertEquals(false, config.dlnaPlayAfterSetUri)
    }

    @Test fun textIsCleanedAndCapped() {
        val k = KnownSpeakersFile.parse(file(entry(evidence = "ok\\u0000\\u001b[31m" + "y".repeat(1000))))!!.speakers.single()
        assertTrue(k.evidence.none { it < ' ' })
        assertEquals(320, k.evidence.length)
    }

    @Test fun onlyTwoLetterLanguagesAreKeptAndEnglishIsTheFallback() {
        val k = KnownSpeakersFile.parse(
            file(entry(extra = ""","notes":{"en":"English","it":"Italiano","EN":"x","english":"y","":"z"}"""))
        )!!.speakers.single()
        assertEquals(setOf("en", "it"), k.notes.keys)
        assertEquals("Italiano", k.noteFor(Locale.ITALIAN))
        assertEquals("English", k.noteFor(Locale.GERMAN))
        assertNull(KnownSpeakersFile.parse(file(entry()))!!.speakers.single().noteFor(Locale.ENGLISH))
    }

    @Test fun unknownFieldsAreIgnoredSoNewerFilesStillLoad() {
        val list = KnownSpeakersFile.parse("""{"schema":2,"version":2,"future":true,"speakers":[${entry(extra = ""","newField":{"a":1}""")}]}""")
        assertNotNull(list)
        assertEquals(1, list!!.speakers.size)
    }

    @Test fun moreThanTheLimitIsCutOff() {
        val many = (1..KnownSpeakersFile.MAX_ENTRIES + 20).map { entry(id = "speaker-$it") }.toTypedArray()
        assertEquals(KnownSpeakersFile.MAX_ENTRIES, KnownSpeakersFile.parse(file(*many))!!.speakers.size)
    }

    @Test fun reportsAndTheLockAreReadAndBadCountsAreDropped() {
        val k = KnownSpeakersFile.parse(
            file(entry(extra = ""","reports":{"supported":3,"quirks":1,"unsupported":-2,"great":9,"quirks2":1},"locked":true"""))
        )!!.speakers.single()
        assertEquals(mapOf(Status.SUPPORTED to 3, Status.QUIRKS to 1), k.reports)
        assertTrue(k.locked)
        val plain = KnownSpeakersFile.parse(file(entry()))!!.speakers.single()
        assertEquals(emptyMap<Status, Int>(), plain.reports)
        assertFalse(plain.locked)
    }

    @Test fun anAbsurdReportCountIsDropped() {
        val k = KnownSpeakersFile.parse(file(entry(extra = ""","reports":{"supported":99999999}""")))!!.speakers.single()
        assertEquals(emptyMap<Status, Int>(), k.reports)
    }

    @Test fun aFileOfTheOldSchemaIsRefused() {
        assertNull(KnownSpeakersFile.parse(file(entry(), schema = 1)))
    }

    @Test fun whatTheBotProposesIsAcceptedByTheApp() {
        // bot-output.json is what the known-speakers bot writes for its sample report (that
        // repository's tests check the bot still produces exactly this file).
        val list = KnownSpeakersFile.parse(TestSpeakers.text("bot-output.json"))!!
        assertEquals(emptyList<String>(), list.skipped)
        assertEquals(listOf("wiim-dlna-restart", "open-box-dlna", "acme-play-3-dlna"), list.speakers.map { it.id })
        val acme = list.speakers.last()
        assertEquals(Status.QUIRKS, acme.status)
        assertEquals(mapOf(Status.QUIRKS to 1), acme.reports)
        assertFalse(acme.locked)
        assertTrue(acme.appliesTo(DeviceIdentity("DLNA", "Den", "Play 3 Pro", "Acme Audio")))
        assertFalse(acme.appliesTo(DeviceIdentity("AirPlay2", "Den", "Play 3 Pro")))
        assertTrue(list.speakers.first().locked)
    }

    @Test fun theShippedSnapshotOfTheListHasNoRejectedEntries() {
        assertEquals(emptyList<String>(), TestSpeakers.load().skipped)
    }
}
