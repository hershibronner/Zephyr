package app.zephyr.fitness

import android.Manifest
import android.os.Build
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zephyr.fitness.ui.Units
import app.zephyr.fitness.ui.ZephyrViewModel
import app.zephyr.fitness.ui.screens.ObStep
import app.zephyr.fitness.ui.screens.OnboardingScreen
import app.zephyr.fitness.ui.screens.TodayScreen
import app.zephyr.fitness.ui.components.UpdateBanner
import app.zephyr.fitness.ui.theme.Z
import app.zephyr.fitness.ui.theme.ZephyrTheme
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
            viewModel.syncSteps()
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
                    Home(viewModel)
                }
            }
        }

        if (savedInstanceState == null) viewModel.syncSteps()
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncSteps()
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
private fun Home(viewModel: ZephyrViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val loggedDates by viewModel.loggedDates.collectAsStateWithLifecycle()
    val prescription by viewModel.prescription.collectAsStateWithLifecycle()
    val updateState by viewModel.update.collectAsStateWithLifecycle()

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
            TopBar(today)

            UpdateBanner(
                state = updateState,
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
                    onSelectDate = viewModel::selectDate,
                    onLogWeight = { showWeighIn = true },
                    onOpenPlan = { tab = Tab.PLAN },
                    onOpenFood = { tab = Tab.FOOD },
                    onDeleteFood = viewModel::deleteFood,
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
private fun TopBar(today: LocalDate) {
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
                .background(Z.Card),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Notifications, "Coach", tint = Z.Ink, modifier = Modifier.size(19.dp)) }
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
