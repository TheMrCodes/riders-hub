package at.themrcodes.ridershub

import android.Manifest
import at.themrcodes.ridershub.storage.StorageModeStore
import at.themrcodes.ridershub.storage.StorageState
import at.themrcodes.ridershub.storage.StorageNotifications
import at.themrcodes.ridershub.storage.StorageProgressCard
import at.themrcodes.ridershub.session.pseudonymousBoardId
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.DocumentsContract
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import at.themrcodes.ridershub.log.TelemetryArchive
import java.io.File
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
import androidx.compose.ui.layout.Layout
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
import at.themrcodes.ridershub.session.ArchiveReplayState
import at.themrcodes.ridershub.session.RideStoreSnapshot
import at.themrcodes.ridershub.session.RideSummary
import at.themrcodes.ridershub.session.TripHistoryGroups
import at.themrcodes.ridershub.session.TripHistoryGroup
import at.themrcodes.ridershub.session.BoardSpeedBucketStatistics
import at.themrcodes.ridershub.session.BoardModelCatalog
import at.themrcodes.ridershub.session.RiderBoardProfile
import at.themrcodes.ridershub.api.ApiIntegration
import at.themrcodes.ridershub.api.ApiHttpException
import at.themrcodes.ridershub.api.ApiSnapshot
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
    private var storageOpenToken by mutableStateOf(0)
    private var pendingOidcBaseUrl: String? = null
    private var oidcSignInPending by mutableStateOf(false)
    private var oidcSignInError by mutableStateOf<String?>(null)

    private var archiveExportBusy by mutableStateOf(false)
    private var archiveExportMessage by mutableStateOf<String?>(null)
    private val archiveExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            archiveExportBusy = false
        } else {
            lifecycleScope.launch {
                val appContext = applicationContext
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val telemetry = appContext.getExternalFilesDir("telemetry") ?: File(appContext.filesDir, "telemetry")
                        val output = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                        output.use { TelemetryArchive().exportZip(File(telemetry, "archive"), appContext.cacheDir, it) }
                    }.onFailure {
                        runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
                    }
                }
                archiveExportBusy = false
                archiveExportMessage = result.fold(
                    onSuccess = { "Saved $it archive files in one ZIP." },
                    onFailure = { "Export failed. Check that local archives exist and the destination has enough space, then retry." },
                )
            }
        }
    }

    private val oidcAuthorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val intent = result.data
        val response = intent?.let(AuthorizationResponse::fromIntent)
        val authorizationError = intent?.let(AuthorizationException::fromIntent)
        if (response == null) {
            reportOidcSignInError(if (authorizationError != null) "SSO authorization failed" else "SSO sign-in was cancelled")
            pendingOidcBaseUrl = null
            return@registerForActivityResult
        }
        authorizationService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
            runOnUiThread {
                val accessToken = tokenResponse?.accessToken
                val baseUrl = pendingOidcBaseUrl
                pendingOidcBaseUrl = null
                if (accessToken == null || baseUrl == null || tokenError != null) {
                    reportOidcSignInError("SSO token exchange failed")
                } else {
                    runCatching {
                        api.completeOidcEnrollment(baseUrl, accessToken) { outcome, step ->
                            outcome.onSuccess { oidcSignInPending = false }
                                .onFailure { error ->
                                    val detail = if (error is ApiHttpException) {
                                        " (HTTP ${error.statusCode})" + (error.publicError?.let { ": $it" } ?: "")
                                    } else ""
                                    reportOidcSignInError("$step failed$detail; check the server configuration")
                                }
                            dashboard = loadDashboard()
                        }
                    }.onFailure { reportOidcSignInError("Could not enroll this device") }
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
                    storageOpenToken = storageOpenToken,
                    onStorageAnswer = { enable ->
                        StorageModeStore.get(this).answer(enable)
                        dashboard = loadDashboard()
                    },
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
                    onSetRiderBoardProfile = ::setRiderBoardProfile,
                    onSetApiEnabled = api::setEnabled,
                    onStartOidc = ::startOidcEnrollment,
                    onClearOidcError = { oidcSignInError = null },
                    oidcSignInPending = oidcSignInPending,
                    oidcSignInError = oidcSignInError,
                    onDisconnectApi = api::clearConfiguration,
                    onRefreshApi = api::refreshCapabilities,
                    onSyncApi = api::manualSync,
                    onSetAnalytics = api::approveAnalytics,
                    onResyncArchives = api::resyncArchives,
                    onSetGps = ::setGpsEnabled,
                    onSetMotion = api::setMotionEnabled,
                    archiveExportBusy = archiveExportBusy,
                    archiveExportMessage = archiveExportMessage,
                    onExportArchive = {
                        archiveExportBusy = true
                        archiveExportMessage = null
                        archiveExportLauncher.launch("ridershub-archive.zip")
                    },
                    onReplayArchive = {
                        rideStore.replayCurrentArchive()
                        dashboard = loadDashboard()
                    },
                )
            }
        }
    }

    private fun loadDashboard(): DashboardState {
        val app = state.snapshot()
        val restingVoltage = app.packVoltageV
            ?.takeIf { (app.speedKmh ?: Float.MAX_VALUE) <= 0.5f }
            ?.toDouble()
        val rides = rideStore.snapshot(app.boardBatteryPercent, restingVoltage)
        val storage = StorageModeStore.get(this)
        app.address?.let { address ->
            val board = pseudonymousBoardId(address)
            if (storage.snapshot().boardId != board) {
                val lastRideAt = (rides.recentTracks + listOfNotNull(rides.activeRide, rides.lastCompletedRide))
                    .filter { it.localBoardId == board && it.distanceKm > 0.1 }
                    .mapNotNull { ride -> runCatching { Instant.parse(ride.lastFrameAt ?: ride.endedAt ?: ride.startedAt).toEpochMilli() }.getOrNull() }
                    .maxOrNull() ?: System.currentTimeMillis()
                storage.selectBoard(board, lastRideAt)
            }
        }
        return DashboardState(
            app = app,
            rides = rides,
            storage = storage.snapshot(),
            storageNotificationsAvailable = StorageNotifications.available(this),
            archiveReplay = rideStore.archiveReplayState(),
            generalSettings = generalSettings.snapshot(),
            api = api.snapshot(),
            charger = chargerStore.snapshot(),
        )
    }

    private fun setLowBatteryWarningPercent(percent: Int) {
        generalSettings.setLowBatteryWarningPercent(percent)
        dashboard = loadDashboard()
    }

    private fun setRiderBoardProfile(profile: RiderBoardProfile) {
        generalSettings.setRiderBoardProfile(profile)
        dashboard = loadDashboard()
    }

    private fun startOidcEnrollment(baseUrl: String) {
        oidcSignInPending = true
        oidcSignInError = null
        runCatching {
            api.prepareOidcEnrollment(baseUrl) { outcome ->
                outcome.onSuccess { config -> discoverOidcAndAuthorize(baseUrl, config) }
                    .onFailure { error ->
                        val detail = if (error is ApiHttpException) " (HTTP ${error.statusCode})" else ""
                        reportOidcSignInError("Could not load SSO settings from this API$detail. Check the URL and server availability.")
                    }
                dashboard = loadDashboard()
            }
        }.onFailure { reportOidcSignInError(it.message ?: "Could not start SSO sign-in") }
        dashboard = loadDashboard()
    }

    private fun reportOidcSignInError(message: String) {
        pendingOidcBaseUrl = null
        oidcSignInPending = false
        oidcSignInError = message
        state.setError(message)
        dashboard = loadDashboard()
    }

    private fun discoverOidcAndAuthorize(baseUrl: String, config: OidcAuthConfig) {
        AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.issuer)) { serviceConfig, error ->
            runOnUiThread {
                if (serviceConfig == null || error != null) {
                    reportOidcSignInError("Could not discover the configured identity provider")
                    return@runOnUiThread
                }
                runCatching {
                    val request = oidcAuthorizationRequest(serviceConfig, config)
                    pendingOidcBaseUrl = apiUrls(baseUrl).base.toString().removeSuffix("/")
                    oidcAuthorizationLauncher.launch(authorizationService.getAuthorizationRequestIntent(request))
                }.onFailure { reportOidcSignInError("Could not open SSO sign-in in a browser") }
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
        if (intent?.getBooleanExtra("open_storage", false) == true) {
            storageOpenToken++
            intent.removeExtra("open_storage")
        }
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
    val archiveReplay: ArchiveReplayState,
    val generalSettings: GeneralSettingsSnapshot,
    val api: ApiSnapshot,
    val charger: ChargerSnapshot,
    val storage: StorageState,
    val storageNotificationsAvailable: Boolean,
    // Refresh date-dependent storage UI even when disconnected telemetry is unchanged.
    val currentMinute: Long = System.currentTimeMillis() / 60_000,
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
    storageOpenToken: Int,
    onStorageAnswer: (Boolean) -> Unit,
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
    onSetRiderBoardProfile: (RiderBoardProfile) -> Unit,
    onSetApiEnabled: (Boolean) -> Unit,
    onStartOidc: (String) -> Unit,
    onClearOidcError: () -> Unit,
    oidcSignInPending: Boolean,
    oidcSignInError: String?,
    onDisconnectApi: () -> Unit,
    onRefreshApi: () -> Unit,
    onSyncApi: () -> Unit,
    onSetAnalytics: (Boolean) -> Unit,
    onResyncArchives: () -> Unit,
    onSetGps: (Boolean) -> Unit,
    onSetMotion: (Boolean) -> Unit,
    archiveExportBusy: Boolean,
    archiveExportMessage: String?,
    onExportArchive: () -> Unit,
    onReplayArchive: () -> Unit,
) {
    val app = dashboard.app
    val rides = dashboard.rides
    val today = LocalDate.now()
    val historyGroups = remember(rides.recentTracks, today) {
        TripHistoryGroups.build(rides.recentTracks, today)
    }
    var expandedHistoryGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var apiBaseUrl by rememberSaveable { mutableStateOf(dashboard.api.baseUrl.orEmpty()) }
    var page by remember { mutableStateOf(DashboardPage.OVERVIEW) }
    var storagePromptDismissed by remember(storageOpenToken) { mutableStateOf(false) }
    LaunchedEffect(storageOpenToken) { page = DashboardPage.OVERVIEW }
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
                    if (dashboard.storage.enabled) {
                        item(key = "storage-progress") {
                            StorageProgressCard(dashboard.storage, dashboard.currentMinute * 60_000, dashboard.storageNotificationsAvailable, onOpenSettings)
                        }
                    }
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
                    rides.speedBucketStatistics?.takeIf { it.profileBuckets(true).isNotEmpty() }?.let { statistics ->
                        item { RidingOverheads(statistics, Modifier.padding(bottom = 24.dp)) }
                    }
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
                            riderBoardProfile = dashboard.generalSettings.riderBoardProfile,
                            onSetRiderBoardProfile = onSetRiderBoardProfile,
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
                            baseUrl = apiBaseUrl,
                            onBaseUrlChange = {
                                apiBaseUrl = it
                                onClearOidcError()
                            },
                            oidcSignInPending = oidcSignInPending,
                            oidcSignInError = oidcSignInError,
                            onSetEnabled = onSetApiEnabled,
                            onStartOidc = onStartOidc,
                            onRequestRemoval = { pendingApiRemoval = true },
                            onRefresh = onRefreshApi,
                            onSync = onSyncApi,
                            onSetAnalytics = onSetAnalytics,
                            onResyncArchives = onResyncArchives,
                            onSetGps = onSetGps,
                            onSetMotion = onSetMotion,
                        )
                    }
                    item { AppSection(onOpenSettings = onOpenSettings, onVersionTap = onVersionTap) }
                    item {
                        Column {
                            ArchiveReplaySection(
                                state = dashboard.archiveReplay,
                                recording = rides.activeRide != null,
                                onReplay = onReplayArchive,
                            )
                            Spacer(Modifier.height(24.dp))
                            SectionTitle("Export archive", "Save all local archive partitions in one ZIP file.")
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onExportArchive, enabled = !archiveExportBusy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (archiveExportBusy) "EXPORTING ARCHIVE…" else "EXPORT LOCAL ARCHIVE")
                            }
                            archiveExportMessage?.let { NothingText(it, color = NothingMuted, size = 11, lineHeight = 17) }
                        }
                    }
                }
            }
        }
    }
    if (dashboard.storage.offerDue(System.currentTimeMillis()) && !storagePromptDismissed) {
        AlertDialog(
            onDismissRequest = { storagePromptDismissed = true },
            title = { Text("Activate storage mode?") },
            text = { Text("No recorded ride for 14 days. Connect your board to record its starting charge, then check in weekly while Riders Hub learns its battery loss. A ride over 0.1 km ends storage mode.") },
            confirmButton = { TextButton(onClick = { onStorageAnswer(true) }) { Text("ACTIVATE") } },
            dismissButton = { TextButton(onClick = { onStorageAnswer(false) }) { Text("NO") } },
        )
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
    Column(modifier.fillMaxWidth()) {
        NothingText("Riders Profile", size = 22, weight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        NothingText("Your riding mix and battery use by speed", color = NothingMuted, size = 11, lineHeight = 17)
        Spacer(Modifier.height(12.dp))
        // Measure both views to reserve their natural height, including larger font settings.
        // Only the selected chart is placed, drawn and exposed to accessibility services.
        Layout(content = {
            RidersProfileChart(statistics, energyUse = false)
            RidersProfileChart(statistics, energyUse = true)
        }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
            val charts = measurables.map { it.measure(constraints) }
            layout(charts.maxOf { it.width }, charts.maxOf { it.height }) {
                charts[if (energyUse) 1 else 0].placeRelative(0, 0)
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
private fun RidersProfileChart(statistics: BoardSpeedBucketStatistics?, energyUse: Boolean) {
    val buckets = statistics?.profileBuckets(energyUse).orEmpty()
    val values = buckets.map {
        (if (energyUse) it.batteryPercentPer100Km?.div(100.0) else it.distanceSharePercent)
            ?.takeIf { value -> value.isFinite() && value >= 0 }
    }
    val maximum = values.filterNotNull().maxOrNull()?.coerceAtLeast(0.001) ?: 1.0
    val minimum = values.filterNotNull().minOrNull() ?: maximum
    // A nearly flat cruise curve must not round to identical labels.
    val energyDecimals = if (maximum > 0 && (maximum - minimum) / maximum < 0.05) 3 else 2
    Column(Modifier.fillMaxWidth()) {
        NothingText(if (energyUse) "Energy use · % / km" else "Distance % · supported speeds", color = NothingMuted, size = 9)
        Spacer(Modifier.height(8.dp))
        if (buckets.isEmpty()) {
            NothingText(if (energyUse) "Collecting battery data" else "Collecting ride data", color = NothingMuted, size = 10)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                buckets.forEachIndexed { index, bucket ->
                    val value = values[index]
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
                        contentDescription = "${bucket.startKmh} to ${bucket.startKmh + 5} km/h, " +
                            (value?.let { UiFormat.decimal(it, if (energyUse) energyDecimals else 1) } ?: "no estimate") +
                            (if (energyUse) " battery percentage points per kilometre" else " percent of supported distance")
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        NothingText(value?.let { UiFormat.decimal(it, if (energyUse) energyDecimals else 0) } ?: "—",
                            color = NothingWhite, size = 8)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.BottomCenter) {
                            if (value != null && value > 0) {
                                Box(Modifier.fillMaxWidth().height((100 * value / maximum).toFloat().dp)
                                    .background(NothingWhite))
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
                NothingText(if (energyUse) "≥5 km measured · battery support" else "≥5 km measured per bucket", color = NothingMuted, size = 8)
                NothingText("km/h", color = NothingMuted, size = 8)
            }
        }
    }
}

@Composable
private fun RidingOverheads(statistics: BoardSpeedBucketStatistics, modifier: Modifier = Modifier) {
    val energyValues = statistics.profileBuckets(true).mapNotNull { it.batteryPercentPer100Km?.div(100.0) }
    val flatCurve = energyValues.size > 1 &&
        requireNotNull(energyValues.maxOrNull()) - requireNotNull(energyValues.minOrNull()) < 1e-9
    val shape = statistics.physicsShape
    val lines = buildList {
        if (shape == null) {
            add("Speed curve" to if (flatCurve) "Learning · shared rate" else "Window fit")
        } else {
            add("Stop-and-go" to "+${UiFormat.decimal(statistics.stopGoPercentPerKm, 3)} % / km")
            add("Hills" to "+${UiFormat.decimal(statistics.hillPercentPerKm, 3)} % / km")
            add("Recuperation" to (shape.recoveryFraction?.let { "${UiFormat.decimal(it * 100, 0)} % of braking" }
                ?: "Learning"))
            val nominalWh = statistics.nominalBatteryWh
            val effectiveWh = statistics.effectiveBatteryWh
            if (nominalWh != null && effectiveWh != null) {
                add("Usable pack energy" to if (statistics.packEnergyFactor == 1.0) {
                    "${UiFormat.decimal(nominalWh, 0)} Wh · stated"
                } else {
                    "${UiFormat.decimal(effectiveWh, 0)} of ${UiFormat.decimal(nominalWh, 0)} Wh"
                })
            }
            statistics.climbMPerKm?.let {
                add("Climbing" to "${UiFormat.decimal(it, 0)} m / km · " +
                    if (statistics.barometricShare >= 0.5) "barometer" else "inferred")
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        NothingText("Energy statistics", size = 22, weight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        NothingText("Cruise cost per speed; extras added to every trip", color = NothingMuted, size = 9)
        lines.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween) {
                NothingText(label, color = NothingMuted, size = 10)
                NothingText(value, size = 10, weight = FontWeight.Bold)
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
    riderBoardProfile: RiderBoardProfile,
    onSetRiderBoardProfile: (RiderBoardProfile) -> Unit,
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
        Spacer(Modifier.height(30.dp))
        RiderBoardProfileSettings(riderBoardProfile, onSetRiderBoardProfile)
    }
}

@Composable
private fun RiderBoardProfileSettings(
    profile: RiderBoardProfile,
    onSetProfile: (RiderBoardProfile) -> Unit,
) {
    var choosingModel by remember { mutableStateOf(false) }
    val model = profile.boardModel
    NothingText("Rider and board", size = 16, weight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
    NothingText(
        "Starting values for the consumption model; it learns how much energy your pack really delivers. " +
            "Stays on this phone.",
        color = NothingMuted,
        size = 10,
        lineHeight = 16,
    )
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Choose board model") { choosingModel = !choosingModel }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        NothingText("Board model", color = NothingMuted, size = 10)
        NothingText(model.displayName, size = 10, weight = FontWeight.Bold)
    }
    if (choosingModel) {
        Column(Modifier.selectableGroup()) {
            BoardModelCatalog.models.forEach { option ->
                val selected = option.id == model.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            choosingModel = false
                            // A new model resets board-specific overrides to its published values.
                            onSetProfile(profile.copy(boardModelId = option.id, boardMassKgOverride = null, batteryWhOverride = null))
                        })
                        .padding(vertical = 7.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    NothingText(option.displayName, color = if (selected) NothingRed else NothingWhite, size = 10)
                    NothingText(
                        "${UiFormat.decimal(option.boardMassKg, 1)} kg · ${UiFormat.decimal(option.batteryWh, 0)} Wh",
                        color = NothingMuted,
                        size = 10,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    ProfileNumberField(
        label = "Rider weight (kg)",
        value = profile.riderMassKg,
        placeholder = UiFormat.decimal(RiderBoardProfile.DEFAULT_RIDER_MASS_KG, 0),
        range = RiderBoardProfile.MIN_RIDER_MASS_KG..RiderBoardProfile.MAX_RIDER_MASS_KG,
        clearable = false,
    ) { onSetProfile(profile.copy(riderMassKg = it ?: RiderBoardProfile.DEFAULT_RIDER_MASS_KG)) }
    Spacer(Modifier.height(8.dp))
    ProfileNumberField(
        label = "Board weight (kg)",
        value = profile.boardMassKgOverride,
        placeholder = "${UiFormat.decimal(model.boardMassKg, 1)} · stock",
        range = RiderBoardProfile.MIN_BOARD_MASS_KG..RiderBoardProfile.MAX_BOARD_MASS_KG,
    ) { onSetProfile(profile.copy(boardMassKgOverride = it)) }
    Spacer(Modifier.height(8.dp))
    ProfileNumberField(
        label = "Battery energy (Wh)",
        value = profile.batteryWhOverride,
        placeholder = "${UiFormat.decimal(model.batteryWh, 0)} · stock",
        range = RiderBoardProfile.MIN_BATTERY_WH..RiderBoardProfile.MAX_BATTERY_WH,
    ) { onSetProfile(profile.copy(batteryWhOverride = it)) }
}

/** Commits only parseable in-range values; clearing an override restores the stock value. */
@Composable
private fun ProfileNumberField(
    label: String,
    value: Double?,
    placeholder: String,
    range: ClosedFloatingPointRange<Double>,
    clearable: Boolean = true,
    onCommit: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value?.let { UiFormat.decimal(it, if (it % 1.0 == 0.0) 0 else 1) } ?: "") }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val invalid = text.isNotBlank() && (parsed == null || parsed !in range)
    ApiTextField(
        value = text,
        onValueChange = { next ->
            text = next
            val number = next.replace(',', '.').toDoubleOrNull()
            when {
                next.isBlank() -> if (clearable) onCommit(null)
                number != null && number in range && number != value -> onCommit(number)
            }
        },
        label = label,
        placeholder = placeholder,
        keyboardType = KeyboardType.Decimal,
        isError = invalid,
    )
}

@Composable
private fun SelfHostedApiSection(
    state: ApiSnapshot,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    oidcSignInPending: Boolean,
    oidcSignInError: String?,
    onSetEnabled: (Boolean) -> Unit,
    onStartOidc: (String) -> Unit,
    onRequestRemoval: () -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onSetAnalytics: (Boolean) -> Unit,
    onResyncArchives: () -> Unit,
    onSetGps: (Boolean) -> Unit,
    onSetMotion: (Boolean) -> Unit,
) {
    val parsed = runCatching { apiUrls(baseUrl) }.getOrNull()
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
            ApiTextField(baseUrl, onBaseUrlChange, "API base URL",
                "https://api.example.test/prefix", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onStartOidc(baseUrl) },
                enabled = parsed?.base?.isHttps == true && !oidcSignInPending,
                colors = ButtonDefaults.buttonColors(containerColor = NothingRed),
            ) {
                if (oidcSignInPending) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                NothingLabel(if (oidcSignInPending) "CONNECTING TO SSO" else "SIGN IN WITH SSO", Color.White)
            }
            oidcSignInError?.let {
                Spacer(Modifier.height(8.dp))
                NothingText(it, color = NothingRed, size = 10, lineHeight = 15)
            }
            NothingText(
                "Uses your server's OIDC provider. The app receives a device credential after browser sign-in.",
                color = NothingMuted,
                size = 9,
                lineHeight = 14,
            )
        } else {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    NothingText(state.baseUrl.orEmpty(), size = 10, lineHeight = 15)
                    NothingText("SSO-enrolled device", color = NothingMuted, size = 9)
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
                    caps?.motion?.let { "MOTION v${it.schemaVersion}" },
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
            ApiStatusRow("Phone motion", if (caps?.motion == null) "Unsupported" else state.motionStatus)
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
            if (caps?.motion != null) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        NothingText("Record and share phone motion", size = 12, weight = FontWeight.Bold)
                        NothingText("Accelerometer and gyroscope during rides, at about 50 Hz. Saved locally and sent every five seconds. Includes precise sample times. Approval applies only to this API server.",
                            color = NothingMuted, size = 9, lineHeight = 14)
                    }
                    Switch(checked = state.motionEnabled, onCheckedChange = onSetMotion)
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
internal fun ArchiveReplaySection(state: ArchiveReplayState, recording: Boolean, onReplay: () -> Unit) {
    Column {
        SectionTitle("Archive recovery", "Restore trips and rebuild Riders Profile from saved recordings on this phone.")
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onReplay,
            enabled = !state.running && !recording,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = NothingWhite, contentColor = NothingBlack),
        ) {
            if (state.running) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(if (state.running) "REPLAYING ARCHIVE…" else "REPLAY LOCAL ARCHIVE", fontSize = 12.sp)
        }
        val message = if (recording && !state.running) "Finish the current recording before replaying archives." else state.message
        message?.let {
            Spacer(Modifier.height(12.dp))
            NothingText(it, color = if (state.failed) NothingRed else NothingMuted, size = 11, lineHeight = 17)
        }
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
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
                "This disables delivery and forgets the protected base URL and device credential. " +
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
