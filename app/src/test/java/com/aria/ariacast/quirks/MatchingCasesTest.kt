package com.aria.ariacast.quirks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases the known-speakers bot also runs (resources/matching-cases.json is the same file as
 * known-speakers/tests/data/matching-cases.json). The app and the bot decide which entry a device
 * belongs to separately; this is what keeps the two from disagreeing.
 */
class MatchingCasesTest {
    private val root = Json.parseToJsonElement(TestSpeakers.text("matching-cases.json")).jsonObject

    private fun device(o: JsonObject) = DeviceIdentity(
        platform = o["platform"]?.jsonPrimitive?.contentOrNull,
        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        model = o["model"]?.jsonPrimitive?.contentOrNull,
        manufacturer = o["manufacturer"]?.jsonPrimitive?.contentOrNull,
    )

    @Test fun theAppAnswersEveryCaseLikeTheBot() {
        val list = KnownSpeakersFile.parse(root.getValue("list").toString())!!
        assertEquals(emptyList<String>(), list.skipped)
        KnownSpeakersStore.setForTesting(list)
        val cases = root.getValue("cases").jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val o = c.jsonObject
            val why = o.getValue("why").jsonPrimitive.content
            val d = device(o.getValue("device").jsonObject)
            assertEquals(why, o.getValue("matches").jsonArray.map { it.jsonPrimitive.content }, DeviceQuirks.matching(d).map { it.id })
            assertEquals(why, o["best"]?.jsonPrimitive?.contentOrNull, DeviceQuirks.bestMatch(d)?.id)
        }
    }
}
