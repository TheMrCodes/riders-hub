package at.themrcodes.ridershub

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.themrcodes.ridershub.session.RangeEstimate
import at.themrcodes.ridershub.session.RangeEstimateStatus
import at.themrcodes.ridershub.session.BatteryLongevityBar
import at.themrcodes.ridershub.session.BatteryLongevityChart
import at.themrcodes.ridershub.session.BatteryLongevitySnapshot
import at.themrcodes.ridershub.session.BatteryLongevityStatus
import at.themrcodes.ridershub.session.LongevityGranularity
import at.themrcodes.ridershub.session.RideStore
import at.themrcodes.ridershub.session.RideStoreSnapshot
import at.themrcodes.ridershub.session.RideSummary
import at.themrcodes.ridershub.homeassistant.HomeAssistantIntegration
import at.themrcodes.ridershub.homeassistant.HomeAssistantSnapshot
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var presenceController: PresenceController
    private lateinit var state: AppStateStore
    private lateinit var rideStore: RideStore
    private lateinit var homeAssistant: HomeAssistantIntegration
    private var associationRequestedAfterPermission = false
    private var handledAssociationId: Int? = null
    private var associationInProgress by mutableStateOf(false)
    private var expertControlsUnlocked by mutableStateOf(false)
    private var developerTapCount = 0
    private var dashboard by mutableStateOf<DashboardState?>(null)

    private val associationChooser = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        associationInProgress = false
        if (result.resultCode == RESULT_OK) {
            val association = presenceController.recoverAssociationAfterChooser()
            if (association != null) onAssociationCreated(association)
            else state.setError("Android reported success but no companion association was available")
        } else {
            state.setError("Remote association was cancelled")
        }
        dashboard = loadDashboard()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (REQUIRED_BLUETOOTH_PERMISSIONS.all { grants[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (associationRequestedAfterPermission) startAssociation()
        } else {
            state.setError("Nearby-device permission is required to monitor telemetry")
        }
        associationRequestedAfterPermission = false
        dashboard = loadDashboard()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { dashboard = loadDashboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        presenceController = PresenceController(this)
        state = AppStateStore(this)
        rideStore = RideStore.get(this)
        homeAssistant = HomeAssistantIntegration.get(this)
        expertControlsUnlocked = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getBoolean(KEY_EXPERT_CONTROLS_UNLOCKED, false)
        dashboard = loadDashboard()
        ensureExistingAssociationIsObserved()
        requestNotificationPermissionIfNeeded()

        setContent {
            RidersHubTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        dashboard = loadDashboard()
                        delay(1_000)
                    }
                }
                RidersHubDashboard(
                    dashboard = dashboard ?: loadDashboard(),
                    associationInProgress = associationInProgress,
                    expertControlsUnlocked = expertControlsUnlocked,
                    onAssociate = ::requestAssociationWithPermissions,
                    onOpenSettings = ::openPermissionSettings,
                    onVersionTap = ::onVersionTapped,
                    onSetChildLimiter = ::requestChildLimiter,
                    onSetHomeAssistantEnabled = ::setHomeAssistantEnabled,
                    onConnectHomeAssistant = homeAssistant::connect,
                    onSaveHomeAssistantWebhook = ::saveHomeAssistantWebhook,
                    onDisconnectHomeAssistant = ::disconnectHomeAssistant,
                )
            }
        }
    }

    private fun loadDashboard(): DashboardState {
        val app = state.snapshot()
        val restingVoltage = app.packVoltageV
            ?.takeIf { (app.speedKmh ?: Float.MAX_VALUE) <= 0.5f }
            ?.toDouble()
        return DashboardState(
            app = app,
            rides = rideStore.snapshot(app.boardBatteryPercent, restingVoltage),
            homeAssistant = homeAssistant.snapshot(),
        )
    }

    private fun setHomeAssistantEnabled(enabled: Boolean) {
        homeAssistant.setEnabled(enabled)
        dashboard = loadDashboard()
    }

    private fun saveHomeAssistantWebhook(webhookUrl: String) {
        homeAssistant.saveWebhookUrl(webhookUrl)
        dashboard = loadDashboard()
    }

    private fun disconnectHomeAssistant() {
        homeAssistant.disconnect()
        dashboard = loadDashboard()
    }

    private fun requestAssociationWithPermissions() {
        val missing = REQUIRED_BLUETOOTH_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            associationRequestedAfterPermission = true
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        } else {
            startAssociation()
        }
    }

    private fun startAssociation() {
        state.clearError()
        associationInProgress = true
        presenceController.startAssociation(
            launchChooser = ::launchAssociationChooser,
            onCreated = ::onAssociationCreated,
            onFailure = { message ->
                state.setError(message)
                associationInProgress = false
                dashboard = loadDashboard()
            },
        )
    }

    private fun launchAssociationChooser(sender: IntentSender) {
        try {
            associationChooser.launch(IntentSenderRequest.Builder(sender).build())
        } catch (error: Exception) {
            state.setError("Could not open Android's companion chooser: ${error.message}")
            associationInProgress = false
        }
    }

    private fun onAssociationCreated(association: BackfireAssociation) {
        if (handledAssociationId == association.associationId) return
        handledAssociationId = association.associationId
        associationInProgress = false
        armAssociation(association)
        Toast.makeText(this, "Remote associated", Toast.LENGTH_SHORT).show()
        dashboard = loadDashboard()
    }

    private fun armAssociation(association: BackfireAssociation) {
        state.setAssociation(association.address, association.displayName, association.associationId)
        presenceController.ensureObserving(association).onFailure { error ->
            state.setError("Could not watch remote presence: ${error.message}")
        }
    }

    private fun ensureExistingAssociationIsObserved() {
        val association = presenceController.existingAssociations().firstOrNull() ?: return
        armAssociation(association)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openPermissionSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            },
        )
    }

    private fun onVersionTapped() {
        if (expertControlsUnlocked) return
        developerTapCount += 1
        val remaining = DEVELOPER_UNLOCK_TAPS - developerTapCount
        if (remaining <= 0) {
            expertControlsUnlocked = true
            getSharedPreferences("app_settings", MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXPERT_CONTROLS_UNLOCKED, true)
                .apply()
            Toast.makeText(this, "Experimental remote controls unlocked", Toast.LENGTH_LONG).show()
        } else if (remaining <= 3) {
            Toast.makeText(this, "$remaining more taps to unlock experimental controls", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestChildLimiter(enabled: Boolean) {
        state.setLimiterCommandStatus("User confirmed ${if (enabled) "enable" else "disable"}; requesting write")
        sendBroadcast(
            Intent(LimiterControl.ACTION_SET_CHILD_LIMITER)
                .setPackage(packageName)
                .putExtra(LimiterControl.EXTRA_ENABLED, enabled),
        )
        dashboard = loadDashboard()
    }

    companion object {
        private val REQUIRED_BLUETOOTH_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        private const val DEVELOPER_UNLOCK_TAPS = 7
        private const val KEY_EXPERT_CONTROLS_UNLOCKED = "expert_controls_unlocked"
    }
}

private data class DashboardState(
    val app: AppSnapshot,
    val rides: RideStoreSnapshot,
    val homeAssistant: HomeAssistantSnapshot,
)

private val NothingRed = Color(0xFFD71921)
private val NothingBlack = Color(0xFF000000)
private val NothingPanel = Color(0xFF0B0B0B)
private val NothingRaised = Color(0xFF171717)
private val NothingLine = Color(0xFF292929)
private val NothingWhite = Color(0xFFF2F2F2)
private val NothingMuted = Color(0xFF929292)
private val NothingInactive = Color(0xFFA6A6A6)
private val NothingMono = FontFamily.Monospace

private val RidersHubColors = darkColorScheme(
    primary = NothingRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF360507),
    onPrimaryContainer = Color.White,
    secondary = NothingWhite,
    background = NothingBlack,
    surface = NothingPanel,
    surfaceVariant = NothingRaised,
    onSurface = NothingWhite,
    onSurfaceVariant = NothingMuted,
    error = NothingRed,
)

@Composable
private fun RidersHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RidersHubColors, content = content)
}

@Composable
private fun RidersHubDashboard(
    dashboard: DashboardState,
    associationInProgress: Boolean,
    expertControlsUnlocked: Boolean,
    onAssociate: () -> Unit,
    onOpenSettings: () -> Unit,
    onVersionTap: () -> Unit,
    onSetChildLimiter: (Boolean) -> Unit,
    onSetHomeAssistantEnabled: (Boolean) -> Unit,
    onConnectHomeAssistant: (String, String) -> Unit,
    onSaveHomeAssistantWebhook: (String) -> Unit,
    onDisconnectHomeAssistant: () -> Unit,
) {
    val app = dashboard.app
    val rides = dashboard.rides
    var page by remember { mutableStateOf(DashboardPage.OVERVIEW) }
    var pendingLimiterChoice by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = page == DashboardPage.DEVICE) { page = DashboardPage.OVERVIEW }
    Surface(Modifier.fillMaxSize(), color = NothingBlack) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            when (page) {
                DashboardPage.OVERVIEW -> {
                    item { NothingHeader(app, onOpenDevice = { page = DashboardPage.DEVICE }) }
                    app.error?.let { message -> item { ErrorWidget(message) } }
                    item { CurrentTripWidget(app, rides.currentTripDisplay) }
                    item { RangeWidget(rides.rangeEstimate, Modifier.padding(top = 12.dp)) }
                    item {
                        BatteryLongevitySection(
                            rides.batteryLongevity,
                            Modifier.padding(top = 12.dp),
                        )
                    }
                    item { RidesHeader(app.odometerKm, Modifier.padding(top = 12.dp)) }
                    if (rides.recentTracks.isEmpty()) {
                        item { EmptyRidesWidget() }
                    } else {
                        items(rides.recentTracks, key = { it.id }) { ride -> RideWidget(ride) }
                    }
                }

                DashboardPage.DEVICE -> {
                    item { DeviceHeader(onBack = { page = DashboardPage.OVERVIEW }) }
                    app.error?.let { message -> item { ErrorWidget(message) } }
                    item {
                        RemoteSection(
                            app = app,
                            associationInProgress = associationInProgress,
                            onAssociate = onAssociate,
                        )
                    }
                    if (expertControlsUnlocked) {
                        item {
                            ExperimentalControl(
                                app = app,
                                onSetChildLimiter = { pendingLimiterChoice = it },
                            )
                        }
                    }
                    item {
                        HomeAssistantSection(
                            state = dashboard.homeAssistant,
                            onSetEnabled = onSetHomeAssistantEnabled,
                            onConnect = onConnectHomeAssistant,
                            onSaveWebhook = onSaveHomeAssistantWebhook,
                            onDisconnect = onDisconnectHomeAssistant,
                        )
                    }
                    item { AppSection(onOpenSettings = onOpenSettings, onVersionTap = onVersionTap) }
                }
            }
        }
    }
    pendingLimiterChoice?.let { enabled ->
        LimiterConfirmationDialog(
            enabled = enabled,
            onConfirm = {
                pendingLimiterChoice = null
                onSetChildLimiter(enabled)
            },
            onDismiss = { pendingLimiterChoice = null },
        )
    }
}

private enum class DashboardPage { OVERVIEW, DEVICE }

@Composable
private fun NothingHeader(app: AppSnapshot, onOpenDevice: () -> Unit) {
    val listening = app.serviceActive && app.connection.contains("Listening", ignoreCase = true)
    val stateLabel = if (listening) "Live" else if (app.serviceActive && app.present) "Nearby" else "Standby"
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            DotMatrixText("RIDERS HUB", Modifier.fillMaxWidth(0.78f).height(44.dp))
            DeviceIconButton(onClick = onOpenDevice, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            NothingText(app.name ?: "No remote", color = NothingMuted, size = 11, spacing = 0.5f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (listening) NothingRed else NothingMuted, CircleShape))
                Spacer(Modifier.width(8.dp))
                NothingText(stateLabel, color = NothingMuted, size = 11)
            }
        }
    }
}

@Composable
private fun DeviceHeader(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        BackIconButton(onClick = onBack)
        Spacer(Modifier.height(28.dp))
        DotMatrixText("DEVICE", Modifier.fillMaxWidth(0.62f).height(44.dp))
        Spacer(Modifier.height(18.dp))
        NothingText("Remote and app settings", color = NothingMuted, size = 11)
    }
}

@Composable
private fun DeviceIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open device settings" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(25.dp)) {
            val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
            drawRoundRect(
                color = NothingWhite,
                topLeft = Offset(size.width * 0.22f, size.height * 0.06f),
                size = Size(size.width * 0.56f, size.height * 0.88f),
                cornerRadius = CornerRadius(5.dp.toPx()),
                style = stroke,
            )
            drawCircle(NothingRed, 2.3.dp.toPx(), Offset(size.width / 2f, size.height * 0.32f))
            drawCircle(NothingMuted, 1.8.dp.toPx(), Offset(size.width / 2f, size.height * 0.68f))
        }
    }
}

@Composable
private fun BackIconButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Back to overview" },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.size(27.dp)) {
            val strokeWidth = 1.8.dp.toPx()
            drawLine(NothingWhite, Offset(size.width * 0.76f, size.height * 0.16f), Offset(size.width * 0.24f, size.height / 2f), strokeWidth, StrokeCap.Round)
            drawLine(NothingWhite, Offset(size.width * 0.24f, size.height / 2f), Offset(size.width * 0.76f, size.height * 0.84f), strokeWidth, StrokeCap.Round)
            drawLine(NothingWhite, Offset(size.width * 0.24f, size.height / 2f), Offset(size.width * 0.96f, size.height / 2f), strokeWidth, StrokeCap.Round)
        }
    }
}

@Composable
private fun CurrentTripWidget(app: AppSnapshot, ride: RideSummary?) {
    val listening = app.serviceActive && app.connection.contains("Listening", ignoreCase = true)
    val rideOpen = ride?.active == true
    val telemetryActive = rideOpen && listening
    val connectionDetail = app.connection.lowercase()
    val connectionStatus = when {
        listening -> "Telemetry from your remote"
        "connecting" in connectionDetail || "discovering" in connectionDetail ||
            "enabling" in connectionDetail -> "Connecting to your remote"
        "retry" in connectionDetail || "disconnected" in connectionDetail -> "Reconnecting to your remote"
        app.serviceActive && app.present -> "Remote nearby · starting telemetry"
        else -> "Waiting for your remote"
    }
    val title = if (telemetryActive) "Current Trip" else "Last Trip"
    val valueColor = if (telemetryActive) NothingWhite else NothingInactive
    val packVoltage = if (rideOpen) {
        app.packVoltageV?.toDouble() ?: ride.packVoltageEnd
    } else {
        ride?.packVoltageEnd
    }
    val batteryPercent = if (rideOpen) {
        app.boardBatteryPercent ?: ride.boardBatteryEnd
    } else {
        ride?.boardBatteryEnd
    }
    Column {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(end = 78.dp)) {
                    SectionTitle(title, connectionStatus, titleColor = valueColor)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    NothingText(
                        ride?.let { "%.2f".format(it.distanceKm) } ?: "--.--",
                        color = valueColor,
                        size = 58,
                        weight = FontWeight.Black,
                        lineHeight = 60,
                    )
                    NothingText("km", Modifier.padding(bottom = 10.dp), NothingMuted, 11, FontWeight.Bold, 0.5f)
                }
                Spacer(Modifier.height(28.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QuietMetric(
                        "Moving",
                        ride?.let { formatDuration(it.movingSeconds) } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        "Top speed",
                        ride?.let { "%.1f km/h".format(it.maxSpeedKmh) } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        "Pack",
                        packVoltage?.let { "%.1f V".format(it) } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        "Battery",
                        batteryPercent?.let { "$it%" } ?: "—",
                        Modifier.width(56.dp),
                        Alignment.CenterHorizontally,
                        valueColor,
                    )
                }
            }
            BatteryMeter(
                percent = batteryPercent,
                active = telemetryActive,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Spacer(Modifier.height(20.dp))
        SpeedScale(app.speedKmh)
        if ((app.boardBatteryPercent ?: 101) <= BatteryWarningNotifier.WARNING_PERCENT) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(NothingRed, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                NothingText("Low battery", color = Color.White, size = 14, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                NothingText("Recharge before your next ride.", color = Color.White, size = 10)
            }
        }
    }
}

@Composable
private fun RangeWidget(estimate: RangeEstimate, modifier: Modifier = Modifier) {
    val collecting = estimate.status == RangeEstimateStatus.COLLECTING_DATA
    val subtitle = when (estimate.status) {
        RangeEstimateStatus.COLLECTING_DATA -> "A short ride is enough to get started"
        RangeEstimateStatus.PROVISIONAL -> "An early estimate from your rides"
        RangeEstimateStatus.CALIBRATED -> "Calibrated to your riding"
    }
    Column(modifier) {
        SectionTitle("Range", subtitle)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                NothingText(estimate.remainingKm?.let { "%04.1f".format(it) } ?: "--.-", size = 58, weight = FontWeight.Black, lineHeight = 60)
                NothingText("km", Modifier.padding(bottom = 10.dp), NothingMuted, 11, FontWeight.Bold, 0.5f)
            }
            NothingText(
                when (estimate.status) {
                    RangeEstimateStatus.COLLECTING_DATA -> "Collecting"
                    RangeEstimateStatus.PROVISIONAL -> "Provisional"
                    RangeEstimateStatus.CALIBRATED -> "Ready"
                },
                color = if (collecting) NothingMuted else NothingRed,
                size = 10,
                weight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(16.dp))
        RangeScale(estimate.confidencePercent / 100f)
        Spacer(Modifier.height(16.dp))
        val explanation = if (collecting) {
            "Ride 1 km and use 2% battery to unlock an estimate."
        } else {
            estimate.message
        }
        NothingText(explanation, color = NothingMuted, size = 11, lineHeight = 17)
    }
}

@Composable
private fun BatteryLongevitySection(
    longevity: BatteryLongevitySnapshot,
    modifier: Modifier = Modifier,
) {
    var granularity by remember { mutableStateOf(LongevityGranularity.DAY) }
    var focusStart by remember { mutableStateOf<java.time.Instant?>(null) }
    var focusEnd by remember { mutableStateOf<java.time.Instant?>(null) }
    var selectedBarId by remember { mutableStateOf<String?>(null) }
    val bars = BatteryLongevityChart.aggregate(
        observations = longevity.observations,
        granularity = granularity,
        zoneId = ZoneId.systemDefault(),
        focusStart = focusStart,
        focusEndExclusive = focusEnd,
    )
    val selected = bars.firstOrNull { it.id == selectedBarId }
    val collecting = longevity.status == BatteryLongevityStatus.COLLECTING_DATA

    Column(modifier) {
        SectionTitle(
            "Battery Longevity",
            if (collecting) {
                "Building a speed-normalized battery baseline"
            } else {
                "Estimated full-charge capacity under the same riding profile"
            },
        )
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                NothingText(
                    longevity.currentFullRangeKm?.let { "%04.1f".format(it) } ?: "--.-",
                    size = 50,
                    weight = FontWeight.Black,
                    lineHeight = 54,
                )
                NothingText(
                    "km / full",
                    Modifier.padding(bottom = 8.dp),
                    NothingMuted,
                    10,
                    FontWeight.Bold,
                    0.3f,
                )
            }
            QuietMetric(
                label = "Observed cycles",
                value = longevity.observedCycleCount.toString(),
                horizontalAlignment = Alignment.End,
            )
        }
        Spacer(Modifier.height(12.dp))
        NothingText(longevity.message, color = NothingMuted, size = 11, lineHeight = 17)
        Spacer(Modifier.height(22.dp))
        if (bars.isEmpty()) {
            BatteryLongevityPlaceholder()
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NothingText(granularity.displayName, color = NothingRed, size = 10, weight = FontWeight.Bold)
                NothingText(
                    "0 – ${"%.1f".format(longevity.allTimeHighKm)} km",
                    color = NothingMuted,
                    size = 9,
                )
            }
            Spacer(Modifier.height(10.dp))
            BatteryLongevityChartView(
                bars = bars,
                allTimeHighKm = longevity.allTimeHighKm ?: 1.0,
                selectedBarId = selectedBarId,
                onBarSelected = { bar ->
                    val finer = granularity.finer()
                    if (finer == null) {
                        selectedBarId = bar.id
                    } else {
                        focusStart = bar.start
                        focusEnd = bar.endExclusive
                        granularity = finer
                        selectedBarId = null
                    }
                },
                onZoomOut = {
                    granularity.coarser()?.let { coarser ->
                        granularity = coarser
                        focusStart = null
                        focusEnd = null
                        selectedBarId = null
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            NothingText(
                selected?.let {
                    "${it.label} · ${"%.1f".format(it.fullRangeKm)} km from ${it.observationCount} observation${if (it.observationCount == 1) "" else "s"}"
                } ?: "Pinch inward to group time · tap a bar to inspect one level deeper",
                color = NothingMuted,
                size = 9,
                lineHeight = 15,
            )
        }
    }
}

@Composable
private fun BatteryLongevityChartView(
    bars: List<BatteryLongevityBar>,
    allTimeHighKm: Double,
    selectedBarId: String?,
    onBarSelected: (BatteryLongevityBar) -> Unit,
    onZoomOut: () -> Unit,
) {
    val scaleMax = allTimeHighKm.coerceAtLeast(1.0)
    val currentOnBarSelected by rememberUpdatedState(onBarSelected)
    val currentOnZoomOut by rememberUpdatedState(onZoomOut)
    Column(
        Modifier
            .fillMaxWidth()
            .background(NothingRaised, RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(142.dp)
                .pointerInput(bars) {
                    detectTapGestures { offset ->
                        val slotWidth = size.width / bars.size
                        val index = (offset.x / slotWidth).toInt().coerceIn(0, bars.lastIndex)
                        currentOnBarSelected(bars[index])
                    }
                }
                .pointerInput(Unit) {
                    var accumulatedZoom = 1f
                    detectTransformGestures { _, _, zoom, _ ->
                        accumulatedZoom *= zoom
                        if (accumulatedZoom < 0.78f) {
                            currentOnZoomOut()
                            accumulatedZoom = 1f
                        }
                    }
                }
                .semantics {
                    contentDescription = "Battery capacity bar chart from zero to ${"%.1f".format(scaleMax)} kilometres"
                },
        ) {
            val baseline = size.height - 3.dp.toPx()
            drawLine(
                color = NothingLine,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx(),
            )
            val slotWidth = size.width / bars.size
            bars.forEachIndexed { index, bar ->
                val height = (baseline * (bar.fullRangeKm / scaleMax).coerceIn(0.0, 1.0)).toFloat()
                val width = slotWidth * 0.56f
                val left = index * slotWidth + (slotWidth - width) / 2f
                val color = if (bar.id == selectedBarId || index == bars.lastIndex) {
                    NothingRed
                } else {
                    NothingWhite
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, baseline - height),
                    size = Size(width, height.coerceAtLeast(2.dp.toPx())),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { index, bar ->
                NothingText(
                    text = if (bars.size <= 6 || index % 2 == 0 || index == bars.lastIndex) bar.label else "",
                    modifier = Modifier.weight(1f),
                    color = NothingMuted,
                    size = 8,
                    align = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BatteryLongevityPlaceholder() {
    Column(
        Modifier
            .fillMaxWidth()
            .height(156.dp)
            .background(NothingRaised, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NothingText("NO CAPACITY HISTORY", color = NothingMuted, size = 10, weight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        NothingText("The first usable charge observation will appear here.", color = NothingMuted, size = 9)
    }
}

@Composable
private fun RidesHeader(odometerKm: Float?, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle("Rides", "Lifetime distance reported by your board")
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            NothingText(
                odometerKm?.let { "%.1f".format(it) } ?: "----.-",
                size = 58,
                weight = FontWeight.Black,
                lineHeight = 60,
            )
            NothingText("km", Modifier.padding(bottom = 10.dp), NothingMuted, 11, FontWeight.Bold, 0.5f)
        }
    }
}

@Composable
private fun RideWidget(ride: RideSummary) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                NothingText(formatDate(ride.startedAt), color = NothingMuted, size = 10)
                Spacer(Modifier.height(8.dp))
                NothingText(ride.modes.joinToString(" · ").ifEmpty { "Ride" }, size = 16, weight = FontWeight.Bold)
            }
            NothingText("%.2f km".format(ride.distanceKm), size = 24, weight = FontWeight.Black)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuietMetric("Moving", formatDuration(ride.movingSeconds))
            QuietMetric("Top speed", "%.1f km/h".format(ride.maxSpeedKmh))
            QuietMetric("Battery", batteryDelta(ride))
        }
        Spacer(Modifier.height(28.dp))
        HorizontalDivider(color = NothingLine)
    }
}

@Composable
private fun EmptyRidesWidget() {
    Column(Modifier.fillMaxWidth()) {
        RidePath()
        Spacer(Modifier.height(20.dp))
        NothingText("No rides yet", size = 22, weight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        NothingText("Your first session will appear automatically.", color = NothingMuted, size = 11, lineHeight = 17)
    }
}

@Composable
private fun RemoteSection(
    app: AppSnapshot,
    associationInProgress: Boolean,
    onAssociate: () -> Unit,
) {
    val linkActive = app.serviceActive && app.connection.contains("Listening", ignoreCase = true)
    val connectionDetail = app.connection.lowercase()
    val state = when {
        linkActive -> "Receiving telemetry"
        "connecting" in connectionDetail || "discovering" in connectionDetail ||
            "enabling" in connectionDetail -> "Connecting to telemetry"
        "retry" in connectionDetail || "disconnected" in connectionDetail -> "Retrying telemetry"
        app.serviceActive && app.present -> "Remote nearby"
        app.observing -> "Monitoring in the background"
        else -> "Background monitoring is off"
    }
    Column {
        SectionTitle("Remote", "Your paired board controller")
        Spacer(Modifier.height(32.dp))
        NothingText(app.name ?: "No remote associated", size = 30, weight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (linkActive) NothingRed else NothingMuted, CircleShape))
            Spacer(Modifier.width(10.dp))
            NothingText(state, color = NothingMuted, size = 11)
        }
        Spacer(Modifier.height(16.dp))
        NothingText(
            "Android watches for the remote and stops background work when it leaves.",
            color = NothingMuted,
            size = 11,
            lineHeight = 18,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onAssociate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !associationInProgress,
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = NothingWhite,
                contentColor = NothingBlack,
                disabledContainerColor = NothingRaised,
                disabledContentColor = NothingMuted,
            ),
            contentPadding = PaddingValues(vertical = 18.dp),
        ) {
            NothingText(
                if (associationInProgress) "OPENING CHOOSER"
                else if (app.address == null) "PAIR SUPPORTED REMOTE"
                else "PAIR DIFFERENT REMOTE",
                color = if (associationInProgress) NothingMuted else NothingBlack,
                size = 11,
                weight = FontWeight.Bold,
                spacing = 1f,
            )
        }
    }
}

@Composable
private fun ExperimentalControl(
    app: AppSnapshot,
    onSetChildLimiter: (Boolean) -> Unit,
) {
    val commandPending = listOf("requesting", "sent", "waiting", "accepted").any { token ->
        app.limiterCommandStatus?.contains(token, ignoreCase = true) == true
    }
    Column {
        SectionTitle("Experimental", "Unverified protocol controls")
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                NothingText("Child speed limiter", size = 16, weight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                NothingText("The exact speed limit is unknown.", color = NothingMuted, size = 10)
            }
            Spacer(Modifier.width(18.dp))
            Switch(
                checked = app.childLimiterActive == true,
                onCheckedChange = onSetChildLimiter,
                enabled = app.limiterControlAvailable && app.childLimiterActive != null && !commandPending,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NothingRed,
                    uncheckedThumbColor = NothingWhite,
                    uncheckedTrackColor = NothingRaised,
                    uncheckedBorderColor = NothingMuted,
                    disabledCheckedTrackColor = NothingRed.copy(alpha = 0.35f),
                ),
            )
        }
        app.limiterCommandStatus?.let {
            Spacer(Modifier.height(14.dp))
            NothingText(it, color = NothingRed, size = 10, lineHeight = 15)
        }
    }
}

@Composable
private fun HomeAssistantSection(
    state: HomeAssistantSnapshot,
    onSetEnabled: (Boolean) -> Unit,
    onConnect: (String, String) -> Unit,
    onSaveWebhook: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var instanceUrl by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var webhookUrl by remember(state.webhookUrl) { mutableStateOf(state.webhookUrl.orEmpty()) }
    LaunchedEffect(state.connected) {
        if (state.connected) {
            instanceUrl = ""
            accessToken = ""
        }
    }

    Column {
        SectionTitle("Home Assistant", "Optional, low-frequency webhook export")
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                NothingText("Enable Home Assistant", size = 14, weight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                NothingText(
                    if (state.enabled) "Delivery enabled" else "No data is sent",
                    color = NothingMuted,
                    size = 10,
                )
            }
            Spacer(Modifier.width(18.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = onSetEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NothingRed,
                    uncheckedThumbColor = NothingWhite,
                    uncheckedTrackColor = NothingRaised,
                    uncheckedBorderColor = NothingMuted,
                ),
            )
        }
        Spacer(Modifier.height(18.dp))
        NothingText("Status", color = NothingMuted, size = 10)
        Spacer(Modifier.height(6.dp))
        NothingText(
            state.status,
            color = if (
                state.status.contains("failed", true) ||
                state.status.contains("reconnect", true) ||
                state.status.contains("rejected", true)
            ) {
                NothingRed
            } else {
                NothingWhite
            },
            size = 10,
            lineHeight = 15,
        )
        if (state.connected) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NothingText("Webhook encryption", color = NothingMuted, size = 10)
                NothingText(if (state.payloadEncrypted) "ON" else "PENDING", size = 10)
            }
        }
        state.lastDeliveryAt?.let { lastDelivery ->
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                NothingText("Last delivery", color = NothingMuted, size = 10)
                NothingText(formatLocalDateTime(lastDelivery), size = 10)
            }
        }

        if (state.enabled) {
            Spacer(Modifier.height(24.dp))
            if (!state.connected) {
                NothingText(
                    "1. In the Home Assistant Companion app: Sidebar → Profile → Security → Long-Lived Access Tokens → Create Token.\n" +
                        "2. Enter the Home Assistant base URL and token below.\n" +
                        "3. Tap Connect. Riders Hub registers its sensors, securely keeps the webhook credentials, and forgets both entries.",
                    color = NothingMuted,
                    size = 10,
                    lineHeight = 16,
                )
                Spacer(Modifier.height(18.dp))
                HomeAssistantTextField(
                    value = instanceUrl,
                    onValueChange = { instanceUrl = it },
                    label = "Home Assistant URL",
                    placeholder = "https://home.example.com",
                    enabled = !state.busy,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(12.dp))
                HomeAssistantTextField(
                    value = accessToken,
                    onValueChange = { accessToken = it },
                    label = "Long-lived access token",
                    placeholder = "Token",
                    enabled = !state.busy,
                    keyboardType = KeyboardType.Password,
                    password = true,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onConnect(instanceUrl, accessToken) },
                    enabled = !state.busy && instanceUrl.isNotBlank() && accessToken.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NothingRed),
                ) {
                    NothingLabel(if (state.busy) "CONNECTING" else "CONNECT", Color.White)
                }
                Spacer(Modifier.height(12.dp))
                NothingText(
                    "Use HTTPS for remote addresses. Plain HTTP is accepted only for private or .local addresses on a trusted LAN.",
                    color = NothingMuted,
                    size = 9,
                    lineHeight = 14,
                )
            } else {
                NothingText(
                    "This route can be a local Home Assistant webhook, a Nabu Casa cloudhook, or your HTTPS proxy such as https://ha.example.com/api/webhook/…. The encryption key remains protected by Android Keystore.",
                    color = NothingMuted,
                    size = 10,
                    lineHeight = 16,
                )
                Spacer(Modifier.height(18.dp))
                HomeAssistantTextField(
                    value = webhookUrl,
                    onValueChange = { webhookUrl = it },
                    label = "Webhook URL",
                    placeholder = "https://hooks.nabu.casa/…",
                    enabled = !state.busy,
                    keyboardType = KeyboardType.Uri,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onSaveWebhook(webhookUrl) },
                    enabled = !state.busy && webhookUrl.isNotBlank() && webhookUrl != state.webhookUrl,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = NothingRaised),
                ) {
                    NothingLabel("SAVE WEBHOOK", NothingWhite)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) {
                    NothingLabel("DISCONNECT", NothingRed)
                }
                NothingText(
                    "Disconnect forgets this app's webhook. Remove the Riders Hub entry in Home Assistant separately if you no longer want it there.",
                    color = NothingMuted,
                    size = 9,
                    lineHeight = 14,
                )
            }
        }
    }
}

@Composable
private fun HomeAssistantTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { NothingText(label, color = NothingMuted, size = 10) },
        placeholder = { NothingText(placeholder, color = NothingMuted, size = 10) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun AppSection(onOpenSettings: () -> Unit, onVersionTap: () -> Unit) {
    Column {
        SectionTitle("App", "Permissions and version")
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings)
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NothingText("Permission settings", size = 14, weight = FontWeight.Bold)
            NothingText("›", color = NothingMuted, size = 24)
        }
        HorizontalDivider(color = NothingLine)
        NothingText(
            "Version ${BuildConfig.VERSION_NAME}",
            modifier = Modifier.clickable(onClick = onVersionTap).padding(vertical = 22.dp),
            color = NothingMuted,
            size = 10,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, titleColor: Color = NothingWhite) {
    Column {
        NothingText(title, color = titleColor, size = 19, weight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        NothingText(subtitle, color = NothingMuted, size = 11, lineHeight = 17)
    }
}

@Composable
private fun QuietMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    valueColor: Color = NothingWhite,
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        NothingText(label, color = NothingMuted, size = 10)
        Spacer(Modifier.height(6.dp))
        NothingText(value, color = valueColor, size = 13, weight = FontWeight.Bold)
    }
}

@Composable
private fun BatteryMeter(percent: Int?, active: Boolean, modifier: Modifier = Modifier) {
    val fraction = (percent ?: 0).coerceIn(0, 100) / 100f
    val markerColor = when {
        !active -> NothingInactive
        (percent ?: 101) <= BatteryWarningNotifier.WARNING_PERCENT -> NothingRed
        else -> NothingWhite
    }
    Box(
        modifier
            .width(56.dp)
            .height(152.dp)
            .background(NothingRaised, RoundedCornerShape(28.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            repeat(5) { index ->
                val y = top + (bottom - top) * index / 4f
                drawCircle(NothingLine, 4.dp.toPx(), Offset(size.width / 2f, y))
            }
            if (percent != null) {
                val y = bottom - (bottom - top) * fraction
                drawCircle(markerColor, 8.dp.toPx(), Offset(size.width / 2f, y))
            }
        }
    }
}

@Composable
private fun SpeedScale(speedKmh: Float?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(NothingRaised, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "Speed scale" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val levels = listOf(0.70f, 0.42f, 0.54f, 0.30f, 0.62f, 0.38f, 0.48f, 0.28f, 0.44f)
            val step = size.width / (levels.size - 1)
            val path = Path()
            levels.forEachIndexed { index, level ->
                val point = Offset(index * step, size.height * level)
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, NothingLine, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            val activeIndex = speedKmh?.let { ((it.coerceIn(0f, 50f) / 50f) * (levels.size - 1)).roundToInt() }
            levels.forEachIndexed { index, level ->
                drawCircle(
                    color = if (index == activeIndex) NothingRed else NothingMuted,
                    radius = if (index == activeIndex) 5.dp.toPx() else 3.3.dp.toPx(),
                    center = Offset(index * step, size.height * level),
                )
            }
        }
    }
}

@Composable
private fun RangeScale(progress: Float) {
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(NothingRaised, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics { contentDescription = "Range estimate readiness ${(fraction * 100).roundToInt()} percent" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            drawLine(NothingLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            repeat(7) { index ->
                val x = size.width * index / 6f
                drawCircle(if (index / 6f <= fraction) NothingWhite else NothingLine, 3.5.dp.toPx(), Offset(x, y))
            }
            drawCircle(NothingRed, 6.dp.toPx(), Offset(size.width * fraction, y))
        }
    }
}

@Composable
private fun RidePath() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(NothingRaised, RoundedCornerShape(24.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .semantics { contentDescription = "Ride history placeholder" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val points = listOf(
                Offset(0f, size.height * 0.68f),
                Offset(size.width * 0.25f, size.height * 0.32f),
                Offset(size.width * 0.50f, size.height * 0.60f),
                Offset(size.width * 0.75f, size.height * 0.24f),
                Offset(size.width, size.height * 0.46f),
            )
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, NothingLine, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { index, point ->
                drawCircle(if (index == 0) NothingRed else NothingMuted, if (index == 0) 5.dp.toPx() else 3.5.dp.toPx(), point)
            }
        }
    }
}

@Composable
private fun LimiterConfirmationDialog(
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NothingPanel,
        shape = RoundedCornerShape(22.dp),
        title = { NothingText("${if (enabled) "Enable" else "Disable"} child limiter?", size = 20, weight = FontWeight.Black) },
        text = {
            NothingText(
                "Keep the board stationary with its wheels clear. This recovered command is not yet validated on the G3. " +
                    if (enabled) "Enabling it should reduce the allowed speed." else "Disabling it may restore full speed.",
                color = NothingMuted,
                size = 11,
                lineHeight = 18,
                spacing = 0.4f,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                NothingLabel("SEND ${if (enabled) "ENABLE" else "DISABLE"}", NothingRed)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { NothingLabel("CANCEL", NothingWhite) } },
    )
}

@Composable
private fun ErrorWidget(message: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(NothingRaised, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        NothingText("Needs attention", color = NothingRed, size = 13, weight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        NothingText(message, color = NothingMuted, size = 11, lineHeight = 17)
    }
}

@Composable
private fun DotMatrixText(text: String, modifier: Modifier = Modifier, color: Color = NothingWhite) {
    Canvas(modifier.semantics { contentDescription = text }) {
        val glyphs = text.uppercase().map { DOT_GLYPHS[it] ?: DOT_GLYPHS.getValue(' ') }
        val totalColumns = glyphs.sumOf { it.first().length } + (glyphs.size - 1)
        val step = minOf(size.width / totalColumns.coerceAtLeast(1), size.height / 7f)
        val contentWidth = totalColumns * step
        var startX = (size.width - contentWidth) / 2f
        val startY = (size.height - 7f * step) / 2f
        glyphs.forEach { glyph ->
            glyph.forEachIndexed { row, line ->
                line.forEachIndexed { column, pixel ->
                    if (pixel == '1') {
                        drawCircle(
                            color = color,
                            radius = step * 0.29f,
                            center = Offset(startX + column * step + step / 2f, startY + row * step + step / 2f),
                        )
                    }
                }
            }
            startX += (glyph.first().length + 1) * step
        }
    }
}

@Composable
private fun NothingLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = NothingMono,
        fontWeight = FontWeight.Bold,
        fontSize = 9.sp,
        letterSpacing = 1.1.sp,
        maxLines = 2,
    )
}

@Composable
private fun NothingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NothingWhite,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    spacing: Float = 0f,
    lineHeight: Int = size + 4,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = NothingMono,
        fontWeight = weight,
        fontSize = size.sp,
        letterSpacing = spacing.sp,
        lineHeight = lineHeight.sp,
        textAlign = align,
        maxLines = maxLines,
    )
}

private val DOT_GLYPHS = mapOf(
    'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    'C' to listOf("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    'D' to listOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
    'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
    'I' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    'K' to listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    'U' to listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
    'V' to listOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    ' ' to listOf("000", "000", "000", "000", "000", "000", "000"),
)

private fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return if (total >= 3600) "%d:%02d h".format(total / 3600, total % 3600 / 60)
    else "%d:%02d".format(total / 60, total % 60)
}

private fun formatDate(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")
        .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault(value)

private fun formatLocalDateTime(value: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
}.getOrDefault(value)

private fun batteryDelta(ride: RideSummary): String {
    val start = ride.boardBatteryStart ?: return "—"
    val end = ride.boardBatteryEnd ?: return "$start%"
    return "$start → $end%"
}
