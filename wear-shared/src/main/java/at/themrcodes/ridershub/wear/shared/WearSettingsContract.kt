package at.themrcodes.ridershub.wear.shared

data class WearSettingsState(
    val autoOpenOnLive: Boolean,
) {
    fun encode(): ByteArray = byteArrayOf(SCHEMA_VERSION, if (autoOpenOnLive) 1 else 0)

    companion object {
        const val DATA_PATH = "/riders-hub/wear-settings"
        const val DEFAULT_AUTO_OPEN_ON_LIVE = true
        private const val SCHEMA_VERSION: Byte = 1

        fun decode(bytes: ByteArray): WearSettingsState {
            require(bytes.size == 2) { "Invalid Wear settings payload length" }
            require(bytes[0] == SCHEMA_VERSION) { "Unsupported Wear settings schema" }
            require(bytes[1] == 0.toByte() || bytes[1] == 1.toByte()) {
                "Invalid Wear auto-open value"
            }
            return WearSettingsState(autoOpenOnLive = bytes[1] == 1.toByte())
        }
    }
}
