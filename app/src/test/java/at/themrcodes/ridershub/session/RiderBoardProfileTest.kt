package at.themrcodes.ridershub.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiderBoardProfileTest {
    @Test fun defaultsToAnAverageRiderOnAStockG3() {
        val profile = RiderBoardProfile.DEFAULT
        assertEquals(BoardModelCatalog.G3, profile.boardModel)
        assertEquals(RiderBoardProfile.DEFAULT_RIDER_MASS_KG + BoardModelCatalog.G3.boardMassKg, profile.totalMassKg, 0.0)
        assertEquals(BoardModelCatalog.G3.batteryWh, profile.nominalBatteryWh, 0.0)
        // Percent per J/kg: 100 m / (efficiency x 3600 x Wh).
        assertEquals(
            100.0 * profile.totalMassKg / (RiderBoardProfile.DRIVE_EFFICIENCY * 3600.0 * profile.nominalBatteryWh),
            profile.nominalPercentPerJPerKg, 1e-15,
        )
    }

    @Test fun overridesReplaceModelValuesAndStockValuesAreNotStoredAsOverrides() {
        val custom = RiderBoardProfile(boardModelId = "backfire_ranger_x5", riderMassKg = 60.0,
            boardMassKgOverride = 13.5, batteryWhOverride = 400.0).normalized()
        assertEquals(73.5, custom.totalMassKg, 1e-12)
        assertEquals(400.0, custom.nominalBatteryWh, 0.0)
        val stock = RiderBoardProfile(boardModelId = "backfire_ranger_x5",
            boardMassKgOverride = 12.8, batteryWhOverride = 518.0).normalized()
        assertNull(stock.boardMassKgOverride)
        assertNull(stock.batteryWhOverride)
    }

    @Test fun unknownModelsAndOutOfRangeValuesFallBackSafely() {
        val profile = RiderBoardProfile(boardModelId = "unknown", riderMassKg = Double.NaN,
            boardMassKgOverride = 0.1, batteryWhOverride = 1e9).normalized()
        assertEquals(BoardModelCatalog.G3.id, profile.boardModelId)
        assertEquals(RiderBoardProfile.DEFAULT_RIDER_MASS_KG, profile.riderMassKg, 0.0)
        assertEquals(RiderBoardProfile.MIN_BOARD_MASS_KG, profile.boardMassKg, 0.0)
        assertEquals(RiderBoardProfile.MAX_BATTERY_WH, profile.nominalBatteryWh, 0.0)
    }
}
