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
import at.themrcodes.ridershub.log.TelemetryArchive
import at.themrcodes.ridershub.charger.ChargerArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class ApiArchiveWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val expectedScope = inputData.getString(KEY_SCOPE) ?: return@withContext Result.success()
        val store = ApiStore(applicationContext)
        val connection = store.connection() ?: return@withContext Result.success()
        if (connection.serverId == null || connection.deliveryScope != expectedScope) return@withContext Result.success()
        val database = store.database()
        if (database.retryDelay(expectedScope, "analytics") > 0) return@withContext Result.retry()
        if (!store.allows(connection, "analytics")) return@withContext Result.success()
        val capability = store.snapshot().capabilities?.analytics ?: return@withContext Result.success()
        val archiveDirectory = File(applicationContext.getExternalFilesDir("telemetry")
            ?: File(applicationContext.filesDir, "telemetry"), "archive")
        // Every attempt owns its temporary files, including uploads not reached before cancellation.
        val exportDirectory = Files.createTempDirectory(applicationContext.cacheDir.toPath(), "api-export-").toFile()
        var blocked = false
        try {
            val uploads = TelemetryArchive().prepareUploads(archiveDirectory, exportDirectory) { partition ->
                blocked = true
                database.setStatus(connection.destinationId, "analytics_status", "Blocked: invalid archive",
                    "Partition $partition could not be verified")
            }
            val client = ApiClient { candidate, channel -> !isStopped && store.allows(candidate, channel) }
            val source = database.sourceId()
            for (upload in uploads) {
                if (!store.allows(connection, "analytics") || isStopped) return@withContext Result.success()
                if (!database.archiveNeedsUpload(expectedScope, source, upload)) continue
                if (upload.file.length() > capability.maxUploadBytes) {
                    blocked = true
                    database.recordArchiveFailure(expectedScope, source, upload, "Exceeds max_upload_bytes")
                    database.setStatus(connection.destinationId, "analytics_status", "Blocked: upload too large",
                        "Partition ${upload.partitionSequence} exceeds the server limit")
                    continue
                }
                try {
                    client.uploadArchiveDurable(connection, source, upload.partitionSequence, upload.file,
                        upload.generation, upload.final, upload.compressedDigestBase64, upload.uncompressedDigestBase64)
                    synchronized(ApiStore.CONFIG_LOCK) {
                        if (!store.allows(connection, "analytics") || isStopped) return@withContext Result.success()
                        database.acknowledgeArchive(expectedScope, source, upload)
                    }
                    if (!blocked) database.setStatus(connection.destinationId, "analytics_status", "Delivered")
                } catch (_: ApiDeliveryCancelled) {
                    return@withContext Result.success()
                } catch (error: ApiHttpException) {
                    if (!store.allows(connection, "analytics")) return@withContext Result.success()
                    if (error.unauthorized) {
                        database.pauseChannel(expectedScope, "analytics")
                        database.setStatus(connection.destinationId, "analytics_status", "Authorization paused", error.message)
                        return@withContext Result.success()
                    }
                    if (error.retryable) {
                        error.retryAfterMillis()?.let { database.deferChannel(expectedScope, "analytics", retryAt(it)) }
                        database.setStatus(connection.destinationId, "analytics_status", "Retrying", error.message)
                        return@withContext Result.retry()
                    }
                    blocked = true
                    database.recordArchiveFailure(expectedScope, source, upload, error.message ?: "Rejected")
                    database.setStatus(connection.destinationId, "analytics_status",
                        if (error.statusCode == 409) "Blocked: partition conflict" else "Blocked", error.message)
                }
            }
            if (1 in capability.coaFormatVersions) {
                var chargerBlocked = false
                for (upload in ChargerArchive.get(applicationContext).finalizedUploads(Long.MAX_VALUE)) {
                    if (!store.allows(connection, "analytics") || isStopped) return@withContext Result.success()
                    if (!database.chargerFileNeedsUpload(expectedScope, source, upload)) continue
                    if (upload.file.length() > capability.maxUploadBytes) {
                        chargerBlocked = true
                        database.blockChargerFile(expectedScope, source, upload, "Exceeds max_upload_bytes")
                        database.setStatus(connection.destinationId, "charger_analytics_status", "Blocked: upload too large")
                        continue
                    }
                    try {
                        client.uploadChargerFileDurable(
                            connection, source, upload.fileId, upload.file, upload.recordCount, upload.digestBase64,
                        )
                        synchronized(ApiStore.CONFIG_LOCK) {
                            if (!store.allows(connection, "analytics") || isStopped) return@withContext Result.success()
                            database.acknowledgeChargerFile(expectedScope, source, upload)
                        }
                        if (!chargerBlocked) database.setStatus(connection.destinationId, "charger_analytics_status", "Delivered")
                    } catch (_: ApiDeliveryCancelled) {
                        return@withContext Result.success()
                    } catch (error: ApiHttpException) {
                        if (!store.allows(connection, "analytics")) return@withContext Result.success()
                        if (error.unauthorized) {
                            database.pauseChannel(expectedScope, "analytics")
                            database.setStatus(connection.destinationId, "charger_analytics_status", "Authorization paused", error.message)
                            return@withContext Result.success()
                        }
                        if (error.retryable) {
                            error.retryAfterMillis()?.let { database.deferChannel(expectedScope, "analytics", retryAt(it)) }
                            database.setStatus(connection.destinationId, "charger_analytics_status", "Retrying", error.message)
                            return@withContext Result.retry()
                        }
                        chargerBlocked = true
                        database.blockChargerFile(expectedScope, source, upload, error.message ?: "Rejected")
                        database.setStatus(
                            connection.destinationId,
                            "charger_analytics_status",
                            if (error.statusCode == 409) "Blocked: file conflict" else "Blocked",
                            error.message,
                        )
                    }
                }
            } else {
                database.setStatus(connection.destinationId, "charger_analytics_status", "Unsupported")
            }
            Result.success()
        } catch (_: Exception) {
            if (!store.allows(connection, "analytics") || isStopped) return@withContext Result.success()
            database.setStatus(connection.destinationId, "analytics_status", "Retrying", "Archive delivery failed")
            Result.retry()
        } finally {
            exportDirectory.deleteRecursively()
        }
    }

    companion object {
        private const val KEY_SCOPE = "delivery_scope"
        fun enqueue(context: Context) {
            val store = ApiStore(context)
            val connection = store.connection() ?: return
            if (connection.serverId == null || !store.snapshot().analyticsApproved) return
            val scope = connection.deliveryScope
            val request = OneTimeWorkRequestBuilder<ApiArchiveWorker>()
                .setInputData(workDataOf(KEY_SCOPE to scope))
                .addTag("api-delivery")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "api-archives-$scope", ExistingWorkPolicy.APPEND_OR_REPLACE, request,
            )
        }
    }
}
