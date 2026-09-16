package at.themrcodes.ridershub.api

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class ApiDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val expectedScope = inputData.getString(KEY_SCOPE) ?: return@withContext Result.success()
        val store = ApiStore(applicationContext)
        val connection = store.connection() ?: return@withContext Result.success()
        if (connection.serverId == null || connection.deliveryScope != expectedScope) return@withContext Result.success()
        val database = store.database()
        if (database.retryDelay(expectedScope, "metrics") > 0) return@withContext Result.retry()
        val client = ApiClient { candidate, channel -> !isStopped && store.allows(candidate, channel) }
        while (store.allows(connection, "metrics") && !isStopped) {
            val batch = database.pendingMetrics(expectedScope)
            if (batch.isEmpty()) break
            for (item in batch) {
                try {
                    client.postMetricDurable(connection, metricPayloadForAttempt(item.payload))
                    database.acknowledgeMetric(item.rowId)
                    if (store.connection() == connection) database.setStatus(connection.destinationId, "metrics_status", "Delivered")
                } catch (_: ApiDeliveryCancelled) {
                    return@withContext Result.success()
                } catch (error: ApiHttpException) {
                    if (!store.allows(connection, "metrics")) return@withContext Result.success()
                    database.failMetric(item.rowId, when {
                        error.unauthorized -> "unauthorized"
                        error.retryable -> "pending"
                        else -> "blocked"
                    }, error.message ?: "Rejected")
                    database.setStatus(connection.destinationId, "metrics_status", when {
                        error.unauthorized -> "Authorization paused"
                        error.retryable -> "Retrying"
                        else -> "Blocked"
                    }, error.message)
                    if (error.unauthorized) {
                        database.pauseChannel(expectedScope, "metrics")
                        return@withContext Result.success()
                    }
                    if (error.retryable) {
                        error.retryAfterMillis()?.let {
                            database.deferChannel(expectedScope, "metrics", retryAt(it))
                        }
                        return@withContext Result.retry()
                    }
                    // A rejected historical event must not strand the remaining backlog.
                } catch (_: Exception) {
                    if (!store.allows(connection, "metrics") || isStopped) return@withContext Result.success()
                    database.failMetric(item.rowId, "pending", "Network delivery failed")
                    database.setStatus(connection.destinationId, "metrics_status", "Retrying", "Network delivery failed")
                    return@withContext Result.retry()
                }
            }
        }
        Result.success()
    }

    companion object {
        private const val KEY_SCOPE = "delivery_scope"
        fun enqueue(context: Context, destinationId: String) {
            val store = ApiStore(context)
            val connection = store.connection() ?: return
            if (connection.destinationId != destinationId || connection.serverId == null) return
            val scope = connection.deliveryScope
            val request = OneTimeWorkRequestBuilder<ApiDeliveryWorker>()
                .setInputData(workDataOf(KEY_SCOPE to scope))
                .addTag("api-delivery")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // KEEP loses transitions enqueued between the final query and worker completion.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "api-metrics-$scope", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            )
        }
    }
}

internal fun metricPayloadForAttempt(storedPayload: String, sentAt: String = Instant.now().toString()): String =
    JSONObject(storedPayload).put("sent_at", sentAt).toString()

internal fun retryAt(delayMillis: Long, now: Long = System.currentTimeMillis()): Long =
    now + delayMillis.coerceIn(0, Long.MAX_VALUE - now)
