package com.pinpin.app.capture

import org.junit.Assert.*
import org.junit.Test

class CaptureGateTest {
    @Test fun rejectsMalformedIds() {
        listOf(null, "", "a".repeat(65), "../capture", "a b").forEach {
            assertFalse(CaptureProtocol.validId(it))
        }
        assertTrue(CaptureProtocol.validId("a123-456"))
    }

    @Test fun rejectsConcurrentAndDuplicateRequests() {
        val gate = CaptureGate()
        assertTrue(gate.acquire("first"))
        assertFalse(gate.acquire("second"))
        assertFalse(gate.acquire("first"))
        assertEquals("first", gate.activeId)
    }

    @Test fun staleCompletionCannotReleaseNewRequest() {
        val gate = CaptureGate()
        assertTrue(gate.acquire("first"))
        assertTrue(gate.release("first"))
        assertTrue(gate.acquire("second"))
        assertFalse(gate.release("first"))
        assertEquals("second", gate.activeId)
    }

    @Test fun invalidRequestDoesNotTakeLease() {
        val gate = CaptureGate()
        assertFalse(gate.acquire(""))
        assertNull(gate.activeId)
        assertTrue(gate.acquire("valid"))
    }
}
