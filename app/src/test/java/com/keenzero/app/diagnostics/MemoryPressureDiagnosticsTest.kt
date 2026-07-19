package com.keenzero.app.diagnostics

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryPressureDiagnosticsTest {
    @Test
    fun namesKnownTrimLevels() {
        assertEquals(
            "TRIM_MEMORY_UI_HIDDEN",
            MemoryPressureDiagnostics.trimLevelName(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertEquals(
            "TRIM_MEMORY_COMPLETE",
            MemoryPressureDiagnostics.trimLevelName(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
        assertEquals("TRIM_MEMORY_UNKNOWN", MemoryPressureDiagnostics.trimLevelName(999))
    }

    @Test
    fun releasesUiHiddenAndStrongerLevels() {
        assertEquals(
            false,
            MemoryPressureDiagnostics.shouldReleaseRecreatableState(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ),
        )
        assertEquals(
            true,
            MemoryPressureDiagnostics.shouldReleaseRecreatableState(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ),
        )
        assertEquals(
            true,
            MemoryPressureDiagnostics.shouldReleaseRecreatableState(
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ),
        )
    }
}
