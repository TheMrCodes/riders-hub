package at.themrcodes.ridershub

import android.content.Context
import java.util.Locale

enum class CompanionRole { BOARD_REMOTE, CHARGER_PLUG }

/** Keeps Android companion associations role-separated without persisting another hardware ID. */
class CompanionRoleStore(context: Context) {
    private val preferences = context.getSharedPreferences("companion_roles", Context.MODE_PRIVATE)

    fun set(role: CompanionRole, associationId: Int) {
        preferences.edit().putInt(role.key, associationId).apply()
    }

    fun associationId(role: CompanionRole): Int? = preferences
        .takeIf { it.contains(role.key) }
        ?.getInt(role.key, -1)
        ?.takeIf { it >= 0 }

    fun roleFor(associationId: Int): CompanionRole? = CompanionRole.entries
        .firstOrNull { this.associationId(it) == associationId }

    /** One-time compatibility for installs made before associations had explicit roles. */
    fun migrateBoardAssociation(associationId: Int?) {
        if (associationId != null && associationId >= 0 && associationId(CompanionRole.BOARD_REMOTE) == null) {
            set(CompanionRole.BOARD_REMOTE, associationId)
        }
    }

    private val CompanionRole.key: String get() = "association_${name.lowercase(Locale.ROOT)}"
}

/**
 * Legacy address-based observation can report the oldest duplicate association for an address.
 * Resolve that callback to the role of the currently selected association without persisting the
 * address or deleting any system association.
 */
internal fun resolveCompanionRole(
    eventAssociation: BackfireAssociation,
    selectedAssociationIds: Map<CompanionRole, Int>,
    associations: List<BackfireAssociation>,
): CompanionRole? {
    selectedAssociationIds.entries.firstOrNull { it.value == eventAssociation.associationId }
        ?.let { return it.key }
    return selectedAssociationIds.entries.filter { (_, selectedId) ->
        associations.firstOrNull { it.associationId == selectedId }
            ?.address == eventAssociation.address
    }.singleOrNull()?.key
}
