package com.aria.ariacast.quirks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersDocTest {
    // Unit tests run from the module directory (app/).
    private val file = File("../docs/SPEAKERS.md")

    @Test fun docIsInSyncWithTheTable() {
        val expected = SpeakersDoc.render()
        if (System.getenv("UPDATE_SPEAKERS_DOC") == "1") file.writeText(expected)
        assertEquals(
            "docs/SPEAKERS.md is out of date. Regenerate it with: " +
                "UPDATE_SPEAKERS_DOC=1 ./gradlew :app:testGithubDebugUnitTest --tests '*SpeakersDocTest*'",
            expected, file.readText(),
        )
    }

    @Test fun entriesAreUniqueAndBackedByEvidence() {
        assertEquals(DeviceQuirks.ALL.size, DeviceQuirks.ALL.map { it.id }.toSet().size)
        DeviceQuirks.ALL.forEach { assertTrue("${it.id} has no evidence", it.evidence.isNotBlank()) }
    }

    @Test fun anythingThatIsNotPlainGoodSaysWhatTheUserWillSee() {
        DeviceQuirks.ALL.filter { it.verdict != Verdict.GOOD }.forEach {
            assertTrue("${it.id} lists no symptoms", it.symptoms.isNotEmpty())
        }
    }
}
