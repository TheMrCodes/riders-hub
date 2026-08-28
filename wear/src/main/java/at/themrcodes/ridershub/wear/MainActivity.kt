package at.themrcodes.ridershub.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.rememberAmbientModeManager
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
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) syncOngoingActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RidersHubWearApp(
                telemetry = WearTelemetryStore.telemetry,
                onAmbientFrame = ::syncOngoingActivity,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                    ?.let(::updateTelemetry)
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
                decodePayload(event.dataItem.data) ?: WearTelemetryStore.latest()
            }
            updateTelemetry(updated)
        }
    }

    private fun updateTelemetry(value: WearTelemetryState?) {
        WearTelemetryStore.update(value)
        WearOngoingActivity.sync(this, value, System.currentTimeMillis())
    }

    private fun syncOngoingActivity() {
        WearOngoingActivity.sync(
            context = this,
            telemetry = WearTelemetryStore.latest(),
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    private fun decodePayload(bytes: ByteArray?): WearTelemetryState? = bytes?.let { payload ->
        runCatching { WearTelemetryState.decode(payload) }.getOrNull()
    }
}

internal object WearTelemetryStore {
    private val ambientGate = AmbientTelemetryGate<WearTelemetryState>()

    var telemetry by mutableStateOf<WearTelemetryState?>(null)
        private set

    fun update(value: WearTelemetryState?) {
        ambientGate.update(value)
        telemetry = ambientGate.visible
    }

    fun setAmbient(enabled: Boolean) {
        ambientGate.setAmbient(enabled)
        telemetry = ambientGate.visible
    }

    fun renderAmbientFrame() {
        ambientGate.onAmbientTick()
        telemetry = ambientGate.visible
    }

    fun latest(): WearTelemetryState? = ambientGate.latest
}

internal class AmbientTelemetryGate<T> {
    var latest: T? = null
        private set
    private var ambient = false

    var visible: T? = null
        private set

    fun update(value: T?) {
        latest = value
        if (!ambient) visible = value
    }

    fun setAmbient(enabled: Boolean) {
        ambient = enabled
        if (!enabled) visible = latest
    }

    fun onAmbientTick() {
        if (ambient) visible = latest
    }
}

private val RidersRed = Color(0xFFD71921)
private val RidersBlack = Color.Black
private val RidersWhite = Color(0xFFF2F2F2)
private val RidersMuted = Color(0xFF8C8C8C)
private val RidersAmbient = Color(0xFF707070)
private val RidersLine = Color(0xFF292929)
private val RidersMono = FontFamily.Monospace

@Composable
private fun RidersHubWearApp(
    telemetry: WearTelemetryState?,
    onAmbientFrame: () -> Unit,
) {
    val ambientModeManager = rememberAmbientModeManager()
    val ambientMode = ambientModeManager.currentAmbientMode
    val ambientDetails = ambientMode as? AmbientMode.Ambient
    val ambient = ambientDetails != null
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ambientFrame by remember { mutableIntStateOf(0) }

    LaunchedEffect(ambient) {
        WearTelemetryStore.setAmbient(ambient)
        nowEpochMs = System.currentTimeMillis()
        if (ambient) return@LaunchedEffect
        while (true) {
            delay(15_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(ambientModeManager) {
        while (true) {
            ambientModeManager.withAmbientTick {
                WearTelemetryStore.renderAmbientFrame()
                nowEpochMs = System.currentTimeMillis()
                ambientFrame++
                onAmbientFrame()
            }
        }
    }
    val uiState = wearUiState(telemetry, nowEpochMs)
    val burnInOffset = if (ambientDetails?.isBurnInProtectionRequired == true) {
        ambientBurnInOffset(ambientFrame)
    } else {
        0 to 0
    }

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
                    .offset(x = burnInOffset.first.dp, y = burnInOffset.second.dp)
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (ambient) Arrangement.Center else Arrangement.Top,
            ) {
                if (ambient) {
                    AmbientDashboard(uiState)
                } else {
                    InteractiveDashboard(uiState)
                }
            }
        }
    }
}

@Composable
private fun AmbientDashboard(uiState: WearUiState) {
    Text(
        text = uiState.tripValue,
        color = RidersAmbient,
        fontFamily = RidersMono,
        fontSize = 42.sp,
        fontWeight = FontWeight.Black,
        lineHeight = 43.sp,
    )
    Text(
        text = "TRIP KM",
        color = RidersAmbient,
        fontFamily = RidersMono,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
    Spacer(Modifier.height(18.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        WearMetric("BATTERY", uiState.battery, RidersAmbient, RidersAmbient)
        WearMetric("KM LEFT", uiState.rangeValue, RidersAmbient, RidersAmbient)
    }
}

@Composable
private fun InteractiveDashboard(uiState: WearUiState) {
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

internal fun ambientBurnInOffset(frame: Int): Pair<Int, Int> = when (Math.floorMod(frame, 4)) {
    0 -> -2 to -2
    1 -> 2 to -2
    2 -> 2 to 2
    else -> -2 to 2
}

@Composable
private fun WearMetric(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color = RidersMuted,
) {
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
            color = labelColor,
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
    val tripValue: String,
    val rangeValue: String,
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
    val tripValue = telemetry?.tripKm?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-"
    return WearUiState(
        live = live,
        statusLabel = status,
        speed = telemetry?.speedKmh?.let { String.format(Locale.US, "%.1f", it) } ?: "--.-",
        battery = telemetry?.boardBatteryPercent?.let { "$it%" } ?: "--%",
        trip = "$tripValue KM",
        tripValue = tripValue,
        rangeValue = telemetry?.estimatedRangeKm?.let {
            String.format(Locale.US, "%.1f", it)
        } ?: "--.-",
        mode = telemetry?.mode?.uppercase(Locale.ROOT) ?: "NO RIDE DATA",
        valueColor = if (live) RidersWhite else RidersMuted,
    )
}

private const val LIVE_FRESHNESS_MS = 30_000L
