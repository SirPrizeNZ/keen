package com.keenzero.app.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceGovernorTest {
    @Test
    fun constraintPolicyProhibitsPools() {
        val p = PerformancePolicy(tier = DeviceTier.CONSTRAINT_32, reason = "test")
        assertEquals(1, p.maxLiveWebViews)
        assertFalse(p.allowRendererPool)
        assertTrue(p.denyFirstPopups)
        assertTrue(p.disallowEagerServiceWorkerOnHome)
        assertEquals(256, p.maxInteractionCandidates)
    }

    @Test
    fun policyJsonHasTier() {
        val j = PerformancePolicy(tier = DeviceTier.BALANCED, reason = "r").toJson()
        assertEquals("BALANCED", j.getString("tier"))
    }
}
