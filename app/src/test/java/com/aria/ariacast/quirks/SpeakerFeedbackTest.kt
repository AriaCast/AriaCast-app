package com.aria.ariacast.quirks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeakerFeedbackTest {
    @Before fun loadTheList() { TestSpeakers.load() }

    @Test fun aSpeakerIsToldApartByProtocolAndName() {
        assertEquals("DLNA|den", SpeakerFeedback.key("DLNA", " Den "))
        assertEquals(SpeakerFeedback.key("DLNA", "DEN"), SpeakerFeedback.key("DLNA", "den"))
        assertFalse(SpeakerFeedback.key("DLNA", "Den") == SpeakerFeedback.key("AirPlay2", "Den"))
        assertEquals("-|den", SpeakerFeedback.key(null, "Den"))
    }

    @Test fun itAsksOnlyWhenAllIsInPlace() {
        assertTrue(SpeakerFeedback.shouldAsk(casting = true, stillInCast = true, alreadyAsked = false, platform = "DLNA"))
        assertFalse(SpeakerFeedback.shouldAsk(casting = false, stillInCast = true, alreadyAsked = false, platform = "DLNA"))
        assertFalse("removed from the cast meanwhile", SpeakerFeedback.shouldAsk(true, false, false, "DLNA"))
        assertFalse("never twice", SpeakerFeedback.shouldAsk(true, true, true, "DLNA"))
        assertFalse("a Sendspin server is not a speaker to judge", SpeakerFeedback.shouldAsk(true, true, false, "Sendspin"))
    }

    @Test fun aFailureIsAskedAboutOnlyForASingleSpeaker() {
        assertTrue(SpeakerFeedback.shouldAskAfterFailure(1, alreadyAsked = false, platform = "DLNA"))
        assertFalse("which of two failed?", SpeakerFeedback.shouldAskAfterFailure(2, false, "DLNA"))
        assertFalse("never twice", SpeakerFeedback.shouldAskAfterFailure(1, true, "DLNA"))
        assertFalse(SpeakerFeedback.shouldAskAfterFailure(1, false, "Sendspin"))
        assertFalse(SpeakerFeedback.shouldAskAfterFailure(0, false, "DLNA"))
    }

    @Test fun aMinuteIsTheWait() {
        assertEquals(60_000L, SpeakerFeedback.ASK_AFTER_MS)
    }

    @Test fun sharingIsOfferedUnlessTheListAlreadySaysSupported() {
        fun known(platform: String, name: String, model: String?) = DeviceQuirks.matching(DeviceIdentity(platform, name, model))
        assertTrue("not in the list", SpeakerFeedback.offerSharingAfterWorks(known("DLNA", "Den", "Acme Box")))
        assertFalse("listed as supported", SpeakerFeedback.offerSharingAfterWorks(known("DLNA", "Den", "WiiM Pro Plus")))
        assertTrue("listed with quirks: a confirmation is news", SpeakerFeedback.offerSharingAfterWorks(known("AirPlay2", "Den", "ShairportSync")))
    }

    @Test fun answersMapToTheFormsWords() {
        assertEquals("Supported", SpeakerFeedback.formLabel(Status.SUPPORTED))
        assertEquals("Supported with quirks", SpeakerFeedback.formLabel(Status.QUIRKS))
        assertEquals("Unsupported", SpeakerFeedback.formLabel(Status.UNSUPPORTED))
        val form = TestSpeakers.text("speaker-report-form.yml")
        Status.entries.forEach { assertTrue(form.contains("- ${SpeakerFeedback.formLabel(it)}\n")) }
    }

    private fun ledger(store: MutableMap<String, String> = mutableMapOf()) = FeedbackLedger({ store[it] }, { k, v -> store[k] = v })

    @Test fun theLedgerRemembersWhatWasAskedAndAnswered() {
        val store = mutableMapOf<String, String>()
        val l = ledger(store)
        assertFalse(l.asked("DLNA|den"))
        assertNull(l.answer("DLNA|den"))
        l.markAsked("DLNA|den", 123L)
        l.record("DLNA|den", Status.QUIRKS)
        assertTrue(ledger(store).asked("DLNA|den"))   // a new ledger over the same storage: it persists
        assertEquals(Status.QUIRKS, ledger(store).answer("DLNA|den"))
        assertFalse(ledger(store).asked("DLNA|other"))
    }

    @Test fun anAnswerFromAnOlderOrDamagedStoreIsIgnored() {
        assertNull(ledger(mutableMapOf("answer.x" to "great")).answer("x"))
    }
}
