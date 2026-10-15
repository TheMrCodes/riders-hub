package at.themrcodes.ridershub.api

import org.junit.Assert.*
import org.junit.Test

class RemoteTrackingGateTest {
    @Test fun trackingExpiresFiveSecondsAfterDisconnectEvenIfTheRideRemainsOpen() {
        var now = 0L
        val gate = RemoteTrackingGate { now }
        assertNull(gate.observe("segment-a", "ride", "synthetic-log"))
        gate.connected("segment-a")
        assertNull(gate.current()) // GATT alone is not evidence of usable board telemetry.
        val window = gate.observe("segment-a", "ride", "synthetic-log")!!
        assertTrue(gate.isCurrent(window))
        assertTrue(gate.disconnected("segment-a"))
        now = 4_999
        assertTrue(gate.isCurrent(window))
        assertFalse(gate.disconnected("segment-a")) // Repeated callbacks cannot extend grace.
        now = 5_000
        assertFalse(gate.isCurrent(window))
        // The same still-open trip and late frames cannot revive the old connection.
        assertNull(gate.observe("segment-a", "ride", "synthetic-log"))
    }
    @Test fun finalizationStopsTrackingWithoutWaitingForGrace() {
        val gate = RemoteTrackingGate { 0L }
        gate.connected("segment")
        val window = gate.observe("segment", "ride", "synthetic-log")!!
        gate.disconnected("segment")
        gate.endRide("another-ride")
        assertTrue(gate.isCurrent(window))
        gate.endRide("ride")
        assertNull(gate.current())
        assertNull(gate.observe("segment", "ride", "synthetic-log"))
    }
    @Test fun reconnectingTheSameRideCannotReuseOldSensorCallbacks() {
        var now = 0L
        val gate = RemoteTrackingGate { now }
        gate.connected("segment")
        val before = gate.observe("segment", "ride", "synthetic-log")!!
        gate.disconnected("segment")
        gate.connected("segment")
        val after = gate.observe("segment", "ride", "synthetic-log")!!
        assertFalse(gate.isCurrent(before))
        now = 10_000
        assertTrue(gate.isCurrent(after))
        assertNotEquals(before.generation, after.generation)
    }
    @Test fun aStaleSessionCannotStopTheReplacementRemote() {
        var now = 0L
        val gate = RemoteTrackingGate { now }
        gate.connected("old-segment")
        gate.connected("new-segment")
        val current = gate.observe("new-segment", "ride", "synthetic-log")!!
        assertFalse(gate.disconnected("old-segment"))
        assertNull(gate.observe("old-segment", "ride", "synthetic-log"))
        assertTrue(gate.isCurrent(current))
    }
    @Test fun oldGpsServiceCannotClearOrOverwriteAReplacementFixOnTheSameRide() {
        val oldOwner = Any()
        val newOwner = Any()
        val fix = RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, 1)
        LocationFixState.begin("synthetic-log", oldOwner)
        LocationFixState.begin("synthetic-log", newOwner)
        assertTrue(LocationFixState.accept("synthetic-log", fix, owner = newOwner) {})
        var recorded = false
        assertFalse(LocationFixState.accept("synthetic-log", fix, owner = oldOwner) { recorded = true })
        assertFalse(recorded)
        LocationFixState.stop(oldOwner)
        assertEquals(fix, LocationFixState.latest())
        LocationFixState.stop(newOwner)
        assertNull(LocationFixState.latest())
    }
    @Test fun delayedGpsResultCannotEnterAReconnectedRideWithTheSameLogPath() {
        var now = 0L
        val gate = RemoteTrackingGate { now }
        gate.connected("segment")
        val old = gate.observe("segment", "ride", "synthetic-log")!!
        gate.disconnected("segment"); gate.connected("segment")
        gate.observe("segment", "ride", "synthetic-log")
        LocationFixState.begin("synthetic-log")
        var recorded = false
        val fix = RealtimeLocation("2030-01-01T00:00:00Z", 0.0, 0.0, null, 1)
        assertFalse(LocationFixState.accept("synthetic-log", fix, { gate.isCurrent(old) }) { recorded = true })
        assertFalse(recorded)
        assertNull(LocationFixState.latest())
        LocationFixState.stop()
    }
}
