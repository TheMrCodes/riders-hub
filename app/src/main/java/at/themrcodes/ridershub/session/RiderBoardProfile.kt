package at.themrcodes.ridershub.session

import kotlin.math.abs

/** Published stock figures used only as starting values; users override what differs. */
data class BoardModelSpec(
    val id: String,
    val displayName: String,
    val boardMassKg: Double,
    val batteryWh: Double,
)

object BoardModelCatalog {
    val G3 = BoardModelSpec("backfire_g3", "Backfire G3", boardMassKg = 8.0, batteryWh = 346.0)

    val models: List<BoardModelSpec> = listOf(
        G3,
        BoardModelSpec("backfire_era_5", "Backfire Era 5", boardMassKg = 7.7, batteryWh = 216.0),
        BoardModelSpec("backfire_zealot_s2", "Backfire Zealot S2", boardMassKg = 10.7, batteryWh = 403.0),
        BoardModelSpec("backfire_zealot_s3", "Backfire Zealot S3", boardMassKg = 10.7, batteryWh = 403.0),
        BoardModelSpec("backfire_ranger_x5", "Backfire Ranger X5", boardMassKg = 12.8, batteryWh = 518.0),
        BoardModelSpec("backfire_ranger_x6", "Backfire Ranger X6", boardMassKg = 12.8, batteryWh = 648.0),
    )

    fun byId(id: String?): BoardModelSpec = models.firstOrNull { it.id == id } ?: G3
}

/**
 * Rider and board facts that the physics model would otherwise have to learn. Mass and nominal
 * pack energy fix the depletion scale from the first fitted ride; one learned pack-energy factor
 * absorbs ageing, drivetrain efficiency and the remote's percentage mapping.
 *
 * Rider mass is personal data: it stays in local settings and never enters logs or exports.
 */
data class RiderBoardProfile(
    val boardModelId: String = BoardModelCatalog.G3.id,
    val riderMassKg: Double = DEFAULT_RIDER_MASS_KG,
    /** Null uses the selected model's published value. */
    val boardMassKgOverride: Double? = null,
    val batteryWhOverride: Double? = null,
) {
    val boardModel: BoardModelSpec get() = BoardModelCatalog.byId(boardModelId)
    val boardMassKg: Double get() = boardMassKgOverride ?: boardModel.boardMassKg
    val totalMassKg: Double get() = riderMassKg + boardMassKg
    val nominalBatteryWh: Double get() = batteryWhOverride ?: boardModel.batteryWh

    /** Battery percent per joule-per-kilogram of drive work at the stated pack energy. */
    val nominalPercentPerJPerKg: Double
        get() = 100.0 * totalMassKg / (DRIVE_EFFICIENCY * 3600.0 * nominalBatteryWh)

    fun normalized(): RiderBoardProfile = copy(
        boardModelId = BoardModelCatalog.byId(boardModelId).id,
        riderMassKg = riderMassKg.takeIf { it.isFinite() }?.coerceIn(MIN_RIDER_MASS_KG, MAX_RIDER_MASS_KG)
            ?: DEFAULT_RIDER_MASS_KG,
        boardMassKgOverride = boardMassKgOverride?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_BOARD_MASS_KG, MAX_BOARD_MASS_KG)
            ?.takeUnless { abs(it - BoardModelCatalog.byId(boardModelId).boardMassKg) < 1e-9 },
        batteryWhOverride = batteryWhOverride?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_BATTERY_WH, MAX_BATTERY_WH)
            ?.takeUnless { abs(it - BoardModelCatalog.byId(boardModelId).batteryWh) < 1e-9 },
    )

    companion object {
        val DEFAULT = RiderBoardProfile()
        /** Approximate adult average; a 5 kg error moves the scale by about 6 percent. */
        const val DEFAULT_RIDER_MASS_KG = 75.0
        const val MIN_RIDER_MASS_KG = 20.0
        const val MAX_RIDER_MASS_KG = 200.0
        const val MIN_BOARD_MASS_KG = 3.0
        const val MAX_BOARD_MASS_KG = 30.0
        const val MIN_BATTERY_WH = 50.0
        const val MAX_BATTERY_WH = 2_000.0
        /** Battery-to-wheel efficiency folded into the nominal scale; the learned factor corrects it. */
        const val DRIVE_EFFICIENCY = 0.85
    }
}
