package at.themrcodes.ridershub.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val dataClient by lazy { Wearable.getDataClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RidersHubWearApp(WearTelemetryStore.telemetry) }
    }

    override fun onStart() {
        super.onStart()
        dataClient.addListener(this)
        dataClient.getDataItems().addOnSuccessListener { items ->
            try {
                items.asSequence()
                    .filter { it.uri.path == WearTelemetryState.DATA_PATH }
                    .mapNotNull { item -> decodePayload(item.data) }
                    .maxByOrNull { it.updatedAtEpochMs }
                    ?.let(WearTelemetryStore::update)
            } finally {
                items.release()
            }
        }
    }

    override fun onStop() {
        dataClient.removeListener(this)
        super.onStop()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path != WearTelemetryState.DATA_PATH) return@forEach
            val updated = if (event.type == DataEvent.TYPE_DELETED) {
                null
            } else {
                decodePayload(event.dataItem.data) ?: WearTelemetryStore.telemetry
            }
            WearTelemetryStore.update(updated)
        }
    }

    private fun decodePayload(bytes: ByteArray?): WearTelemetryState? = bytes?.let { payload ->
        runCatching { WearTelemetryState.decode(payload) }.getOrNull()
    }
}

internal object WearTelemetryStore {
    var telemetry by mutableStateOf<WearTelemetryState?>(null)
        private set

    fun update(value: WearTelemetryState?) {
        telemetry = value
    }
}

private val RidersRed = Color(0xFFD71921)
private val RidersBlack = Color.Black
private val RidersWhite = Color(0xFFF2F2F2)
private val RidersMuted = Color(0xFF8C8C8C)
private val RidersLine = Color(0xFF292929)
private val RidersMono = FontFamily.Monospace

@Composable
private fun RidersHubWearApp(telemetry: WearTelemetryState?) {
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val uiState = wearUiState(telemetry, nowEpochMs)

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RidersBlack),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(if (uiState.live) RidersRed else RidersMuted, CircleShape),
                    )
                    Text(
                        text = uiState.statusLabel,
                        modifier = Modifier.padding(start = 7.dp),
                        color = RidersMuted,
                        fontFamily = RidersMono,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = uiState.speed,
                    color = uiState.valueColor,
                    fontFamily = RidersMono,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Black,
                    lineHeight = 43.sp,
                )
                Text(
                    text = "KM/H",
                    color = RidersMuted,
                    fontFamily = RidersMono,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .height(1.dp)
                        .background(RidersLine),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    WearMetric("BATTERY", uiState.battery, uiState.valueColor)
                    WearMetric("TRIP", uiState.trip, uiState.valueColor)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = uiState.mode,
                    color = RidersMuted,
                    fontFamily = RidersMono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WearMetric(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = valueColor,
            fontFamily = RidersMono,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = label,
            color = RidersMuted,
            fontFamily = RidersMono,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}

internal data class WearUiState(
    val live: Boolean,
    val statusLabel: String,
    val speed: String,
    val battery: String,
    val trip: String,
    val mode: String,
    val valueColor: Color,
)

internal fun wearUiState(telemetry: WearTelemetryState?, nowEpochMs: Long): WearUiState {
    val ageMs = telemetry?.let { (nowEpochMs - it.updatedAtEpochMs).coerceAtLeast(0) }
    val live = telemetry?.connection == WearConnectionStatus.LIVE &&
        ageMs != null && ageMs <= LIVE_FRESHNESS_MS
    val status = when {
        telemetry == null -> "WAITING FOR PHONE"
        telemetry.connection == WearConnectionStatus.LIVE && !live -> "UPDATE STALE"
        telemetry.connection == WearConnectionStatus.LIVE -> "LIVE"
        telemetry.connection == WearConnectionStatus.CONNECTING -> "CONNECTING"
        telemetry.connection == WearConnectionStatus.RECONNECTING -> "RECONNECTING"
        else -> "STANDBY"
    }
    return WearUiState(
        live = live,
        statusLabel = status,
        speed = telemetry?.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-",
        battery = telemetry?.boardBatteryPercent?.let { "$it%" } ?: "--%",
        trip = telemetry?.tripKm?.let { String.format(Locale.US, "%.1f KM", it) } ?: "--.- KM",
        mode = telemetry?.mode?.uppercase(Locale.ROOT) ?: "NO RIDE DATA",
        valueColor = if (live) RidersWhite else RidersMuted,
    )
}

private const val LIVE_FRESHNESS_MS = 30_000L
