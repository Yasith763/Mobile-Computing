package com.group3.blecrowd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.group3.blecrowd.ui.BeaconScreen
import com.group3.blecrowd.ui.ScannerScreen
import com.group3.blecrowd.ui.SegmentedRow

class MainActivity : ComponentActivity() {

    /** Incremented on every onResume so the UI re-checks permissions / Bluetooth / Location. */
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Risk R1: unfiltered scans pause when the screen turns off, and beacons must not sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AppTheme {
                App(resumeTick.intValue)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

object Permissions {
    /** Everything we ask for. */
    fun requested(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** What the app cannot work without (notifications are optional). */
    fun essentialGranted(context: Context): Boolean = requested()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private enum class Role(val label: String) { SCANNER("Scanner"), BEACON("Beacon") }

@SuppressLint("MissingPermission")
@Composable
private fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("blecrowd", Context.MODE_PRIVATE) }
    var role by rememberSaveable {
        mutableStateOf(runCatching { Role.valueOf(prefs.getString("role", null) ?: "SCANNER") }.getOrDefault(Role.SCANNER))
    }

    var permsOk by remember { mutableStateOf(Permissions.essentialGranted(ctx)) }
    var btOn by remember { mutableStateOf(isBluetoothOn(ctx)) }
    var locationOn by remember { mutableStateOf(isLocationOn(ctx)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsOk = Permissions.essentialGranted(ctx)
        btOn = isBluetoothOn(ctx)
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = isBluetoothOn(ctx)
    }

    LaunchedEffect(resumeTick) {
        permsOk = Permissions.essentialGranted(ctx)
        btOn = isBluetoothOn(ctx)
        locationOn = isLocationOn(ctx)
        if (!permsOk && !askedOnce) {
            askedOnce = true
            permLauncher.launch(Permissions.requested())
        }
    }

    val live by ScanRepository.state.collectAsStateWithLifecycle()
    val beaconActive by BeaconAdvertiser.activeId.collectAsStateWithLifecycle()
    val roleLocked = live.status != RunStatus.IDLE || beaconActive != null

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("BLE Crowd Sensing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("CS4473 · Group 3", style = MaterialTheme.typography.bodySmall)

            SegmentedRow(
                options = Role.entries,
                selected = role,
                label = { it.label },
                enabled = !roleLocked,
            ) {
                role = it
                prefs.edit().putString("role", it.name).apply()
            }

            // ---------- readiness checks ----------
            if (!permsOk) {
                Warning("Bluetooth / Location permissions are required.") {
                    OutlinedButton(onClick = { permLauncher.launch(Permissions.requested()) }) { Text("Grant") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        )
                    }) { Text("App settings") }
                }
            }
            if (permsOk && !btOn) {
                Warning("Bluetooth is OFF.") {
                    OutlinedButton(onClick = {
                        runCatching { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }) { Text("Turn on") }
                }
            }
            if (role == Role.SCANNER && !locationOn) {
                Warning("Location is OFF. Samsung phones return no BLE scan results without it (Risk R4).") {
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) {
                        Text("Location settings")
                    }
                }
            }

            val ready = permsOk && btOn
            when (role) {
                Role.SCANNER -> ScannerScreen(ready = ready)
                Role.BEACON -> BeaconScreen(ready = ready)
            }
        }
    }
}

@Composable
private fun Warning(text: String, actions: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

private fun isBluetoothOn(ctx: Context): Boolean =
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

private fun isLocationOn(ctx: Context): Boolean =
    ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
