package at.themrcodes.ridershub.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionContinuityTest {
    @Test
    fun reconnectAtGraceBoundaryResumesRide() {
        assertTrue(SessionContinuity.shouldResume("AA", "AA", 1_000, 121_000, 120_000))
    }

    @Test
    fun reconnectAfterGraceStartsAnotherRide() {
        assertFalse(SessionContinuity.shouldResume("AA", "AA", 1_000, 121_001, 120_000))
    }

    @Test
    fun anotherRemoteNeverResumesRide() {
        assertFalse(SessionContinuity.shouldResume("AA", "BB", 1_000, 2_000, 120_000))
    }

    @Test
    fun addressCaseChangeStillResumesSameRide() {
        assertTrue(
            SessionContinuity.shouldResume(
                "02:ab:cd:ef:00:01",
                "02:AB:CD:EF:00:01",
                1_000,
                2_000,
                120_000,
            ),
        )
    }
}
