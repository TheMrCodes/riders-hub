package at.themrcodes.ridershub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionRoleResolutionTest {
    private val oldDuplicate = BackfireAssociation(1, "AA:BB:CC:DD:EE:01", "ShellyPlusPlugS-OLD")
    private val selected = BackfireAssociation(3, "AA:BB:CC:DD:EE:01", "ShellyPlusPlugS-SELECTED")

    @Test fun `legacy callback for duplicate address inherits selected charger role`() {
        assertEquals(
            CompanionRole.CHARGER_PLUG,
            resolveCompanionRole(
                eventAssociation = oldDuplicate,
                selectedAssociationIds = mapOf(CompanionRole.CHARGER_PLUG to selected.associationId),
                associations = listOf(oldDuplicate, selected),
            ),
        )
    }

    @Test fun `direct association id takes precedence`() {
        assertEquals(
            CompanionRole.BOARD_REMOTE,
            resolveCompanionRole(
                eventAssociation = oldDuplicate,
                selectedAssociationIds = mapOf(
                    CompanionRole.BOARD_REMOTE to oldDuplicate.associationId,
                    CompanionRole.CHARGER_PLUG to selected.associationId,
                ),
                associations = listOf(oldDuplicate, selected),
            ),
        )
    }

    @Test fun `ambiguous shared address is not assigned`() {
        assertNull(
            resolveCompanionRole(
                eventAssociation = BackfireAssociation(9, selected.address, "Synthetic duplicate"),
                selectedAssociationIds = mapOf(
                    CompanionRole.BOARD_REMOTE to oldDuplicate.associationId,
                    CompanionRole.CHARGER_PLUG to selected.associationId,
                ),
                associations = listOf(oldDuplicate, selected),
            ),
        )
    }
}
