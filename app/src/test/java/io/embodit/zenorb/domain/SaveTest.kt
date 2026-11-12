package io.embodit.zenorb.domain

import io.embodit.zenorb.data.SaveCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class SaveTest {
    @Test fun roundTrip() {
        val s = Saved(listOf(Day("2026-11-11", 120, 12, 10, 8, 2), Day("2026-11-12")), FocusActive(1L, 1500_000L, 3000L, 99L))
        assertEquals(s, SaveCodec.decode(SaveCodec.encode(s)))
        val n = Saved(emptyList(), null)
        assertEquals(n, SaveCodec.decode(SaveCodec.encode(n)))
    }
}
