package app.zephyr.fitness

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zephyr.fitness.ui.ZephyrViewModel
import app.zephyr.fitness.ui.screens.OnboardingScreen
import app.zephyr.fitness.ui.screens.TodayScreen
import app.zephyr.fitness.ui.theme.ZephyrTheme

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
                        preview = viewModel.previewPlan(draft),
                        onDraftChange = viewModel::updateDraft,
                        onFinish = {
                            viewModel.completeOnboarding(draft) { requestRuntimePermissions() }
                        },
                    )
                } else {
                    HomeScaffold(viewModel)
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
     * Permissions are requested after onboarding, not on first launch.
     *
     * A cold permission dialog before the user knows what the app is gets denied, and on Android a
     * denial is sticky — the second ask never appears. Asking once the user has just set a goal
     * means the request has visible purpose.
     */
    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
private fun HomeScaffold(viewModel: ZephyrViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showQuickAdd by remember { mutableStateOf(false) }
    var showWeighIn by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showQuickAdd = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text("Log food")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            TodayScreen(
                state = state,
                onQuickAdd = { showQuickAdd = true },
                onStartSession = { showWeighIn = true },
            )
        }
    }

    if (showQuickAdd) {
        NumberDialog(
            title = "Quick add",
            label = "Calories",
            confirmText = "Add",
            onDismiss = { showQuickAdd = false },
            onConfirm = { value ->
                viewModel.quickAddCalories(value.toInt())
                showQuickAdd = false
            },
        )
    }

    if (showWeighIn) {
        NumberDialog(
            title = "Log weight",
            label = "Weight (kg)",
            confirmText = "Save",
            onDismiss = { showWeighIn = false },
            onConfirm = { value ->
                viewModel.logWeight(value)
                showWeighIn = false
            },
        )
    }
}

@Composable
private fun NumberDialog(
    title: String,
    label: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val value = text.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() || it == '.' } },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onConfirm) },
                enabled = value != null && value > 0,
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
