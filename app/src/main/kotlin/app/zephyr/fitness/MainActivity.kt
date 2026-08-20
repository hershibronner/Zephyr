package app.zephyr.fitness

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.ZephyrViewModel
import app.zephyr.fitness.ui.screens.ObStep
import app.zephyr.fitness.ui.screens.CaptureMode
import app.zephyr.fitness.ui.screens.CaptureScreen
import app.zephyr.fitness.ui.screens.EstimateDialog
import app.zephyr.fitness.ui.screens.FoodScreen
import app.zephyr.fitness.ui.screens.MoveScreen
import app.zephyr.fitness.ui.screens.PortionDialog
import app.zephyr.fitness.ui.screens.OnboardingScreen
import app.zephyr.fitness.ui.screens.TodayScreen
import app.zephyr.fitness.tracking.TrackingService
import app.zephyr.fitness.ui.components.UpdateBanner
import app.zephyr.fitness.ui.theme.Z
import app.zephyr.fitness.ui.theme.ZephyrTheme
import app.zephyr.fitness.ui.FoodFlow
import app.zephyr.fitness.update.UpdateState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("Today", Icons.Outlined.Circle),
    FOOD("Food", Icons.Filled.Restaurant),
    MOVE("Move", Icons.Filled.DirectionsRun),
    PLAN("Plan", Icons.Filled.CalendarMonth),
    PROGRESS("Progress", Icons.Filled.BarChart),
}

class MainActivity : ComponentActivity() {

    private val viewModel: ZephyrViewModel by viewModels {
        ZephyrViewModel.Factory((application as ZephyrApplication).container)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasActivityRecognition.value = checkStepPermission()
            viewModel.syncSteps()
        }

    /** Recomputed on resume, so granting a permission in Settings shows up without a restart. */
    private val hasLocation = mutableStateOf(false)
    private val hasActivityRecognition = mutableStateOf(true)

    private val hasCamera = mutableStateOf(false)

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasCamera.value = checkCameraPermission()
            // Opening the camera is the only reason this is ever asked for, so go straight there.
            if (hasCamera.value) viewModel.openCapture()
        }

    private val locationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasLocation.value = checkLocationPermission()
        }

    /** Below Android Q the permission does not exist, so the counter is always readable. */
    private fun checkStepPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStepPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        }
    }

    private fun checkCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        cameraLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun checkLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setContent {
            ZephyrTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                val draft by viewModel.draft.collectAsStateWithLifecycle()

                if (!state.settings.onboarded) {
                    OnboardingScreen(
                        draft = draft,
                        preview = if (draft.step == ObStep.REVEAL) viewModel.previewPlan(draft) else null,
                        onChange = viewModel::updateDraft,
                        onFinish = { viewModel.completeOnboarding(draft) { requestRuntimePermissions() } },
                    )
                } else {
                    Home(
                        viewModel = viewModel,
                        hasLocation = hasLocation.value,
                        onRequestLocation = ::requestLocation,
                        stepsBlocked = !hasActivityRecognition.value,
                        onEnableSteps = ::requestStepPermission,
                        hasCamera = hasCamera.value,
                        onRequestCamera = ::requestCamera,
                    )
                }
            }
        }

        if (savedInstanceState == null) viewModel.syncSteps()
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncSteps()
        // Also on resume, not just cold start: phones keep apps alive for days, and an update the
        // user cannot see until they force-quit is an update that never gets installed. This also
        // covers coming back from the system permission screen mid-install.
        viewModel.checkForUpdate()
        viewModel.refreshInstallPermission()
        hasLocation.value = checkLocationPermission()
        hasActivityRecognition.value = checkStepPermission()
        hasCamera.value = checkCameraPermission()
    }

    /**
     * Permissions are requested after onboarding, not on first launch. A cold dialog before the user
     * knows what the app is gets denied, and on Android a denial is sticky — the second ask never
     * appears. Asking once they've just set a goal means the request has visible purpose.
     */
    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
private fun Home(
    viewModel: ZephyrViewModel,
    hasLocation: Boolean,
    onRequestLocation: () -> Unit,
    stepsBlocked: Boolean,
    onEnableSteps: () -> Unit,
    hasCamera: Boolean,
    onRequestCamera: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tracking by viewModel.tracking.collectAsStateWithLifecycle()
    val history by viewModel.sessionHistory.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val loggedDates by viewModel.loggedDates.collectAsStateWithLifecycle()
    val prescription by viewModel.prescription.collectAsStateWithLifecycle()
    val updateState by viewModel.update.collectAsStateWithLifecycle()
    val foodFlow by viewModel.foodFlow.collectAsStateWithLifecycle()
    val recentFoods by viewModel.recentFoods.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    var captureMode by remember { mutableStateOf(CaptureMode.BARCODE) }
    var showKeyDialog by remember { mutableStateOf(false) }
    val needsInstallPermission by viewModel.needsInstallPermission.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.TODAY) }
    var showQuickAdd by remember { mutableStateOf(false) }
    var showWeighIn by remember { mutableStateOf(false) }

    val today = remember { LocalDate.now() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Z.Page),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(today) { showKeyDialog = true }

            UpdateBanner(
                state = updateState,
                needsPermission = needsInstallPermission,
                onDownload = {
                    (updateState as? UpdateState.Available)?.let { viewModel.downloadUpdate(it.manifest) }
                },
                onInstall = viewModel::installUpdate,
                onDismiss = viewModel::dismissUpdate,
            )

            when (tab) {
                Tab.TODAY -> TodayScreen(
                    state = state,
                    selectedDate = selectedDate,
                    today = today,
                    loggedDates = loggedDates,
                    prescription = prescription,
                    sessionDone = state.sessionsToday > 0,
                    stepsBlocked = stepsBlocked,
                    onEnableSteps = onEnableSteps,
                    onSelectDate = viewModel::selectDate,
                    onLogWeight = { showWeighIn = true },
                    onOpenPlan = { tab = Tab.PLAN },
                    onOpenFood = { tab = Tab.FOOD },
                    onDeleteFood = viewModel::deleteFood,
                    modifier = Modifier.weight(1f),
                )
                Tab.FOOD -> FoodScreen(
                    balance = state.balance,
                    entries = state.entries,
                    recents = recentFoods,
                    canUsePhotos = hasApiKey,
                    onScan = { if (hasCamera) viewModel.openCapture() else onRequestCamera() },
                    onQuickAdd = { showQuickAdd = true },
                    onPickRecent = viewModel::startPortioning,
                    onDelete = viewModel::deleteFood,
                    modifier = Modifier.weight(1f),
                )
                Tab.MOVE -> MoveScreen(
                    tracking = tracking,
                    history = history,
                    hasLocationPermission = hasLocation,
                    onRequestPermission = onRequestLocation,
                    onStart = { type ->
                        if (hasLocation) {
                            TrackingService.start(context, type, viewModel.trackingWeightKg())
                        } else {
                            onRequestLocation()
                        }
                    },
                    onPause = { TrackingService.send(context, TrackingService.ACTION_PAUSE) },
                    onResume = { TrackingService.send(context, TrackingService.ACTION_RESUME) },
                    onFinish = {
                        // Stop the service first: the repository is cleared as part of finishing, and
                        // a late GPS fix landing afterwards would restart a session that just ended.
                        TrackingService.send(context, TrackingService.ACTION_STOP)
                        viewModel.finishTracking()
                    },
                    onDiscard = {
                        TrackingService.send(context, TrackingService.ACTION_STOP)
                        viewModel.discardTracking()
                    },
                    modifier = Modifier.weight(1f),
                )
                // The remaining tabs are still being ported from the prototype; the screen says so
                // rather than presenting an empty shell as though it were finished.
                else -> ComingSoon(tab, Modifier.weight(1f))
            }
        }

        if (tab == Tab.TODAY) {
            ExtendedFloatingActionButton(
                onClick = { showQuickAdd = true },
                containerColor = Z.Violet,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 104.dp),
            ) { Text("Log food", fontWeight = FontWeight.Bold) }
        }

        BottomNav(
            current = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        )
    }

    // The camera covers everything, including the nav bar: while it is open the only meaningful
    // actions are shoot, switch mode, or close.
    (foodFlow as? FoodFlow.Capturing)?.let { capturing ->
        CaptureScreen(
            mode = captureMode,
            busy = capturing.busy,
            status = capturing.status,
            onModeChange = { captureMode = it },
            onBarcode = viewModel::onBarcode,
            onPhoto = viewModel::onMealPhoto,
            onClose = viewModel::closeFoodFlow,
        )
    }

    (foodFlow as? FoodFlow.Portioning)?.let { portioning ->
        PortionDialog(
            food = portioning.food,
            grams = portioning.grams,
            onGramsChange = viewModel::updatePortion,
            onConfirm = { viewModel.logPortion() },
            onDismiss = viewModel::closeFoodFlow,
        )
    }

    (foodFlow as? FoodFlow.Reviewing)?.let { reviewing ->
        EstimateDialog(
            estimate = reviewing.estimate,
            onConfirm = { viewModel.logEstimate(reviewing.estimate) },
            onDismiss = viewModel::closeFoodFlow,
        )
    }

    (foodFlow as? FoodFlow.Failed)?.let { failed ->
        AlertDialog(
            onDismissRequest = viewModel::closeFoodFlow,
            containerColor = Z.Card,
            title = { Text("Couldn't log that", fontWeight = FontWeight.Bold) },
            text = { Text(failed.reason, color = Z.Muted, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = {
                TextButton(onClick = viewModel::closeFoodFlow) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
        )
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            hasKey = hasApiKey,
            onSave = { viewModel.saveApiKey(it); showKeyDialog = false },
            onDismiss = { showKeyDialog = false },
        )
    }

    if (showQuickAdd) {
        NumberDialog(
            title = "Quick add",
            label = "Calories",
            confirm = "Add",
            onDismiss = { showQuickAdd = false },
            onConfirm = { viewModel.quickAddCalories(it.toInt()); showQuickAdd = false },
        )
    }

    if (showWeighIn) {
        NumberDialog(
            title = "Log weight",
            label = "Weight (lb)",
            confirm = "Save",
            initial = state.trend.currentTrendKg?.let { String.format(Locale.US, "%.1f", Units.kgToLb(it)) } ?: "",
            onDismiss = { showWeighIn = false },
            onConfirm = { viewModel.logWeightPounds(it); showWeighIn = false },
        )
    }
}

@Composable
private fun TopBar(today: LocalDate, onOpenSettings: () -> Unit) {
    val hour = remember { java.time.LocalTime.now().hour }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Z.Violet),
            contentAlignment = Alignment.Center,
        ) { Text("YOU", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

        Column(Modifier.weight(1f)) {
            Text(
                when {
                    hour < 5 -> "Still up?"
                    hour < 12 -> "Good morning"
                    hour < 18 -> "Good afternoon"
                    else -> "Good evening"
                },
                color = Z.Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp,
            )
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.US)),
                color = Z.Muted, fontSize = 13.sp,
            )
        }

        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Z.Card)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Settings, "Settings", tint = Z.Ink, modifier = Modifier.size(19.dp)) }
    }
}

/** Floating dark bar with the active tab lifted into a white circle. */
@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(34.dp))
            .background(Z.Nav)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { entry ->
            val on = entry == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(entry) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = entry.label,
                    tint = if (on) Z.Nav else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun ComingSoon(tab: Tab, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(40.dp),
        ) {
            Text(tab.label, color = Z.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "Being ported from the web prototype next.",
                color = Z.Muted, fontSize = 14.sp,
            )
        }
    }
}

/**
 * Where the user supplies their own Anthropic API key for photo estimation.
 *
 * The key is theirs and stays on the device. Shipping one inside the APK would put it in the hands
 * of anyone who downloads the app, billed to whoever published it — so the feature is off until a
 * key is entered, rather than quietly working on someone else's account.
 */
@Composable
private fun ApiKeyDialog(
    hasKey: Boolean,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Card,
        title = { Text("Photo calorie estimates", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (hasKey) {
                        "A key is saved. Photo estimates are on. Paste a new one to replace it, " +
                            "or clear the box and save to turn photos off."
                    } else {
                        "Photographing a meal sends it to Claude for a calorie estimate. That needs " +
                            "your own Anthropic API key from console.anthropic.com — it stays on this " +
                            "phone and the usage is billed to you."
                    },
                    color = Z.Muted, fontSize = 13.sp, lineHeight = 19.sp,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(if (hasKey) "New key" else "sk-ant-...") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Barcode scanning needs no key and always works.",
                    color = Z.Faint, fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.takeIf { it.isNotBlank() }) }) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val value = text.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Z.Card,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null && value > 0) {
                Text(confirm, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
