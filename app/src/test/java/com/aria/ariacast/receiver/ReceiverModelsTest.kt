package com.aria.ariacast.receiver

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverModelsTest {
    private fun parse(json: String) = ReceiverServer.parseMetadata(ReceiverServer.json.parseToJsonElement(json).jsonObject)

    @Test fun acceptsSnakeAndCamelCase() {
        val a = parse("""{"title":"T","artwork_url":"http://x/a.jpg","duration_ms":1000,"is_playing":true}""")
        val b = parse("""{"title":"T","artworkUrl":"http://x/a.jpg","durationMs":1000,"isPlaying":true}""")
        assertEquals(a, b)
        assertEquals("http://x/a.jpg", a.artworkUrl)
        assertEquals(true, a.isPlaying)
    }

    @Test fun partialUpdatesKeepOtherFields() {
        val start = ReceiverMetadata(title = "Song", artist = "Band", isPlaying = true)
        val (m, changed) = start.merge(MetadataUpdate(isPlaying = false))
        assertEquals("Song", m.title)
        assertEquals("Band", m.artist)
        assertFalse(m.isPlaying)
        assertFalse(changed)
        val (_, changed2) = m.merge(MetadataUpdate(title = "Other"))
        assertTrue(changed2)
    }
}
