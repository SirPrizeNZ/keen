package com.keenzero.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActivationLedgerTest {
    private var now = 1_000L
    private val ledger = ActivationLedger(clock = { now })

    @Test
    fun singleUseConsume() {
        ledger.record(
            type = ActivationLedger.Type.LINK,
            sourceOrigin = "https://a.example",
            expectedHref = "https://b.example/x",
        )
        assertNotNull(ledger.peek())
        assertNotNull(ledger.consume())
        assertNull(ledger.consume())
        assertNull(ledger.peek())
    }

    @Test
    fun expiryInvalidates() {
        ledger.record(
            type = ActivationLedger.Type.UNKNOWN,
            sourceOrigin = null,
            expectedHref = null,
        )
        now += ActivationLedger.Type.UNKNOWN.ttlMs + 1
        assertNull(ledger.peek())
        assertNull(ledger.consume())
    }

    @Test
    fun replacementClearsPrevious() {
        ledger.record(ActivationLedger.Type.LINK, "https://a.example", "https://a.example/1")
        ledger.record(ActivationLedger.Type.PLAY, "https://a.example", "https://a.example/play")
        val g = ledger.peek()
        assertEquals(ActivationLedger.Type.PLAY, g?.type)
    }

    @Test
    fun clearDropsGrant() {
        ledger.record(ActivationLedger.Type.FORM, null, null)
        ledger.clear()
        assertNull(ledger.peek())
    }
}
