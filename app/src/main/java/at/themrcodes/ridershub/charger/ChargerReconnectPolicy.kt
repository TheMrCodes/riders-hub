package at.themrcodes.ridershub.charger

/** Reconnect radio operations are paced; user relay commands are never queued for replay. */
internal class ChargerReconnectPolicy {
    private var attempts = 0
    fun nextDelayMs(): Long = (2_000L shl attempts.coerceAtMost(5)).coerceAtMost(60_000L)
        .also { attempts = (attempts + 1).coerceAtMost(6) }
    fun connected() { attempts = 0 }
}
