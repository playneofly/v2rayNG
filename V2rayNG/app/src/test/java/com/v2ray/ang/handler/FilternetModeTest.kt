package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FILTERNET: who owns the tunnel.
 *
 * The bug this guards against is the one the user hit: starting the deep hunt
 * from the private tab left the home tab believing it was connected, so both
 * screens offered to disconnect the same service and the home tab's profile
 * selection was quietly replaced by a scan result.
 *
 * MMKV is not available in a JVM test. Every persistence call in
 * [FilternetMode] is wrapped in runCatching precisely so the in-memory state
 * machine still works when the store is missing - which is what these tests
 * exercise, and is also the behaviour on a device whose storage is locked
 * before first unlock.
 */
class FilternetModeTest {

    @Before
    fun clearOwner() {
        FilternetMode.resetForTest()
    }

    @Test
    fun `starts owned by nobody`() {
        assertEquals(FilternetMode.Owner.NONE, FilternetMode.current())
        assertFalse(FilternetMode.isInternal())
    }

    @Test
    fun `the private tab can take the tunnel`() {
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)

        assertEquals(FilternetMode.Owner.INTERNAL, FilternetMode.current())
        assertTrue(FilternetMode.isInternal())
    }

    @Test
    fun `the home tab is not the private tab`() {
        FilternetMode.claim(FilternetMode.Owner.MAIN)

        assertEquals(FilternetMode.Owner.MAIN, FilternetMode.current())
        // The whole point: MAIN must never satisfy the internal check, or the
        // home tab would hide itself behind its own tunnel.
        assertFalse(FilternetMode.isInternal())
    }

    @Test
    fun `releasing hands it back to nobody`() {
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)
        FilternetMode.release()

        assertEquals(FilternetMode.Owner.NONE, FilternetMode.current())
        assertFalse(FilternetMode.isInternal())
    }

    @Test
    fun `ownership can move between tabs without passing through none`() {
        FilternetMode.claim(FilternetMode.Owner.MAIN)
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)

        assertTrue(FilternetMode.isInternal())
    }

    @Test
    fun `the flow carries the current owner`() {
        assertEquals(FilternetMode.Owner.NONE, FilternetMode.owner.value)

        FilternetMode.claim(FilternetMode.Owner.INTERNAL)
        assertEquals(FilternetMode.Owner.INTERNAL, FilternetMode.owner.value)

        FilternetMode.release()
        assertEquals(FilternetMode.Owner.NONE, FilternetMode.owner.value)
    }

    @Test
    fun `claiming the same owner twice is not a change`() {
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)
        val first = FilternetMode.owner.value

        FilternetMode.claim(FilternetMode.Owner.INTERNAL)

        assertEquals(first, FilternetMode.owner.value)
        assertTrue(FilternetMode.isInternal())
    }
}
