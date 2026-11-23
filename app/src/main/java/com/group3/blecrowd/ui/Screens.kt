package com.group3.blecrowd.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.group3.blecrowd.BeaconAdvertiser
import com.group3.blecrowd.BleScanService
import com.group3.blecrowd.Constants
import com.group3.blecrowd.LiveState
import com.group3.blecrowd.Phase
import com.group3.blecrowd.Placement
import com.group3.blecrowd.RunPlanner
import com.group3.blecrowd.RunStatus
import com.group3.blecrowd.ScanModeOption
import com.group3.blecrowd.ScanRepository

private val Green = Color(0xFF2E7D32)

// =========================================================================================
// Shared widgets
// =========================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SegmentedRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                enabled = enabled,
            ) { Text(label(o), maxLines = 1) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelMedium)

// =========================================================================================
// Scanner role (S9 FE)
// =========================================================================================

@Composable
fun ScannerScreen(ready: Boolean) {
    val ctx = LocalContext.current
    val live by ScanRepository.state.collectAsStateWithLifecycle()
    val groundTruth by ScanRepository.groundTruth.collectAsStateWithLifecycle()
    val notes by ScanRepository.notes.collectAsStateWithLifecycle()

    var phase by rememberSaveable { mutableStateOf(Phase.CONTROLLED) }
    var mode by rememberSaveable { mutableStateOf(ScanModeOption.LOW_LATENCY) }
    var placement by rememberSaveable { mutableStateOf(Placement.HAND) }
    var duration by rememberSaveable { mutableStateOf(Constants.DEFAULT_RUN_SECONDS.toString()) }
    var runId by rememberSaveable { mutableStateOf("") }
    var rep by rememberSaveable { mutableStateOf("1") }
    var followPlan by rememberSaveable { mutableStateOf(true) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var savedRuns by remember { mutableStateOf(RunPlanner.savedRuns(ctx)) }

    val busy = live.status == RunStatus.COUNTDOWN || live.status == RunStatus.RUNNING
    val isLive = live.status == RunStatus.LIVE

    // Suggest the next run ID (and, for controlled runs, the next interleaved condition)
    // whenever the phase changes or a run has just been saved.
    LaunchedEffect(phase, live.completedRuns, followPlan) {
        savedRuns = RunPlanner.savedRuns(ctx)
        if (live.status == RunStatus.IDLE) {
            runId = RunPlanner.nextRunId(ctx, phase)
            if (phase == Phase.CONTROLLED && followPlan) {
                val s = RunPlanner.nextControlled(ctx)
                mode = s.mode
                placement = s.placement
                rep = s.rep.toString()
            }
        }
    }

    // ---------------- Run setup ----------------
    Section("Run setup") {
        Label("Phase")
        SegmentedRow(Phase.entries, phase, { it.label }, enabled = !busy && !isLive) {
            phase = it
            duration = it.defaultSeconds.toString()
            when (it) {
                Phase.CONTROLLED -> ScanRepository.groundTruth.value = "3"
                Phase.FIELD -> {
                    placement = Placement.FIELD
                    mode = ScanModeOption.LOW_LATENCY
                    ScanRepository.groundTruth.value = ""
                }
                Phase.BATTERY -> {
                    placement = Placement.HAND
                    ScanRepository.groundTruth.value = "3"
                }
            }
        }

        if (phase == Phase.CONTROLLED) {
            val done = savedRuns.count { it.second == Phase.CONTROLLED.name }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Follow interleaved plan (C1→C2→C3→C4…)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Controlled runs saved: $done / ${RunPlanner.CONTROLLED_TARGET}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = followPlan, onCheckedChange = { followPlan = it }, enabled = !busy)
            }
        }

        Label(if (isLive) "Scan mode (switches live)" else "Scan mode")
        SegmentedRow(ScanModeOption.entries, mode, { it.label }, enabled = !busy) {
            mode = it
            if (isLive) BleScanService.setLiveMode(ctx, it)
        }

        Label("Placement")
        SegmentedRow(Placement.entries, placement, { it.label }, enabled = !busy && !isLive) { placement = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = runId, onValueChange = { runId = it.take(12) }, label = { Text("Run ID") },
                singleLine = true, enabled = !busy, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = rep, onValueChange = { rep = it.filter(Char::isDigit).take(2) }, label = { Text("Rep") },
                singleLine = true, enabled = !busy, modifier = Modifier.weight(0.7f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(4) },
                label = { Text("Secs") }, singleLine = true, enabled = !busy, modifier = Modifier.weight(0.8f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Ground truth & notes stay editable during a run: they are read when the run ends.
            OutlinedTextField(
                value = groundTruth,
                onValueChange = { v -> ScanRepository.groundTruth.value = v.filter { it.isDigit() || it == '.' }.take(6) },
                label = { Text("Ground truth") }, singleLine = true, modifier = Modifier.weight(0.8f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = notes, onValueChange = { ScanRepository.notes.value = it.take(120) },
                label = { Text("Notes") }, singleLine = true, modifier = Modifier.weight(1.5f),
            )
        }
        if (phase == Phase.FIELD) {
            Text(
                "Field run: enter the AVERAGE headcount (t = 0, 2.5, 5 min) before the timer ends. " +
                    "Put e.g. \"Off-peak morning\" in Notes; it labels the chart.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // ---------------- Buttons ----------------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    BleScanService.startRun(
                        ctx, runId.ifBlank { RunPlanner.nextRunId(ctx, phase) }, phase, mode, placement,
                        rep.toIntOrNull() ?: 1, duration.toIntOrNull() ?: phase.defaultSeconds,
                    )
                },
                enabled = ready && !busy,
                modifier = Modifier.weight(1f),
            ) { Text("START RUN") }

            if (busy) {
                Button(
                    onClick = { showStopConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f),
                ) { Text("STOP") }
            } else {
                OutlinedButton(
                    onClick = { if (isLive) BleScanService.stop(ctx) else BleScanService.startLive(ctx, mode) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) { Text(if (isLive) "STOP LIVE" else "LIVE SCAN") }
            }
        }
        Text(
            "LIVE SCAN = demo mode, nothing is recorded. START RUN = 5 s countdown, then recording.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    LiveSection(live)
    BeaconPanel(live)
    RecentDevices(live)
    DataSection(enabled = !busy, savedCount = savedRuns.size)

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop this run?") },
            text = { Text("The partial data will be DISCARDED and nothing is added to summary.csv.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    BleScanService.stop(ctx)
                }) { Text("Stop & discard") }
            },
            dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text("Keep running") } },
        )
    }
}

@Composable
private fun LiveSection(live: LiveState) {
    Section("Live") {
        val statusText = when (live.status) {
            RunStatus.IDLE -> "Idle"
            RunStatus.COUNTDOWN -> "Starting ${live.runId} in ${live.secondsLeft} s… get into position!"
            RunStatus.RUNNING -> "● REC ${live.runId} · ${live.mode.label} · ${live.placement.label} · ${live.secondsLeft} s left"
            RunStatus.LIVE -> "● LIVE · ${live.mode.label} · ${live.elapsedSec} s (not recording)"
        }
        Text(
            statusText,
            color = if (live.status == RunStatus.RUNNING) MaterialTheme.colorScheme.error else Color.Unspecified,
            fontWeight = FontWeight.SemiBold,
        )
        if (live.status == RunStatus.COUNTDOWN) {
            Text("${live.secondsLeft}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
        } else {
            Text("Devices nearby (last ${Constants.WINDOW_MS / 1000} s)", style = MaterialTheme.typography.bodyMedium)
            Text("${live.windowCount}", fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Text("Total unique this run: ${live.totalUnique}   ·   Packets: ${live.totalPackets}")
        }
        live.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun BeaconPanel(live: LiveState) {
    Section("Ground-truth beacons") {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            for (id in 1..Constants.BEACON_COUNT) {
                val b = live.beacons[id]
                val recent = b != null && b.ageMs <= Constants.WINDOW_MS
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "B$id ${if (recent) "✔" else "✖"}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = if (recent) Green else MaterialTheme.colorScheme.error,
                    )
                    Text(if (b != null) "${b.rssi} dBm" else "—")
                    if (b != null) {
                        Text("${b.ageMs / 1000} s ago · n=${b.n}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        val detected = live.beacons.keys.count { it in 1..Constants.BEACON_COUNT }
        Text("Detected this run: $detected / ${Constants.BEACON_COUNT}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RecentDevices(live: LiveState) {
    if (live.recent.isEmpty()) return
    Section("Recently seen (hashed IDs, no real MACs)") {
        live.recent.forEach { d ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.hashId, fontFamily = FontFamily.Monospace)
                Text(d.beaconId?.let { "Beacon $it" } ?: "", color = Green)
                Text("${d.rssi} dBm", fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DataSection(enabled: Boolean, savedCount: Int) {
    val ctx = LocalContext.current
    Section("Data (D3)") {
        Text("Runs in summary.csv: $savedCount")
        Text(
            "Folder: Android/data/${ctx.packageName}/files/${Constants.RUNS_DIR}/",
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
        )
        Text(
            "Never uninstall the app before pulling the data: uninstalling deletes this folder (Risk R10).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { shareRuns(ctx) }, enabled = enabled) { Text("SHARE / EXPORT CSVs") }
    }
}

private fun shareRuns(ctx: Context) {
    val files = RunPlanner.csvFiles(ctx)
    if (files.isEmpty()) {
        Toast.makeText(ctx, "No CSV files yet", Toast.LENGTH_SHORT).show()
        return
    }
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/csv"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "BLE Crowd runs (${files.size} files)")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Export CSV files"))
}

// =========================================================================================
// Beacon role (S6 Lite)
// =========================================================================================

@Composable
fun BeaconScreen(ready: Boolean) {
    val ctx = LocalContext.current
    val status by BeaconAdvertiser.status.collectAsStateWithLifecycle()
    val active by BeaconAdvertiser.activeId.collectAsStateWithLifecycle()
    var beaconId by rememberSaveable { mutableIntStateOf(1) }

    Section("Beacon (ground-truth device)") {
        Label("Beacon ID")
        SegmentedRow(listOf(1, 2, 3), beaconId, { "Beacon $it" }, enabled = active == null) { beaconId = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { BeaconAdvertiser.start(ctx.applicationContext, beaconId) },
                enabled = ready && active == null,
                modifier = Modifier.weight(1f),
            ) { Text("START ADVERTISING") }
            OutlinedButton(
                onClick = { BeaconAdvertiser.stop() },
                enabled = active != null,
                modifier = Modifier.weight(1f),
            ) { Text("STOP") }
        }
        Text(status, fontWeight = FontWeight.SemiBold)
    }

    if (active != null) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("BEACON", style = MaterialTheme.typography.titleLarge)
                Text("$active", fontSize = 120.sp, fontWeight = FontWeight.Bold, color = Green)
                Text("Payload: 0xFFFF · 47 33 · 0$active", fontFamily = FontFamily.Monospace)
            }
        }
    }

    Section("Checklist") {
        listOf(
            "Keep this screen ON and the app open (the screen stays awake automatically).",
            "Plug the tablet in if possible.",
            "Same TX power on all 3 beacons (fixed in code: MEDIUM).",
            "Beacon 1 at 2 m, Beacon 2 at 4 m, Beacon 3 at 6 m, about 1 m high. Mark the spots with tape.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}
