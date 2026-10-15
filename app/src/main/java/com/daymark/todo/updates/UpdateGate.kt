package com.daymark.todo.updates

import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.daymark.todo.BuildConfig
import com.daymark.todo.R
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UpdateGate(controller: UpdateController, content: @Composable () -> Unit) {
    val policy by controller.policy.collectAsStateWithLifecycle()
    val checked by controller.checked.collectAsStateWithLifecycle()
    val checking by controller.checking.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val dismissed by controller.dismissedVersion.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val installer = remember(context) {runCatching {
        if(Build.VERSION.SDK_INT>=30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()}
    var launchError by remember(policy?.update_url) { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, controller) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                controller.check()
                delay(com.daymark.todo.core.SyncCadence.delayMillis(BuildConfig.UPDATE_CHECK_SECONDS,sample=kotlin.random.Random.nextDouble()))
            }
        }
    }
    val current = policy
    val kind = current?.kind(BuildConfig.VERSION_CODE) ?: UpdateKind.NONE
    val openUpdate = {
        launchError = null
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, current!!.destination(installer,BuildConfig.APPLICATION_ID).toUri()))
        } catch (_: Exception) {
            launchError = "Unable to open the update link. Install a browser and try again."
        }
    }
    if (kind == UpdateKind.HARD || !checked) {
        // Do not compose the app underneath the required update or initial check.
        BackHandler { }
        Surface(Modifier.fillMaxSize()) {
            if(kind!=UpdateKind.HARD) com.daymark.todo.ui.SplashScreen()
            else Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Update required", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("Install ${stringResource(R.string.app_name)} ${current!!.version_name} to continue using the app.")
                    Text(current.message)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = openUpdate) { Text("Update now") }
                    TextButton(enabled = !checking, onClick = { scope.launch { controller.check() } }) {
                        Text(if (checking) "Checking…" else "Check again")
                    }
                    (launchError ?: error)?.let { Text(it) }
            }
        }
    } else {
        content()
        if (kind == UpdateKind.SOFT && current != null && dismissed != current.latest_version_code) {
            AlertDialog(
                onDismissRequest = controller::dismiss,
                title = { Text("Update available") },
                text = { Column {
                    Text("${stringResource(R.string.app_name)} ${current.version_name}")
                    Text(current.message)
                    Text("You can keep using the app and update later.")
                    launchError?.let { Text(it) }
                } },
                confirmButton = { TextButton(onClick = openUpdate) { Text("Update") } },
                dismissButton = { TextButton(onClick = controller::dismiss) { Text("Later") } }
            )
        }
    }
}
