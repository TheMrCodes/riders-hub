package at.themrcodes.ridershub.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import java.util.Base64

internal object WearTelemetryPersistence {
    fun read(context: Context): WearTelemetryState? = preferences(context)
        .getString(KEY_LATEST_TELEMETRY, null)
        ?.let(::decodePersistedTelemetry)

    fun write(context: Context, telemetry: WearTelemetryState?) {
        preferences(context).edit().apply {
            if (telemetry == null) {
                remove(KEY_LATEST_TELEMETRY)
            } else {
                putString(KEY_LATEST_TELEMETRY, encodePersistedTelemetry(telemetry))
            }
        }.commit()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private const val PREFERENCES_NAME = "wear_telemetry_state"
    private const val KEY_LATEST_TELEMETRY = "latest_telemetry_v1"
}

internal object WearTelemetryRepository {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var revision = 0L

    fun restore(context: Context) {
        publishToUi(context)
        val telemetry = WearTelemetryPersistence.read(context)
        WearOngoingActivity.sync(context.applicationContext, telemetry, System.currentTimeMillis())
    }

    fun currentRevision(): Long = synchronized(lock) { revision }

    fun accept(context: Context, telemetry: WearTelemetryState?): Boolean =
        acceptLocked(context, telemetry, expectedRevision = null)

    fun acceptBootstrap(
        context: Context,
        telemetry: WearTelemetryState,
        expectedRevision: Long,
    ): Boolean = acceptLocked(context, telemetry, expectedRevision)

    private fun acceptLocked(
        context: Context,
        telemetry: WearTelemetryState?,
        expectedRevision: Long?,
    ): Boolean {
        val changed = synchronized(lock) {
            if (expectedRevision != null && revision != expectedRevision) return@synchronized false
            val current = WearTelemetryPersistence.read(context)
            if (telemetry != null && !shouldReplaceTelemetry(current, telemetry)) {
                return@synchronized false
            }
            if (current == telemetry) return@synchronized false
            WearTelemetryPersistence.write(context, telemetry)
            revision++
            true
        }
        if (!changed) return false

        publishToUi(context)
        WearOngoingActivity.sync(
            context = context.applicationContext,
            telemetry = telemetry,
            nowEpochMs = System.currentTimeMillis(),
        )
        return true
    }

    private fun publishToUi(context: Context) {
        val appContext = context.applicationContext
        val publish = Runnable {
            WearTelemetryStore.update(WearTelemetryPersistence.read(appContext))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) publish.run() else mainHandler.post(publish)
    }
}

internal fun shouldReplaceTelemetry(
    current: WearTelemetryState?,
    candidate: WearTelemetryState,
): Boolean = current == null || candidate.updatedAtEpochMs >= current.updatedAtEpochMs

internal fun encodePersistedTelemetry(telemetry: WearTelemetryState): String =
    Base64.getEncoder().encodeToString(telemetry.encode())

internal fun decodePersistedTelemetry(encoded: String): WearTelemetryState? = runCatching {
    WearTelemetryState.decode(Base64.getDecoder().decode(encoded))
}.getOrNull()

internal fun decodeWearTelemetry(bytes: ByteArray?): WearTelemetryState? = bytes?.let { payload ->
    runCatching { WearTelemetryState.decode(payload) }.getOrNull()
}
