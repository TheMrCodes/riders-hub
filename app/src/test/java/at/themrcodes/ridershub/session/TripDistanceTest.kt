package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Test

class TripDistanceTest {
    @Test
    fun firstFrameConnectedMidRideKeepsRemoteTripDistance() {
        assertEquals(
            4.2,
            authoritativeTripDistanceKm(remoteTripKm = 4.2, previousDistanceKm = 0.0),
            0.0,
        )
    }

    @Test
    fun remoteTripDistanceReplacesPhoneIntegratedEstimate() {
        assertEquals(
            4.3,
            authoritativeTripDistanceKm(remoteTripKm = 4.3, previousDistanceKm = 4.247),
            0.0,
        )
    }

    @Test
    fun invalidRemoteValueDoesNotEraseLastValidDistance() {
        assertEquals(
            4.3,
            authoritativeTripDistanceKm(remoteTripKm = Double.NaN, previousDistanceKm = 4.3),
            0.0,
        )
    }
}
