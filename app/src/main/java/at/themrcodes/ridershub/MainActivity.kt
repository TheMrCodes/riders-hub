package at.themrcodes.ridershub

import android.Manifest
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
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
import at.themrcodes.ridershub.session.TripHistoryGroups
import at.themrcodes.ridershub.session.TripHistoryGroup
import at.themrcodes.ridershub.session.BoardSpeedBucketStatistics
import at.themrcodes.ridershub.api.ApiIntegration
import at.themrcodes.ridershub.api.ApiSnapshot
import at.themrcodes.ridershub.api.ApiAuthMode
import at.themrcodes.ridershub.api.OidcAuthConfig
import at.themrcodes.ridershub.api.apiUrls
import at.themrcodes.ridershub.api.oidcAuthorizationRequest
import at.themrcodes.ridershub.charger.ChargerControl
import at.themrcodes.ridershub.charger.ChargerPhase
import at.themrcodes.ridershub.charger.ChargerSnapshot
import at.themrcodes.ridershub.charger.ChargerStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration

class MainActivity : ComponentActivity() {
    private lateinit var presenceController: PresenceController
    private lateinit var state: AppStateStore
    private lateinit var rideStore: RideStore
    private lateinit var api: ApiIntegration
    private lateinit var authorizationService: AuthorizationService
    private lateinit var generalSettings: GeneralSettingsStore
    private lateinit var chargerStore: ChargerStore
    private var associationRequestedAfterPermission = false
    private var requestedAssociationRole = CompanionRole.BOARD_REMOTE
    private var associationsBeforeChooser = emptySet<Int>()
    private var handledAssociationId: Int? = null
    private var associationInProgress by mutableStateOf(false)
    private var expertControlsUnlocked by mutableStateOf(false)
    private var developerTapCount = 0
    private var dashboard by mutableStateOf<DashboardState?>(null)
    private var chargerPromptToken by mutableStateOf<String?>(null)
    private var pendingOidcBaseUrl: String? = null

    private val oidcAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val intent = result.data
        val response = intent?.let(AuthorizationResponse::fromIntent)
        val authorizationError = intent?.let(AuthorizationException::fromIntent)
        if (response == null) {
            state.setError(if (authorizationError != null) "SSO authorization failed" else "SSO sign-in was cancelled")
            pendingOidcBaseUrl = null
            dashboard = loadDashboard()
            return@registerForActivityResult
        }
        authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            runOnUiThread {
                val accessToken = tokenResponse?.accessToken
                val baseUrl = pendingOidcBaseUrl
                pendingOidcBaseUrl = null
                if (accessToken == null || baseUrl == null || tokenError != null) {
                    state.setError("SSO token exchange failed")
                } else {
                    runCatching { api.completeOidcEnrollment(baseUrl, accessToken) }
                        .onFailure { state.setError(it.message ?: "Could not enroll this device") }
                }
                dashboard = loadDashboard()
            }
        }
    }

    private val associationChooser = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        associationInProgress = false
        if (result.resultCode == RESULT_OK) {
            val association = presenceController.recoverAssociationAfterChooser(requestedAssociationRole, associationsBeforeChooser)
            if (association != null) onAssociationCreated(association, requestedAssociationRole)
            else state.setError("Android reported success but no companion association was available")
        } else {
            state.setError(
                if (requestedAssociationRole == CompanionRole.BOARD_REMOTE) {
                    "Remote association was cancelled"
                } else {
                    "Charger association was cancelled"
                },
            )
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

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            api.setGpsEnabled(true)
        } else {
            api.reportGpsUnavailable("Background location was not granted")
        }
        dashboard = loadDashboard()
    }

    private val preciseLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val precise = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (precise) backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else api.reportGpsUnavailable("Precise location was not granted")
        dashboard = loadDashboard()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        presenceController = PresenceController(this)
        state = AppStateStore(this)
        rideStore = RideStore.get(this)
        api = ApiIntegration.get(this)
        authorizationService = AuthorizationService(this)
        pendingOidcBaseUrl = savedInstanceState?.getString(KEY_PENDING_OIDC_BASE_URL)
        generalSettings = GeneralSettingsStore(this)
        chargerStore = ChargerStore(this)
        expertControlsUnlocked = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getBoolean(KEY_EXPERT_CONTROLS_UNLOCKED, false)
        dashboard = loadDashboard()
        ensureExistingAssociationIsObserved()
        requestNotificationPermissionIfNeeded()
        handleChargerPromptIntent(intent)

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
                    onAssociate = { requestAssociationWithPermissions(CompanionRole.BOARD_REMOTE) },
                    onAssociateCharger = { requestAssociationWithPermissions(CompanionRole.CHARGER_PLUG) },
                    onPromptCharger = { chargerPromptToken = chargerStore.snapshot().appearanceToken },
                    chargerPromptToken = chargerPromptToken,
                    onChargerDecision = ::requestChargerDecision,
                    onDismissChargerPrompt = { chargerPromptToken = null },
                    onOpenSettings = ::openPermissionSettings,
                    onVersionTap = ::onVersionTapped,
                    onSetChildLimiter = ::requestChildLimiter,
                    onSetLowBatteryWarningPercent = ::setLowBatteryWarningPercent,
                    onSetApiEnabled = api::setEnabled,
                    onConfigureApi = ::configureApi,
                    onStartOidc = ::startOidcEnrollment,
                    onDisconnectApi = api::clearConfiguration,
                    onRefreshApi = api::refreshCapabilities,
                    onSyncApi = api::manualSync,
                    onSetAnalytics = api::approveAnalytics,
                    onResyncArchives = api::resyncArchives,
                    onSetGps = ::setGpsEnabled,
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
            generalSettings = generalSettings.snapshot(),
            api = api.snapshot(),
            charger = chargerStore.snapshot(),
        )
    }

    private fun setLowBatteryWarningPercent(percent: Int) {
        generalSettings.setLowBatteryWarningPercent(percent)
        dashboard = loadDashboard()
    }

    private fun configureApi(baseUrl: String, bearerKey: String, confirmHttp: Boolean) {
        runCatching { api.configureAndTest(baseUrl, bearerKey, confirmHttp) }
            .onFailure { state.setError(it.message ?: "Could not save API configuration") }
        dashboard = loadDashboard()
    }

    private fun startOidcEnrollment(baseUrl: String) {
        runCatching {
            api.prepareOidcEnrollment(baseUrl) { outcome ->
                outcome.onSuccess { config -> discoverOidcAndAuthorize(baseUrl, config) }
                    .onFailure { state.setError("Could not load the API SSO configuration") }
                dashboard = loadDashboard()
            }
        }.onFailure { state.setError(it.message ?: "Could not start SSO enrollment") }
        dashboard = loadDashboard()
    }

    private fun discoverOidcAndAuthorize(baseUrl: String, config: OidcAuthConfig) {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { serviceConfig, error ->
            runOnUiThread {
                if (serviceConfig == null || error != null) {
                    state.setError("Could not discover the configured identity provider")
                    dashboard = loadDashboard()
                    return@runOnUiThread
                }
                val request = oidcAuthorizationRequest(serviceConfig, config)
                pendingOidcBaseUrl = apiUrls(baseUrl).base.toString().removeSuffix("/")
                oidcAuthorizationLauncher.launch(authorizationService.getAuthorizationRequestIntent(request))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingOidcBaseUrl?.let { outState.putString(KEY_PENDING_OIDC_BASE_URL, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        authorizationService.dispose()
        super.onDestroy()
    }

    private fun setGpsEnabled(enabled: Boolean) {
        if (!enabled) {
            api.setGpsEnabled(false)
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            preciseLocationLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
        } else if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            api.setGpsEnabled(true)
        }
        dashboard = loadDashboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleChargerPromptIntent(intent)
    }

    private fun handleChargerPromptIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(ChargerControl.EXTRA_OPEN_PROMPT, false) == true) {
            val supplied = intent.getStringExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN)
            chargerPromptToken = supplied?.takeIf { it == chargerStore.snapshot().appearanceToken }
            intent.removeExtra(ChargerControl.EXTRA_OPEN_PROMPT)
        }
    }

    private fun requestAssociationWithPermissions(role: CompanionRole) {
        requestedAssociationRole = role
        val missing = REQUIRED_BLUETOOTH_PERMISSIONS.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            associationRequestedAfterPermission = true
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
        } else {
            startAssociation(role)
        }
    }

    private fun startAssociation(role: CompanionRole = requestedAssociationRole) {
        state.clearError()
        associationInProgress = true
        requestedAssociationRole = role
        associationsBeforeChooser = presenceController.existingAssociations().map { it.associationId }.toSet()
        presenceController.startAssociation(
            role = role,
            launchChooser = ::launchAssociationChooser,
            onCreated = { onAssociationCreated(it, role) },
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

    private fun onAssociationCreated(association: BackfireAssociation, role: CompanionRole) {
        if (handledAssociationId == association.associationId) return
        handledAssociationId = association.associationId
        associationInProgress = false
        armAssociation(association, role)
        val label = association.displayName ?: if (role == CompanionRole.BOARD_REMOTE) "remote" else "Shelly charger"
        Toast.makeText(this, "Associated with $label", Toast.LENGTH_SHORT).show()
        dashboard = loadDashboard()
    }

    private fun armAssociation(association: BackfireAssociation, role: CompanionRole) {
        CompanionRoleStore(this).set(role, association.associationId)
        if (role == CompanionRole.BOARD_REMOTE) state.setAssociation(association.address, association.displayName, association.associationId)
        else chargerStore.setAssociation(association.associationId, association.displayName)
        presenceController.ensureObserving(association, role).onFailure { error ->
            state.setError("Could not watch ${if (role == CompanionRole.BOARD_REMOTE) "remote" else "charger"} presence: ${error.message}")
        }
    }

    private fun ensureExistingAssociationIsObserved() {
        CompanionRole.entries.forEach { role ->
            presenceController.association(role)?.let { armAssociation(it, role) }
        }
    }

    private fun requestChargerDecision(start: Boolean, token: String) {
        chargerPromptToken = null
        sendBroadcast(
            Intent(if (start) ChargerControl.ACTION_START else ChargerControl.ACTION_STOP)
                .setPackage(packageName)
                .putExtra(ChargerControl.EXTRA_APPEARANCE_TOKEN, token)
                .putExtra(ChargerControl.EXTRA_CONFIRMED, true),
        )
        dashboard = loadDashboard()
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
        private const val KEY_PENDING_OIDC_BASE_URL = "pending_oidc_base_url"
    }
}

private data class DashboardState(
    val app: AppSnapshot,
    val rides: RideStoreSnapshot,
    val generalSettings: GeneralSettingsSnapshot,
    val api: ApiSnapshot,
    val charger: ChargerSnapshot,
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
    onAssociateCharger: () -> Unit,
    onPromptCharger: () -> Unit,
    chargerPromptToken: String?,
    onChargerDecision: (Boolean, String) -> Unit,
    onDismissChargerPrompt: () -> Unit,
    onOpenSettings: () -> Unit,
    onVersionTap: () -> Unit,
    onSetChildLimiter: (Boolean) -> Unit,
    onSetLowBatteryWarningPercent: (Int) -> Unit,
    onSetApiEnabled: (Boolean) -> Unit,
    onConfigureApi: (String, String, Boolean) -> Unit,
    onStartOidc: (String) -> Unit,
    onDisconnectApi: () -> Unit,
    onRefreshApi: () -> Unit,
    onSyncApi: () -> Unit,
    onSetAnalytics: (Boolean) -> Unit,
    onResyncArchives: () -> Unit,
    onSetGps: (Boolean) -> Unit,
) {
    val app = dashboard.app
    val rides = dashboard.rides
    val today = LocalDate.now()
    val historyGroups = remember(rides.recentTracks, today) {
        TripHistoryGroups.build(rides.recentTracks, today)
    }
    var expandedHistoryGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var page by remember { mutableStateOf(DashboardPage.OVERVIEW) }
    var pendingLimiterChoice by remember { mutableStateOf<Boolean?>(null) }
    var pendingApiRemoval by remember { mutableStateOf(false) }
    fun leaveDevicePage() { page = DashboardPage.OVERVIEW }
    BackHandler(enabled = page == DashboardPage.DEVICE, onBack = ::leaveDevicePage)
    Surface(Modifier.fillMaxSize(), color = NothingBlack) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(if (page == DashboardPage.OVERVIEW) 24.dp else 48.dp),
        ) {
            when (page) {
                DashboardPage.OVERVIEW -> {
                    item {
                        Box(Modifier.padding(bottom = 24.dp)) {
                            NothingHeader(
                                app = app,
                                onOpenDevice = { page = DashboardPage.DEVICE },
                            )
                        }
                    }
                    app.error?.let { message -> item {
                        Box(Modifier.padding(bottom = 24.dp)) { ErrorWidget(message) }
                    } }
                    item {
                        Box(Modifier.padding(bottom = 24.dp)) {
                            CurrentTripWidget(
                                app = app,
                                ride = rides.currentTripDisplay,
                                lowBatteryWarningPercent = dashboard.generalSettings.lowBatteryWarningPercent,
                            )
                        }
                    }
                    item { RangeWidget(rides.rangeEstimate, Modifier.padding(top = 12.dp, bottom = 24.dp)) }
                    item { RidersProfileSection(rides.speedBucketStatistics, Modifier.padding(top = 12.dp, bottom = 24.dp)) }
                    item {
                        BatteryLongevitySection(
                            rides.batteryLongevity,
                            Modifier.padding(top = 12.dp, bottom = 24.dp),
                        )
                    }
                    item { RidesHeader(app.odometerKm, Modifier.padding(top = 12.dp, bottom = 24.dp)) }
                    if (rides.recentTracks.isEmpty()) {
                        item { EmptyRidesWidget() }
                    } else {
                        if (historyGroups.recentTrips.isNotEmpty()) {
                            item(key = "recent-trips-label") {
                                NothingText("Last 7 days", color = NothingMuted, size = 12)
                            }
                            items(historyGroups.recentTrips, key = { it.id }) { ride -> RideWidget(ride) }
                        }
                        historyGroups.olderGroups.forEach { group ->
                            val expanded = group.id in expandedHistoryGroups
                            item(key = group.id) {
                                TripHistoryGroupHeader(group, expanded) {
                                    expandedHistoryGroups = if (expanded) expandedHistoryGroups - group.id
                                        else expandedHistoryGroups + group.id
                                }
                            }
                            if (expanded) {
                                items(group.trips, key = { it.id }) { ride -> RideWidget(ride) }
                            }
                        }
                    }
                }

                DashboardPage.DEVICE -> {
                    item { DeviceHeader(onBack = ::leaveDevicePage) }
                    app.error?.let { message -> item { ErrorWidget(message) } }
                    item {
                        RemoteSection(
                            app = app,
                            associationInProgress = associationInProgress,
                            onAssociate = onAssociate,
                        )
                    }
                    item {
                        ChargerSection(
                            charger = dashboard.charger,
                            associationInProgress = associationInProgress,
                            onAssociate = onAssociateCharger,
                            onPrompt = onPromptCharger,
                        )
                    }
                    item {
                        GeneralSettingsSection(
                            lowBatteryWarningPercent = dashboard.generalSettings.lowBatteryWarningPercent,
                            onSetLowBatteryWarningPercent = onSetLowBatteryWarningPercent,
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
                        SelfHostedApiSection(
                            state = dashboard.api,
                            onSetEnabled = onSetApiEnabled,
                            onConfigure = onConfigureApi,
                            onStartOidc = onStartOidc,
                            onRequestRemoval = { pendingApiRemoval = true },
                            onRefresh = onRefreshApi,
                            onSync = onSyncApi,
                            onSetAnalytics = onSetAnalytics,
                            onResyncArchives = onResyncArchives,
                            onSetGps = onSetGps,
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
    if (pendingApiRemoval) {
        ApiRemovalDialog(
            onConfirm = {
                pendingApiRemoval = false
                onDisconnectApi()
            },
            onDismiss = { pendingApiRemoval = false },
        )
    }
    chargerPromptToken?.let { token ->
        ChargerConfirmationDialog(
            charger = dashboard.charger,
            onStart = { onChargerDecision(true, token) },
            onStop = { onChargerDecision(false, token) },
            onDismiss = onDismissChargerPrompt,
        )
    }
}

private enum class DashboardPage { OVERVIEW, DEVICE }

@Composable
private fun NothingHeader(
    app: AppSnapshot,
    onOpenDevice: () -> Unit,
) {
    val listening = app.serviceActive && app.connection.contains("Listening", ignoreCase = true)
    val stateLabel = if (listening) "Live" else if (app.serviceActive && app.present) "Nearby" else "Standby"
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            DotMatrixText("RIDERS HUB", Modifier.fillMaxWidth(0.78f).height(44.dp))
            DeviceIconButton(onClick = onOpenDevice, modifier = Modifier.align(Alignment.CenterEnd))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            NothingText(
                app.name?.let { "$it · $TESTED_BOARD_MODEL" } ?: "No remote",
                color = NothingMuted,
                size = 11,
                spacing = 0.5f,
            )
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
private fun CurrentTripWidget(
    app: AppSnapshot,
    ride: RideSummary?,
    lowBatteryWarningPercent: Int,
) {
    val listening = app.serviceActive && app.connection.contains("Listening", ignoreCase = true)
    val rideOpen = ride?.active == true
    val telemetryActive = rideOpen && listening
    val connectionDetail = app.connection.lowercase(Locale.ROOT)
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
    val estimatedBattery = ride?.estimatedBatteryPercent
    Column {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(end = 78.dp)) {
                    SectionTitle(title, connectionStatus, titleColor = valueColor)
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    NothingText(
                        ride?.let { UiFormat.decimal(it.distanceKm, 2) } ?: "--.--",
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
                        ride?.let { UiFormat.duration(it.movingSeconds) } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        "Top speed",
                        ride?.let { "${UiFormat.decimal(it.maxSpeedKmh, 1)} km/h" } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        "Pack",
                        packVoltage?.let { "${UiFormat.decimal(it, 1)} V" } ?: "—",
                        valueColor = valueColor,
                    )
                    QuietMetric(
                        if (estimatedBattery != null) "Est. battery" else "Battery",
                        estimatedBattery?.let { "${UiFormat.decimal(it, 1)}%" }
                            ?: batteryPercent?.let { "$it%" } ?: "—",
                        Modifier.width(56.dp),
                        Alignment.CenterHorizontally,
                        valueColor,
                    )
                }
            }
            BatteryMeter(
                percent = batteryPercent,
                estimatedPercent = estimatedBattery,
                active = telemetryActive,
                lowBatteryWarningPercent = lowBatteryWarningPercent,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        Spacer(Modifier.height(20.dp))
        SpeedScale(app.speedKmh)
        if (isLowBoardBattery(app.boardBatteryPercent, lowBatteryWarningPercent)) {
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
        RangeEstimateStatus.COLLECTING_DATA -> "Building a 5% battery-use window"
        RangeEstimateStatus.PROVISIONAL -> "Based on your recent riding pattern"
        RangeEstimateStatus.CALIBRATED -> if (estimate.usesAccumulatedBucketConsumption)
            "Calibrated to your board's riding profile" else "Calibrated to your latest 100 km"
    }
    Column(modifier) {
        SectionTitle("Range", subtitle)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                NothingText(estimate.remainingKm?.let { UiFormat.decimal(it, 1, minimumWidth = 4) } ?: "--.-", size = 58, weight = FontWeight.Black, lineHeight = 60)
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
        NothingText(estimate.message, color = NothingMuted, size = 11, lineHeight = 17)
    }
}

@Composable
private fun RidersProfileSection(statistics: BoardSpeedBucketStatistics?, modifier: Modifier = Modifier) {
    var energyUse by rememberSaveable { mutableStateOf(false) }
    val buckets = statistics?.buckets.orEmpty().filter {
        it.measuredDistanceKm > 0 || it.inferredDistanceKm > 0 ||
            it.calibrationMeasuredKm > 0 || it.calibrationInferredKm > 0
    }.sortedBy { it.startKmh }
    val values = buckets.map {
        (if (energyUse) it.batteryPercentPer100Km?.div(100.0).takeUnless { _ -> it.modelLimitReached } else it.distanceSharePercent)
            ?.takeIf { value -> value.isFinite() && value >= 0 }
    }
    val maximum = values.filterNotNull().maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
    Column(modifier.fillMaxWidth()) {
        NothingText("Riders Profile", size = 22, weight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        NothingText("Your riding mix and battery use by speed", color = NothingMuted, size = 11, lineHeight = 17)
        Spacer(Modifier.height(12.dp))
        NothingText(if (energyUse) "Energy use · % / km" else "Distance %", color = NothingMuted, size = 9)
        Spacer(Modifier.height(8.dp))
        if (buckets.isEmpty()) {
            NothingText("Collecting ride data", color = NothingMuted, size = 10)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                buckets.forEachIndexed { index, bucket ->
                    val value = values[index]
                    val supported = !energyUse || bucket.hasUsableConsumptionSupport
                    val color = if (supported) NothingWhite else NothingLine
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = "${bucket.startKmh} to ${bucket.startKmh + 5} km/h, " +
                            (value?.let { UiFormat.decimal(it, if (energyUse) 2 else 1) } ?: "no estimate") +
                            (if (energyUse) " battery percentage points per kilometre" else " percent of distance") +
                            if (supported) "" else ", insufficient support"
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        NothingText(value?.let { UiFormat.decimal(it, if (energyUse) 2 else 0) } ?: "—",
                            color = if (supported) NothingWhite else NothingMuted, size = 8)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.BottomCenter) {
                            if (value != null && value > 0) {
                                Box(Modifier.fillMaxWidth().height((100 * value / maximum).toFloat().dp)
                                    .background(color))
                            } else if (energyUse) {
                                Box(Modifier.fillMaxWidth().height(3.dp).background(NothingLine))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        NothingText("${bucket.startKmh}–${bucket.startKmh + 5}", color = NothingMuted, size = 8)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                NothingText(if (energyUse) "Gray = low support · — = unavailable" else "", color = NothingMuted, size = 8)
                NothingText("km/h", color = NothingMuted, size = 8)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            listOf(false to "Distribution", true to "Energy use").forEach { (mode, label) ->
                val selected = energyUse == mode
                Column(Modifier.weight(1f)
                    .selectable(selected = selected, role = Role.Tab, onClick = { energyUse = mode }),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background(if (selected) NothingRed else NothingLine))
                    Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                        NothingText(label, color = if (selected) NothingWhite else NothingMuted,
                            size = 11, weight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryLongevitySection(
    longevity: BatteryLongevitySnapshot,
    modifier: Modifier = Modifier,
) {
    var granularity by remember { mutableStateOf(LongevityGranularity.DAY) }
    var anchor by remember { mutableStateOf<java.time.Instant?>(null) }
    val bars = BatteryLongevityChart.aggregate(
        observations = longevity.observations,
        granularity = granularity,
        zoneId = ZoneId.systemDefault(),
        anchor = anchor,
    )
    val selected = bars.lastOrNull()
    val collecting = longevity.status == BatteryLongevityStatus.COLLECTING_DATA
    val chartMaximumKm = longevity.allTimeHighKm ?: bars.maxOfOrNull { it.fullRangeKm } ?: 1.0

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
                    longevity.currentFullRangeKm?.let { UiFormat.decimal(it, 1, minimumWidth = 4) } ?: "--.-",
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
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuietMetric(
                    label = "Observed charges",
                    value = longevity.observedCycleCount.toString(),
                    horizontalAlignment = Alignment.End,
                )
                QuietMetric(
                    label = "Est. total charges",
                    value = longevity.cycleAge?.let { UiFormat.decimal(it.estimatedLifetimeFullCycles, 0) } ?: "—",
                    horizontalAlignment = Alignment.End,
                    valueColor = if (longevity.observedCycleCount >= 10) NothingWhite else NothingMuted,
                )
            }
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
                    "0 – ${UiFormat.decimal(chartMaximumKm, 1)} km",
                    color = NothingMuted,
                    size = 9,
                )
            }
            Spacer(Modifier.height(10.dp))
            BatteryLongevityChartView(
                bars = bars,
                allTimeHighKm = chartMaximumKm,
                selectedBarId = selected?.id,
                onBarSelected = { bar ->
                    val finer = granularity.finer()
                    anchor = bar.latestObservationAt
                    if (finer != null) granularity = finer
                },
                onZoomOut = {
                    granularity.coarser()?.let { coarser ->
                        granularity = coarser
                        if (anchor == null) anchor = selected?.latestObservationAt
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            NothingText(
                selected?.let {
                    "${it.label} · ${UiFormat.decimal(it.fullRangeKm, 1)} km from ${it.observationCount} observation${if (it.observationCount == 1) "" else "s"}"
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
                        val slotWidth = size.width / BatteryLongevityChart.DEFAULT_MAX_BARS
                        val index = (offset.x / slotWidth).toInt() - (BatteryLongevityChart.DEFAULT_MAX_BARS - bars.size)
                        bars.getOrNull(index)?.let { currentOnBarSelected(it) }
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
                    contentDescription = "Battery capacity bar chart from zero to ${UiFormat.decimal(scaleMax, 1)} kilometres"
                },
        ) {
            val baseline = size.height - 3.dp.toPx()
            drawLine(
                color = NothingLine,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.dp.toPx(),
            )
            val slotWidth = size.width / BatteryLongevityChart.DEFAULT_MAX_BARS
            bars.forEachIndexed { index, bar ->
                val height = (baseline * (bar.fullRangeKm / scaleMax).coerceIn(0.0, 1.0)).toFloat()
                val width = slotWidth * 0.56f
                val left = (index + BatteryLongevityChart.DEFAULT_MAX_BARS - bars.size) * slotWidth + (slotWidth - width) / 2f
                val color = if (bar.id == selectedBarId) {
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
            repeat(BatteryLongevityChart.DEFAULT_MAX_BARS - bars.size) { Spacer(Modifier.weight(1f)) }
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
                odometerKm?.let { UiFormat.decimal(it, 1) } ?: "----.-",
                size = 58,
                weight = FontWeight.Black,
                lineHeight = 60,
            )
            NothingText("km", Modifier.padding(bottom = 10.dp), NothingMuted, 11, FontWeight.Bold, 0.5f)
        }
    }
}

@Composable
private fun TripHistoryGroupHeader(group: TripHistoryGroup, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth()
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
        .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse trips" else "Expand trips", onClick = onToggle)
        .padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                NothingText(group.label, size = 18, weight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                NothingText("${group.trips.size} trips · ${UiFormat.decimal(group.trips.sumOf { it.distanceKm }, 1)} km",
                    color = NothingMuted, size = 11)
            }
            NothingText(if (expanded) "−" else "+", size = 26, weight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = NothingLine)
    }
}

@Composable
private fun RideWidget(ride: RideSummary) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                NothingText(UiFormat.rideDate(ride.startedAt), color = NothingMuted, size = 10)
                Spacer(Modifier.height(8.dp))
                NothingText(ride.modes.joinToString(" · ").ifEmpty { "Ride" }, size = 16, weight = FontWeight.Bold)
            }
            NothingText("${UiFormat.decimal(ride.distanceKm, 2)} km", size = 24, weight = FontWeight.Black)
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QuietMetric("Moving", UiFormat.duration(ride.movingSeconds))
            QuietMetric("Top speed", "${UiFormat.decimal(ride.maxSpeedKmh, 1)} km/h")
            QuietMetric("Battery", batteryDelta(ride))
        }
        Spacer(Modifier.height(14.dp))
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
    val connectionDetail = app.connection.lowercase(Locale.ROOT)
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
private fun ChargerSection(
    charger: ChargerSnapshot,
    associationInProgress: Boolean,
    onAssociate: () -> Unit,
    onPrompt: () -> Unit,
) {
    val selectedDevice = charger.associatedDeviceName ?: when {
        charger.paired -> "Shelly charger (name unavailable)"
        else -> "No charger selected"
    }
    Column {
        SectionTitle("Charger", "Shelly Plus Plug S Gen2 observation")
        Spacer(Modifier.height(30.dp))
        NothingText("SELECTED PLUG", color = NothingMuted, size = 9, weight = FontWeight.Bold, spacing = 1f)
        Spacer(Modifier.height(8.dp))
        NothingText(selectedDevice, size = 20, weight = FontWeight.Black, lineHeight = 26)
        charger.associationId?.let { associationId ->
            Spacer(Modifier.height(6.dp))
            NothingText("Android association #$associationId", color = NothingMuted, size = 10)
        }
        Spacer(Modifier.height(10.dp))
        NothingText(
            if (charger.paired) {
                "This is the exact advertised name selected in Android's chooser. Association alone does not mean it is connected."
            } else {
                "Choose the plug once; its advertised name will remain visible here."
            },
            color = NothingMuted,
            size = 10,
            lineHeight = 16,
        )
        Spacer(Modifier.height(18.dp))
        NothingText(
            when {
                !charger.paired -> "No charger associated"
                charger.rpcVerified -> charger.status
                charger.gattConnected -> "BLE connected; verifying Shelly RPC"
                charger.nearby -> "Plug detected; opening BLE link"
                else -> "Waiting for Android to detect the associated plug"
            },
            size = 14,
            weight = FontWeight.Bold,
            lineHeight = 20,
        )
        charger.error?.let {
            Spacer(Modifier.height(10.dp))
            NothingText(it, color = NothingRed, size = 10, lineHeight = 15)
        }
        if (charger.rpcVerified) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QuietMetric("Output", when (charger.outputOn) { true -> "ON"; false -> "OFF"; null -> "—" })
                QuietMetric("Power", charger.powerWatts?.let { "${UiFormat.decimal(it, 1)} W" } ?: "—")
                QuietMetric("Peak", charger.peakWatts?.let { "${UiFormat.decimal(it, 1)} W" } ?: "—")
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onPrompt,
                enabled = charger.appearanceToken != null && (charger.canPrompt || charger.canStop),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (charger.outputOn == true) NothingRed else NothingWhite),
            ) {
                NothingLabel(if (charger.outputOn == true) "REVIEW / STOP" else "START CHARGING", if (charger.outputOn == true) Color.White else NothingBlack)
            }
        }
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onAssociate,
            enabled = !associationInProgress,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NothingRaised, contentColor = NothingWhite),
        ) {
            NothingLabel(if (charger.paired) "PAIR DIFFERENT CHARGER" else "PAIR SHELLY CHARGER", NothingWhite)
        }
        Spacer(Modifier.height(12.dp))
        NothingText(
            "Power is never enabled by presence or notification alone. START requires confirmation and uses Shelly's 12-hour automatic-off fail-safe.",
            color = NothingMuted,
            size = 9,
            lineHeight = 15,
        )
    }
}

@Composable
private fun ChargerConfirmationDialog(
    charger: ChargerSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val outputOn = charger.outputOn == true
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (outputOn) "Stop board charging?" else "Start board charging?") },
        text = {
            Text(
                if (outputOn) {
                    "This sends an OFF command and verifies the observed relay state."
                } else {
                    "Confirm the longboard charger is safely connected. The plug will turn on with a 12-hour fail-safe, stop if no active load appears within 5 minutes, and stop after cautious completion evidence."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = if (outputOn) onStop else onStart) {
                Text(if (outputOn) "STOP" else "START CHARGING")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GeneralSettingsSection(
    lowBatteryWarningPercent: Int,
    onSetLowBatteryWarningPercent: (Int) -> Unit,
) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = NothingWhite,
        activeTrackColor = NothingRed,
        inactiveTrackColor = NothingRaised,
        activeTickColor = NothingWhite,
        inactiveTickColor = NothingLine,
    )
    val sliderInteractionSource = remember { MutableInteractionSource() }

    Column {
        SectionTitle("General", "App-wide adjustments")
        Spacer(Modifier.height(30.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                NothingText("Low battery warning", size = 16, weight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                NothingText(
                    "Show the warning and send one notification per ride at or below this level.",
                    color = NothingMuted,
                    size = 10,
                    lineHeight = 16,
                )
            }
            Spacer(Modifier.width(20.dp))
            NothingText(
                "$lowBatteryWarningPercent%",
                color = NothingRed,
                size = 24,
                weight = FontWeight.Black,
            )
        }
        Spacer(Modifier.height(18.dp))
        Slider(
            value = lowBatteryWarningPercent.toFloat(),
            onValueChange = { value ->
                val percent = normalizeLowBatteryWarningPercent(value.roundToInt())
                if (percent != lowBatteryWarningPercent) onSetLowBatteryWarningPercent(percent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Low battery warning threshold, $lowBatteryWarningPercent percent"
                },
            valueRange = MIN_LOW_BATTERY_WARNING_PERCENT.toFloat()..
                MAX_LOW_BATTERY_WARNING_PERCENT.toFloat(),
            steps = (MAX_LOW_BATTERY_WARNING_PERCENT - MIN_LOW_BATTERY_WARNING_PERCENT) /
                LOW_BATTERY_WARNING_STEP_PERCENT - 1,
            colors = sliderColors,
            interactionSource = sliderInteractionSource,
            thumb = {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(24.dp)
                        .background(NothingWhite, RoundedCornerShape(3.dp)),
                )
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NothingText("$MIN_LOW_BATTERY_WARNING_PERCENT%", color = NothingMuted, size = 9)
            NothingText("$MAX_LOW_BATTERY_WARNING_PERCENT%", color = NothingMuted, size = 9)
        }
    }
}

@Composable
private fun SelfHostedApiSection(
    state: ApiSnapshot,
    onSetEnabled: (Boolean) -> Unit,
    onConfigure: (String, String, Boolean) -> Unit,
    onStartOidc: (String) -> Unit,
    onRequestRemoval: () -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onSetAnalytics: (Boolean) -> Unit,
    onResyncArchives: () -> Unit,
    onSetGps: (Boolean) -> Unit,
) {
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl.orEmpty()) }
    var bearerKey by remember { mutableStateOf("") }
    var confirmHttp by remember(state.httpConfirmed) { mutableStateOf(state.httpConfirmed) }
    val parsed = runCatching { apiUrls(baseUrl) }.getOrNull()
    val insecureDraft = parsed?.base?.isHttps == false
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionTitle("Self-hosted API", "Metrics, realtime and raw analytics")
            }
            Spacer(Modifier.width(18.dp))
            Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
        }
        if (!state.enabled) return@Column
        Spacer(Modifier.height(22.dp))
        if (!state.configured) {
            ApiTextField(baseUrl, { baseUrl = it; confirmHttp = false }, "API base URL",
                "https://api.example.test/prefix", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onStartOidc(baseUrl) },
                enabled = parsed?.base?.isHttps == true,
                colors = ButtonDefaults.buttonColors(containerColor = NothingRed),
            ) { NothingLabel("SIGN IN WITH SSO", Color.White) }
            NothingText(
                "Uses your server's OIDC provider. The app receives a device credential after browser sign-in.",
                color = NothingMuted,
                size = 9,
                lineHeight = 14,
            )
            Spacer(Modifier.height(20.dp))
            NothingText("Legacy bearer key", size = 11, weight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            ApiTextField(bearerKey, { bearerKey = it }, "Bearer key", "Key",
                keyboardType = KeyboardType.Password, password = true)
            if (insecureDraft) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    NothingText("I understand HTTP exposes the key and ride data", color = NothingRed, size = 9,
                        lineHeight = 14, modifier = Modifier.weight(1f))
                    Switch(checked = confirmHttp, onCheckedChange = { confirmHttp = it })
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onConfigure(baseUrl, bearerKey, confirmHttp) },
                enabled = parsed != null && bearerKey.isNotBlank() && (!insecureDraft || confirmHttp),
                colors = ButtonDefaults.buttonColors(containerColor = NothingRed),
            ) { NothingLabel("SAVE & TEST", Color.White) }
        } else {
            if (state.insecure) {
                NothingText("UNENCRYPTED CONNECTION — bearer key and data travel in plaintext",
                    color = NothingRed, size = 10, weight = FontWeight.Bold, lineHeight = 15)
                Spacer(Modifier.height(12.dp))
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    NothingText(state.baseUrl.orEmpty(), size = 10, lineHeight = 15)
                    NothingText(
                        if (state.authMode == ApiAuthMode.OIDC_DEVICE) "SSO-enrolled device" else "Legacy bearer key",
                        color = NothingMuted,
                        size = 9,
                    )
                    state.serverId?.let { NothingText("Server ${it.take(18)}", color = NothingMuted, size = 9) }
                }
                ApiIconButton("Remove API configuration", true, onRequestRemoval) { TrashIcon() }
            }
            Spacer(Modifier.height(18.dp))
            val caps = state.capabilities
            NothingText(
                listOfNotNull(
                    caps?.metrics?.let { "METRICS v${it.schemaVersion}" },
                    caps?.realtime?.let { "REALTIME v${it.schemaVersion}" },
                    caps?.analytics?.let { "ANALYTICS v${it.schemaVersion}" },
                    caps?.realtime?.takeIf { it.gps }?.let { "GPS" },
                ).ifEmpty { listOf("NO VALID CAPABILITIES") }.joinToString("  ·  "),
                color = NothingMuted, size = 9, weight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            ApiStatusRow("Metrics", state.metricsStatus)
            ApiStatusRow("Realtime", state.realtimeStatus)
            ApiStatusRow("Ride archives", state.analyticsStatus)
            ApiStatusRow("Charger observations", state.chargerAnalyticsStatus)
            ApiStatusRow("GPS", state.gpsStatus)
            state.lastError?.let {
                Spacer(Modifier.height(8.dp)); NothingText(it, color = NothingRed, size = 9, lineHeight = 14)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(10.dp)) {
                Button(onClick = onRefresh, modifier = Modifier.weight(1f)) { NothingLabel("REFRESH", Color.White) }
                Button(onClick = onSync, enabled = caps?.metrics != null, modifier = Modifier.weight(1f)) {
                    NothingLabel("SYNC NOW", Color.White)
                }
            }
            if (caps?.analytics != null) {
                Spacer(Modifier.height(20.dp))
                NothingText("Raw analytics consent", size = 12, weight = FontWeight.Bold)
                NothingText(
                    "Ride archives can contain device identifiers, exact activity times, raw frames and precise GPS. Charger observation files contain generated IDs, times, relay state and allowlisted power evidence. Approval is scoped to this URL and authenticated server identity.",
                    color = NothingMuted, size = 9, lineHeight = 14,
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    NothingText(if (state.analyticsApproved) "Archive uploads enabled" else "Archive uploads disabled", size = 10)
                    Switch(checked = state.analyticsApproved, onCheckedChange = onSetAnalytics)
                }
                if (state.analyticsApproved) TextButton(onClick = onResyncArchives) {
                    NothingLabel("RESYNC ALL ARCHIVES", NothingRed)
                }
            }
            if (caps?.realtime?.gps == true) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NothingText("Live GPS", size = 12, weight = FontWeight.Bold)
                        NothingText("Requires precise and always-allowed location", color = NothingMuted, size = 9)
                    }
                    Switch(checked = state.gpsEnabled, onCheckedChange = onSetGps)
                }
            }
        }
    }
}

@Composable
private fun ApiStatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
        NothingText(label, color = NothingMuted, size = 10)
        NothingText(value, size = 10, lineHeight = 15)
    }
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun ApiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    enabled: Boolean = true,
    keyboardType: KeyboardType,
    password: Boolean = false,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { NothingText(label, color = NothingMuted, size = 10) },
        placeholder = { NothingText(placeholder, color = NothingMuted, size = 10) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        isError = isError,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun ApiIconButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = label },
    ) {
        icon()
    }
}

@Composable
private fun TrashIcon() {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.7.dp.toPx()
        drawLine(NothingWhite, Offset(size.width * 0.25f, size.height * 0.28f), Offset(size.width * 0.75f, size.height * 0.28f), stroke, StrokeCap.Round)
        drawLine(NothingWhite, Offset(size.width * 0.42f, size.height * 0.17f), Offset(size.width * 0.58f, size.height * 0.17f), stroke, StrokeCap.Round)
        drawRoundRect(
            color = NothingWhite,
            topLeft = Offset(size.width * 0.31f, size.height * 0.35f),
            size = Size(size.width * 0.38f, size.height * 0.48f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
    }
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
private fun BatteryMeter(
    percent: Int?,
    estimatedPercent: Double? = null,
    active: Boolean,
    lowBatteryWarningPercent: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = ((estimatedPercent ?: percent?.toDouble() ?: 0.0).coerceIn(0.0, 100.0) / 100.0).toFloat()
    val markerColor = when {
        !active -> NothingInactive
        isLowBoardBattery(percent, lowBatteryWarningPercent) -> NothingRed
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
private fun ApiRemovalDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NothingPanel,
        shape = RoundedCornerShape(22.dp),
        title = { NothingText("Remove API configuration?", size = 20, weight = FontWeight.Black) },
        text = {
            NothingText(
                "This disables delivery and forgets the protected base URL and bearer key. " +
                    "Destination-scoped delivery acknowledgements are retained if you reconnect later.",
                color = NothingMuted,
                size = 11,
                lineHeight = 18,
                spacing = 0.4f,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { NothingLabel("REMOVE", NothingRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { NothingLabel("CANCEL", NothingWhite) }
        },
    )
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
        val glyphs = text.uppercase(Locale.ROOT).map { DOT_GLYPHS[it] ?: DOT_GLYPHS.getValue(' ') }
        val totalColumns = glyphs.sumOf { it.first().length } + (glyphs.size - 1)
        val step = minOf(size.width / totalColumns.coerceAtLeast(1), size.height / 7f)
        var startX = 0f
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

private const val TESTED_BOARD_MODEL = "G3"

private fun batteryDelta(ride: RideSummary): String {
    val start = ride.boardBatteryStart ?: return "—"
    val end = ride.boardBatteryEnd ?: return "$start%"
    return "$start → $end%"
}
