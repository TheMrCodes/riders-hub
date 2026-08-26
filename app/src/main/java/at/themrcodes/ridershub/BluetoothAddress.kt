package at.themrcodes.ridershub

import java.util.Locale

private val BLUETOOTH_ADDRESS_PATTERN = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

/**
 * Android's BluetoothAdapter address APIs require the canonical upper-case representation even
 * though AssociationInfo's MacAddress string is lower-case on some Android 16 devices.
 */
internal fun canonicalBluetoothAddress(value: String): String {
    val canonical = value.trim().uppercase(Locale.ROOT)
    require(BLUETOOTH_ADDRESS_PATTERN.matches(canonical)) {
        "$value is not a valid Bluetooth address"
    }
    return canonical
}
