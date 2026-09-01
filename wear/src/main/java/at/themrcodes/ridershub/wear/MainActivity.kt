package at.themrcodes.ridershub.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientModeSupport
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import at.themrcodes.ridershub.wear.shared.WearConnectionStatus
import at.themrcodes.ridershub.wear.shared.WearTelemetryState
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : FragmentActivity(), AmbientModeSupport.AmbientCallbackProvider {
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private lateinit var ambientController: AmbientModeSupport.AmbientController
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) syncOngoingActivity()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WearTelemetryRepository.restore(this)
        ambientController = AmbientModeSupport.attach(this)
        setContent {
            RidersHubWearApp(
                telemetry = WearTelemetryStore.telemetry,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback =
        object : AmbientModeSupport.AmbientCallback() {
            override fun onEnterAmbient(ambientDetails: Bundle?) {
                super.onEnterAmbient(ambientDetails)
                val burnIn = ambientDetails?.getBoolean(AmbientModeSupport.EXTRA_BURN_IN_PROTECTION, false) ?: false
                WearAmbientState.isAmbient = true
                WearAmbientState.isBurnInProtectionRequired = burnIn
                WearAmbientState.ambientFrame++
                WearTelemetryStore.setAmbient(true)
                syncOngoingActivity()
            }

            override fun onExitAmbient() {
                super.onExitAmbient()
                WearAmbientState.isAmbient = false
                WearTelemetryStore.setAmbient(false)
                syncOngoingActivity()
            }

            override fun onUpdateAmbient() {
                super.onUpdateAmbient()
                WearTelemetryStore.renderAmbientFrame()
                WearAmbientState.ambientFrame++
                syncOngoingActivity()
            }
        }

    override fun onStart() {
        super.onStart()
        val repositoryRevision = WearTelemetryRepository.currentRevision()
        dataClient.getDataItems().addOnSuccessListener { items ->
            try {
                items.asSequence()
                    .filter { it.uri.path == WearTelemetryState.DATA_PATH }
                    .mapNotNull { item -> decodePayload(item.data) }
                    .maxByOrNull { it.updatedAtEpochMs }
                    ?.let { telemetry ->
                        WearTelemetryRepository.acceptBootstrap(
                            context = this,
                            telemetry = telemetry,
                            expectedRevision = repositoryRevision,
                        )
                    }
            } finally {
                items.release()
            }
        }
    }

    private fun syncOngoingActivity() {
        WearOngoingActivity.sync(
            context = this,
            telemetry = WearTelemetryStore.latest(),
            nowEpochMs = System.currentTimeMillis(),
        )
    }

    private fun decodePayload(bytes: ByteArray?): WearTelemetryState? = decodeWearTelemetry(bytes)
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

internal object WearAmbientState {
    var isAmbient by mutableStateOf(false)
    var isBurnInProtectionRequired by mutableStateOf(false)
    var ambientFrame by mutableIntStateOf(0)
}

internal object WearDisplayPreferences {
    fun keepScreenAwake(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

    fun setKeepScreenAwake(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private const val PREFERENCES_NAME = "wear_display_preferences"
    private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake_during_ride"
}

@Composable
private fun RidersHubWearApp(
    telemetry: WearTelemetryState?,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val ambient = WearAmbientState.isAmbient
    val burnInRequired = WearAmbientState.isBurnInProtectionRequired
    val ambientFrame = WearAmbientState.ambientFrame
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var keepScreenAwake by remember {
        mutableStateOf(WearDisplayPreferences.keepScreenAwake(context))
    }
    var autoOpenOnLive by remember {
        mutableStateOf(WearSettingsPreferences.autoOpenOnLive(context))
    }

    LaunchedEffect(autoOpenOnLive) {
        WearSettingsPublisher.publish(context, autoOpenOnLive)
    }

    val keepScreenOn = shouldKeepScreenOn(keepScreenAwake, telemetry, nowEpochMs)
    DisposableEffect(keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(ambient, ambientFrame) {
        nowEpochMs = System.currentTimeMillis()
        if (ambient) return@LaunchedEffect
        while (true) {
            delay(15_000)
            nowEpochMs = System.currentTimeMillis()
        }
    }
    val uiState = wearUiState(telemetry, nowEpochMs)
    val burnInOffset = if (ambient && burnInRequired) {
        ambientBurnInOffset(ambientFrame)
    } else {
        0 to 0
    }
    val pagerState = rememberPagerState(pageCount = { 2 })

    LaunchedEffect(ambient) {
        if (ambient) pagerState.scrollToPage(DASHBOARD_PAGE)
    }

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RidersBlack),
            contentAlignment = Alignment.Center,
        ) {
            if (ambient) {
                DashboardPage(uiState, ambient = true, burnInOffset)
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        DASHBOARD_PAGE -> DashboardPage(uiState, ambient = false, 0 to 0)
                        else -> DisplaySettingsPage(
                            keepScreenAwake = keepScreenAwake,
                            autoOpenOnLive = autoOpenOnLive,
                            onToggleKeepScreenAwake = { enabled ->
                                keepScreenAwake = enabled
                                WearDisplayPreferences.setKeepScreenAwake(context, enabled)
                            },
                            onToggleAutoOpen = { enabled ->
                                autoOpenOnLive = enabled
                                WearSettingsPreferences.setAutoOpenOnLive(context, enabled)
                            },
                        )
                    }
                }
                PageIndicator(
                    selectedPage = pagerState.currentPage,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun DashboardPage(
    uiState: WearUiState,
    ambient: Boolean,
    burnInOffset: Pair<Int, Int>,
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

@Composable
private fun DisplaySettingsPage(
    keepScreenAwake: Boolean,
    autoOpenOnLive: Boolean,
    onToggleKeepScreenAwake: (Boolean) -> Unit,
    onToggleAutoOpen: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = RidersMuted,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = "RIDE SETTINGS",
            color = RidersMuted,
            fontFamily = RidersMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(13.dp))
        SettingsChoice(
            selected = keepScreenAwake,
            selectedLabel = "LIVE SCREEN",
            unselectedLabel = "AMBIENT",
            selectedDescription = "LIVE DATA STAYS ON\nUSES MORE BATTERY",
            unselectedDescription = "DIMS AFTER TIMEOUT\nUPDATES EACH MINUTE",
            onClick = { onToggleKeepScreenAwake(!keepScreenAwake) },
        )
        Spacer(Modifier.height(8.dp))
        SettingsChoice(
            selected = autoOpenOnLive,
            selectedLabel = "AUTO OPEN",
            unselectedLabel = "MANUAL OPEN",
            selectedDescription = "OPENS ON FIRST\nLIVE FRAME",
            unselectedDescription = "ONGOING ACTIVITY\nONLY",
            onClick = { onToggleAutoOpen(!autoOpenOnLive) },
        )
    }
}

@Composable
private fun SettingsChoice(
    selected: Boolean,
    selectedLabel: String,
    unselectedLabel: String,
    selectedDescription: String,
    unselectedDescription: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RidersLine, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (selected) selectedLabel else unselectedLabel,
            color = if (selected) RidersWhite else RidersAmbient,
            fontFamily = RidersMono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (selected) selectedDescription else unselectedDescription,
            color = RidersMuted,
            fontFamily = RidersMono,
            fontSize = 8.sp,
            lineHeight = 10.sp,
            letterSpacing = 0.6.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PageIndicator(
    selectedPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(2) { page ->
            Box(
                Modifier
                    .size(if (page == selectedPage) 5.dp else 4.dp)
                    .background(
                        if (page == selectedPage) RidersMuted else RidersLine,
                        CircleShape,
                    ),
            )
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

internal fun shouldKeepScreenOn(
    enabled: Boolean,
    telemetry: WearTelemetryState?,
    nowEpochMs: Long,
): Boolean = enabled && shouldKeepRideVisible(telemetry, nowEpochMs)

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
private const val DASHBOARD_PAGE = 0
