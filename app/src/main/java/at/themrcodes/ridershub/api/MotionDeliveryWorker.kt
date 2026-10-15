package at.themrcodes.ridershub.api

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Immediate delivery for live batches; WorkManager handles offline/process recovery. */
internal object MotionDelivery {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "riders-hub-motion-upload") }
    private val scheduled = AtomicBoolean(false)
    private val deliveryLock = ReentrantLock()
    fun request(context: Context) {
        if (!scheduled.compareAndSet(false, true)) return
        executor.execute {
            try { if (!runCatching { deliver(context) }.getOrDefault(false)) MotionDeliveryWorker.enqueue(context, append = false) }
            finally { scheduled.set(false) }
        }
    }
    fun deliver(context: Context): Boolean = deliveryLock.withLock {
        val window = RemoteTrackingGate.shared.current() ?: return true
        val store = ApiStore(context)
        val connection = store.connection()?.takeIf { it.serverId != null } ?: return true
        val scope = connection.deliveryScope
        val database = store.database()
        if (database.retryDelay(scope, "motion") > 0) return false
        val journal = MotionStore.get(context)
        fun allowed(candidate: ApiConnection, channel: String) =
            RemoteTrackingGate.shared.isCurrent(window) && store.allows(candidate, channel)
        val client = ApiClient(::allowed)
        while (allowed(connection, "motion")) {
            val batch = journal.pending(scope)
            if (batch.isEmpty()) return true
            for (record in batch) {
                try {
                    client.postMotion(connection, record.payload)
                    journal.mark(record.id, "acknowledged")
                    journal.status(scope, "Batches delivered")
                } catch (_: ApiDeliveryCancelled) { return true }
                catch (error: ApiHttpException) {
                    if (!allowed(connection, "motion")) return true
                    when {
                        error.unauthorized -> {
                            database.pauseChannel(scope, "motion")
                            journal.status(scope, "Authorization paused; recordings retained")
                            return true
                        }
                        error.retryable -> {
                            database.deferChannel(scope, "motion", retryAt(error.retryAfterMillis() ?: 10_000))
                            journal.status(scope, "Retrying; recordings retained")
                            return false
                        }
                        else -> { journal.mark(record.id, "blocked"); journal.status(scope, "Upload rejected; recording retained") }
                    }
                } catch (_: Exception) {
                    if (!allowed(connection, "motion")) return true
                    database.deferChannel(scope, "motion", retryAt(10_000))
                    journal.status(scope, "Offline; recordings retained")
                    return false
                }
            }
        }
        true
    }
}

class MotionDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (runCatching { MotionDelivery.deliver(applicationContext) }.getOrDefault(false)) Result.success() else Result.retry()
    }
    companion object {
        fun enqueue(context: Context, append: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<MotionDeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
            // Repeated live failures share one retry job. Finalization/recovery appends
            // a successor so an enqueue racing a worker's final empty query is not lost.
            WorkManager.getInstance(context).enqueueUniqueWork("api-motion",
                if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
