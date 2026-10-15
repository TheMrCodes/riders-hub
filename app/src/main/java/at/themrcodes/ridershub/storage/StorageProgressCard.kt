package at.themrcodes.ridershub.storage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

internal data class StorageProgress(
    val stage: String,
    val title: String,
    val detail: String,
    val next: String,
    val progress: Float,
    val urgent: Boolean,
)

internal fun storageProgress(state: StorageState, now: Long, zone: ZoneId = ZoneId.systemDefault()): StorageProgress? {
    if (!state.enabled) return null
    fun date(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
    val latest = state.latest
    val due = state.chargeDueAt
    val overdue = due != null && now >= due
    val sampleDue = state.nextSampleAt?.let { now >= it } == true
    return when {
        latest == null -> StorageProgress("SETUP", "Connect your board", "Turn on your board and remote near this phone. Keep the board still for 30 seconds to save the starting level.", "Starting observation · 0 of 3", 0f, false)
        overdue -> StorageProgress("CHARGE DUE", "Time for a charge", "Charge your board, then reconnect it here. A sustained battery increase confirms the charge.", "Daily reminder around 10:00 until charge is observed", 1f, true)
        state.lossPerDay == null -> StorageProgress("LEARNING", if (sampleDue) "Battery check-in due" else "Learning your battery", "${state.observations.size.coerceAtMost(3)} of 3 weekly observations collected. Connect the board for each check-in.",
            if (state.observations.size >= 3) "No clear decline yet · next check ${date(requireNotNull(state.nextSampleAt))}"
            else "${if (sampleDue) "Check-in due" else "Next check-in"} · ${date(requireNotNull(state.nextSampleAt))}",
            (state.observations.size / 3f).coerceAtMost(1f), sampleDue)
        else -> {
            val days = ceil((requireNotNull(due) - now).toDouble() / STORAGE_DAY_MS).toInt().coerceAtLeast(0)
            val start = state.cycleStartedAt ?: latest.at
            StorageProgress("MONITORING", "Next charge in $days days", "Reminder at half the estimated time to empty. The estimate uses your observed storage loss.",
                "Charge check-in · ${date(due)} · around 10:00", ((now - start).toDouble() / (due - start).coerceAtLeast(1)).toFloat().coerceIn(0f, 1f), false)
        }
    }
}

@Composable
internal fun StorageProgressCard(state: StorageState, now: Long, notificationsAvailable: Boolean, onOpenSettings: () -> Unit) {
    val progress = storageProgress(state, now) ?: return
    val accent = MaterialTheme.colorScheme.primary
    val muted = Color(0xFFAAAAAA)
    var showCheck by remember { mutableStateOf(false) }
    Surface(color = Color(0xFF101010), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Color(0xFF303030)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("STORAGE MODE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Text(progress.stage, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (progress.urgent) accent else muted)
            }
            Text(progress.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp)
            Text(progress.detail, fontSize = 13.sp, lineHeight = 19.sp, color = muted)
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = if (progress.urgent) accent else Color.White, trackColor = Color(0xFF353535))
            state.latest?.let { latest ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${latest.percent}%", fontFamily = FontFamily.Monospace, fontSize = 26.sp)
                        val age = ((now - latest.at).coerceAtLeast(0) / STORAGE_DAY_MS)
                        Text(if (age == 0L) "Observed today" else "Observed ${age}d ago", color = muted, fontSize = 11.sp)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(state.lossPerDay?.let { String.format(Locale.US, "%.2f", it) } ?: "—", fontFamily = FontFamily.Monospace, fontSize = 26.sp)
                        Text("% points / day", color = muted, fontSize = 11.sp)
                    }
                }
            }
            Text(progress.next, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = if (progress.urgent) accent else Color.White)
            if (!notificationsAvailable) {
                Text("Notifications are off. Enable them for storage reminders.", fontSize = 12.sp, color = accent)
                TextButton(onClick = onOpenSettings) { Text("ENABLE NOTIFICATIONS") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ends after a ride\nover 0.1 km", fontSize = 11.sp, lineHeight = 16.sp, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { showCheck = true }) { Text("CHECK IN", fontSize = 12.sp) }
            }
        }
    }
    if (showCheck) AlertDialog(
        onDismissRequest = { showCheck = false },
        title = { Text("Connect to check in") },
        text = { Text("Turn on your board and its paired remote near this phone. Keep the board still for 30 seconds. A fresh reading is recorded automatically when a sample is due.\n\nCharging is confirmed only by a sustained rise of at least 10 percentage points over the last storage observation.") },
        confirmButton = { TextButton(onClick = { showCheck = false }) { Text("GOT IT") } },
    )
}
