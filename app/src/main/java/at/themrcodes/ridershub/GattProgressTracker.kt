package at.themrcodes.ridershub

internal enum class GattStage(
    val logName: String,
    val timeoutMs: Long?,
) {
    IDLE("idle", null),
    CONNECTING("connecting", 20_000L),
    DISCOVERING_SERVICES("discovering_services", 15_000L),
    ENABLING_NOTIFICATIONS("enabling_notifications", 12_000L),
    LISTENING("listening", null),
}

internal data class GattDeadline(
    val token: Long,
    val stage: GattStage,
)

/** Invalidates an older timeout every time GATT advances to another stage. */
internal class GattProgressTracker {
    private var token = 0L

    var stage: GattStage = GattStage.IDLE
        private set

    fun enter(stage: GattStage): GattDeadline {
        token += 1
        this.stage = stage
        return GattDeadline(token, stage)
    }

    fun isCurrent(deadline: GattDeadline): Boolean =
        deadline.token == token && deadline.stage == stage
}
