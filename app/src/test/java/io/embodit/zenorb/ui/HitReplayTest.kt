package io.embodit.zenorb.ui

import io.embodit.zenorb.ui.orb.Names
import io.embodit.zenorb.ui.orb.input.parseLoggedPath
import io.embodit.zenorb.ui.orb.input.resolvePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** build.md 命中回放: real hit chains (hit node first, then parents) resolve to the orb, nothing else takes hits. */
class HitReplayTest {
    @Test fun hitsResolveToTheOrb() {
        for (style in listOf("GLAZE", "BRASS", "MOSS")) {
            assertEquals(Names.ORB, resolvePath(listOf("part0", "orbModel:$style", "orb", "content", "root"), Names::target))
            assertEquals(Names.ORB, resolvePath(parseLoggedPath("part0 < world < orbModel:$style < orb < content < root"), Names::target))
            assertNull(resolvePath(listOf("part0", "cradle:$style", "content", "root"), Names::target))
            assertNull(resolvePath(listOf("arc:$style:3", "arc:$style", "content"), Names::target))
        }
        assertEquals(Names.ORB, resolvePath(listOf("orb", "content"), Names::target))
        // the recall stone (design v5 C8): the pebble and its dots resolve to it
        for (n in listOf("pebble", "dot-1", "dot0", "dot1")) assertEquals(Names.RECALL, resolvePath(listOf(n, "recall", "content", "root"), Names::target))
        assertNull(resolvePath(listOf("hover", "content", "root"), Names::target))
        for (n in listOf("shadow", "ringThin", "ringPivot", "tag", "caption")) assertNull(resolvePath(listOf(n, "content", "root"), Names::target))
    }
}
