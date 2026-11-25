package com.group3.blecrowd

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Foreground service that does all BLE scanning.
 *
 * Two ways to use it:
 *  - RUN:  5 s countdown -> scan for N seconds -> events_<runId>.csv + one row in summary.csv -> vibrate.
 *  - LIVE: scan without logging (for the demo); the scan mode can be switched while it runs.
 *
 * Threading: timers and Bluetooth calls run on the main thread. Every scan result is posted to a single
 * worker thread that does hashing, counting and CSV writing, so the UI never stalls in LOW_LATENCY.
 */
class BleScanService : Service() {

    companion object {
        private const val ACTION_START_RUN = "com.group3.blecrowd.START_RUN"
        private const val ACTION_START_LIVE = "com.group3.blecrowd.START_LIVE"
        private const val ACTION_SET_MODE = "com.group3.blecrowd.SET_MODE"
        private const val ACTION_STOP = "com.group3.blecrowd.STOP"

        private const val EXTRA_RUN_ID = "runId"
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PLACEMENT = "placement"
        private const val EXTRA_REP = "rep"
        private const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "scan"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 500L

        fun startRun(
            context: Context, runId: String, phase: Phase, mode: ScanModeOption,
            placement: Placement, rep: Int, durationS: Int,
        ) {
            val i = Intent(context, BleScanService::class.java).setAction(ACTION_START_RUN)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_PHASE, phase.name)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_PLACEMENT, placement.name)
                .putExtra(EXTRA_REP, rep)
                .putExtra(EXTRA_DURATION, durationS)
            ContextCompat.startForegroundService(context, i)
        }

        fun startLive(context: Context, mode: ScanModeOption) {
            val i = Intent(context, BleScanService::class.java).setAction(ACTION_START_LIVE)
                .putExtra(EXTRA_MODE, mode.name)
            ContextCompat.startForegroundService(context, i)
        }

        fun setLiveMode(context: Context, mode: ScanModeOption) {
            context.startService(
                Intent(context, BleScanService::class.java).setAction(ACTION_SET_MODE).putExtra(EXTRA_MODE, mode.name)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BleScanService::class.java).setAction(ACTION_STOP))
        }
    }

    private data class RunConfig(
        val runId: String, val phase: Phase, val mode: ScanModeOption,
        val placement: Placement, val rep: Int, val durationS: Int,
    )

    private data class Battery(val pct: Int?, val uah: Int?)

    private enum class State { IDLE, COUNTDOWN, RUNNING, FINISHING, LIVE }

    // ---------- main-thread state ----------
    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var cfg: RunConfig? = null
    private var liveMode = ScanModeOption.LOW_LATENCY
    private var countdownEnd = 0L
    private var scanStartWall = 0L
    private var runEnd = 0L
    private var batteryStart = Battery(null, null)
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var pendingRestart: Runnable? = null
    private var tickerRunning = false

    // ---------- worker-thread state ----------
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val tracker = DeviceTracker()
    private var logger: CsvLogger? = null
    private var accepting = false
    private var lastUiPush = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (tickerRunning) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("ble-worker").also { it.start() }
        worker = Handler(workerThread.looper)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BLE scanning", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            if (state == State.IDLE) stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START_RUN -> {
                if (state == State.COUNTDOWN || state == State.RUNNING || state == State.FINISHING) {
                    return START_NOT_STICKY // a run is already in progress
                }
                if (!goForeground("Preparing run...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (state == State.LIVE) stopLiveScanOnly()
                val c = RunConfig(
                    runId = intent.getStringExtra(EXTRA_RUN_ID) ?: "RUN",
                    phase = enumOr(intent.getStringExtra(EXTRA_PHASE), Phase.CONTROLLED),
                    mode = enumOr(intent.getStringExtra(EXTRA_MODE), ScanModeOption.LOW_LATENCY),
                    placement = enumOr(intent.getStringExtra(EXTRA_PLACEMENT), Placement.HAND),
                    rep = intent.getIntExtra(EXTRA_REP, 1),
                    durationS = intent.getIntExtra(EXTRA_DURATION, Constants.DEFAULT_RUN_SECONDS).coerceAtLeast(5),
                )
                beginCountdown(c)
            }

            ACTION_START_LIVE -> {
                val mode = enumOr(intent.getStringExtra(EXTRA_MODE), ScanModeOption.LOW_LATENCY)
                when (state) {
                    State.IDLE -> {
                        if (!goForeground("Live scan (not recording)")) {
                            stopSelf()
                            return START_NOT_STICKY
                        }
                        startLive(mode)
                    }
                    State.LIVE -> changeLiveMode(mode)
                    else -> Unit
                }
            }

            ACTION_SET_MODE -> {
                if (state == State.LIVE) {
                    changeLiveMode(enumOr(intent.getStringExtra(EXTRA_MODE), liveMode))
                } else if (state == State.IDLE) {
                    stopSelf()
                }
            }

            ACTION_STOP -> when (state) {
                State.COUNTDOWN, State.RUNNING -> abortRun("Run stopped early. Data discarded; re-run with the same ID.")
                State.LIVE -> stopLive()
                State.IDLE -> stopSelf()
                State.FINISHING -> Unit
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopScan()
        tickerRunning = false
        main.removeCallbacksAndMessages(null)
        if (state == State.COUNTDOWN || state == State.RUNNING) {
            ScanRepository.update {
                it.copy(status = RunStatus.IDLE, error = "Scanning service was stopped by the system. Run discarded.")
            }
        } else if (state == State.LIVE) {
            ScanRepository.update { it.copy(status = RunStatus.IDLE) }
        }
        worker.post {
            accepting = false
            logger?.close()
            logger = null
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    // =====================================================================================
    // Controlled / field / battery run
    // =====================================================================================

    private fun beginCountdown(requested: RunConfig) {
        val dir = RunPlanner.runsDir(this)
        val c = requested.copy(runId = RunPlanner.uniqueRunId(dir, requested.runId))
        cfg = c
        state = State.COUNTDOWN
        countdownEnd = System.currentTimeMillis() + Constants.START_DELAY_SECONDS * 1000L
        ScanRepository.update {
            it.copy(
                status = RunStatus.COUNTDOWN, runId = c.runId, phase = c.phase, mode = c.mode,
                placement = c.placement, secondsLeft = Constants.START_DELAY_SECONDS, elapsedSec = 0,
                windowCount = 0, totalUnique = 0, totalPackets = 0, beacons = emptyMap(), recent = emptyList(),
                message = "Get into position (${c.placement.label})...", error = null,
            )
        }
        updateNotification("Run ${c.runId} starts in ${Constants.START_DELAY_SECONDS} s")
        startTicker()
    }

    private fun beginScanning(now: Long) {
        val c = cfg ?: return
        val dir = RunPlanner.runsDir(this)
        batteryStart = readBattery()
        scanStartWall = now
        worker.post {
            tracker.reset(now)
            lastUiPush = 0
            logger = try {
                CsvLogger(dir, c.runId, c.mode, c.placement)
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Cannot create CSV", e)
                ScanRepository.update { it.copy(error = "Cannot create CSV file: ${e.message}") }
                null
            }
            accepting = true
        }
        if (!startScan(c.mode)) {
            abortRun(null)
            return
        }
        state = State.RUNNING
        runEnd = now + c.durationS * 1000L
        ScanRepository.update {
            it.copy(
                status = RunStatus.RUNNING, secondsLeft = c.durationS, elapsedSec = 0,
                message = "Recording ${c.runId}: ${c.mode.label}, ${c.placement.label}. Stand still.",
            )
        }
        updateNotification("Recording ${c.runId} (${c.mode.label}, ${c.placement.label})")
    }

    private fun finishRun() {
        val c = cfg ?: return
        state = State.FINISHING
        stopScan()
        val batteryEnd = readBattery()
        val bStart = batteryStart
        val gt = normaliseNumber(ScanRepository.groundTruth.value)
        val notes = ScanRepository.notes.value.trim()
        val startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(scanStartWall))
        val dir = RunPlanner.runsDir(this)

        worker.post {
            accepting = false
            val now = System.currentTimeMillis()
            logger?.close()
            logger = null

            val stats = tracker.beaconStats()
            val detected = tracker.beaconsDetected()
            val unique = tracker.totalUnique()
            val row = mutableMapOf<String, Any?>(
                "run_id" to c.runId,
                "phase" to c.phase.name,
                "start_time" to startTime,
                "mode" to c.mode.name,
                "placement" to c.placement.name,
                "rep" to c.rep,
                "duration_s" to c.durationS,
                "ground_truth" to gt,
                "total_unique" to unique,
                "window_count_end" to tracker.windowCount(now),
                "beacons_detected" to detected,
                "total_packets" to tracker.totalPackets,
                "battery_pct_start" to bStart.pct,
                "battery_pct_end" to batteryEnd.pct,
                "charge_uah_start" to bStart.uah,
                "charge_uah_end" to batteryEnd.uah,
                "notes" to notes,
            )
            for (b in 1..Constants.BEACON_COUNT) {
                val s = stats[b]
                row["b${b}_first_ms"] = s?.firstElapsedMs
                row["b${b}_mean_rssi"] = s?.mean
                row["b${b}_std_rssi"] = s?.std
                row["b${b}_n"] = s?.n ?: 0
            }

            var error: String? = null
            try {
                CsvLogger.appendSummary(dir, row)
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "Cannot write summary", e)
                error = "Could not write summary.csv: ${e.message}"
            }
            pushUi(now, force = true)
            ScanRepository.update {
                it.copy(
                    status = RunStatus.IDLE, secondsLeft = 0,
                    message = if (error == null) "Saved ${c.runId}: $detected/3 beacons, $unique unique devices" else null,
                    error = error,
                    completedRuns = it.completedRuns + 1,
                )
            }
            main.post {
                vibrate()
                endService()
            }
        }
    }

    private fun abortRun(reason: String?) {
        state = State.FINISHING
        stopScan()
        worker.post {
            accepting = false
            logger?.let {
                it.close()
                it.file.delete()
            }
            logger = null
            ScanRepository.update {
                it.copy(status = RunStatus.IDLE, secondsLeft = 0, message = reason)
            }
            main.post { endService() }
        }
    }

    // =====================================================================================
    // Live (demo) mode: no logging, scan mode can be switched on the fly
    // =====================================================================================

    private fun startLive(mode: ScanModeOption) {
        state = State.LIVE
        liveMode = mode
        resetLiveTracker()
        ScanRepository.update {
            it.copy(
                status = RunStatus.LIVE, runId = "", mode = mode, elapsedSec = 0, secondsLeft = 0,
                windowCount = 0, totalUnique = 0, totalPackets = 0, beacons = emptyMap(), recent = emptyList(),
                message = "Live scan (${mode.label}), not recording", error = null,
            )
        }
        scheduleLiveScan(0)
        startTicker()
    }

    private fun changeLiveMode(mode: ScanModeOption) {
        if (mode == liveMode && scanning) return
        liveMode = mode
        stopScan()
        resetLiveTracker()
        ScanRepository.update { it.copy(mode = mode, message = "Switching to ${mode.label}...") }
        // R2: stop -> wait ~1 s -> start once.
        scheduleLiveScan(1_000)
    }

    private fun scheduleLiveScan(minDelayMs: Long) {
        pendingRestart?.let { main.removeCallbacks(it) }
        val now = System.currentTimeMillis()
        val delay = max(minDelayMs, ScanLimiter.delayNeeded(now + minDelayMs) + minDelayMs)
        if (delay > minDelayMs + 100) {
            ScanRepository.update {
                it.copy(message = "Waiting ${delay / 1000 + 1} s: Android allows only ~5 scan starts per 30 s")
            }
        }
        val r = Runnable {
            pendingRestart = null
            if (state == State.LIVE && startScan(liveMode)) {
                ScanRepository.update { it.copy(message = "Live scan (${liveMode.label}), not recording") }
            }
        }
        pendingRestart = r
        main.postDelayed(r, delay)
    }

    private fun resetLiveTracker() {
        val now = System.currentTimeMillis()
        scanStartWall = now
        worker.post {
            tracker.reset(now)
            logger = null
            lastUiPush = 0
            accepting = true
        }
    }

    private fun stopLiveScanOnly() {
        pendingRestart?.let { main.removeCallbacks(it) }
        pendingRestart = null
        stopScan()
        worker.post { accepting = false }
        state = State.IDLE
    }

    private fun stopLive() {
        stopLiveScanOnly()
        ScanRepository.update { it.copy(status = RunStatus.IDLE, message = "Live scan stopped") }
        endService()
    }

    // =====================================================================================
    // Ticker (countdown, run timer, periodic UI refresh so the window count also decays)
    // =====================================================================================

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.post(ticker)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        when (state) {
            State.COUNTDOWN -> {
                if (now >= countdownEnd) {
                    val wait = ScanLimiter.delayNeeded(now)
                    if (wait > 0) {
                        countdownEnd = now + wait
                        ScanRepository.update {
                            it.copy(
                                secondsLeft = ceil(wait / 1000.0).toInt(),
                                message = "Waiting: Android allows only ~5 scan starts per 30 s (Risk R2)",
                            )
                        }
                    } else {
                        beginScanning(now)
                    }
                } else {
                    ScanRepository.update { it.copy(secondsLeft = ceil((countdownEnd - now) / 1000.0).toInt()) }
                }
            }

            State.RUNNING -> {
                if (now >= runEnd) {
                    finishRun()
                } else {
                    val elapsed = ((now - scanStartWall) / 1000).toInt()
                    ScanRepository.update {
                        val warn = if (elapsed >= 10 && it.totalPackets == 0L) {
                            "No packets after 10 s. Check Location is ON and permissions (R4), or wait 30 s between runs (R2)."
                        } else it.error
                        it.copy(
                            secondsLeft = ceil((runEnd - now) / 1000.0).toInt(),
                            elapsedSec = elapsed,
                            error = warn,
                        )
                    }
                    worker.post { pushUi(now, force = true) }
                }
            }

            State.LIVE -> {
                ScanRepository.update { it.copy(elapsedSec = ((now - scanStartWall) / 1000).toInt()) }
                worker.post { pushUi(now, force = true) }
            }

            else -> Unit
        }
    }

    // =====================================================================================
    // Bluetooth scanning
    // =====================================================================================

    @SuppressLint("MissingPermission")
    private fun startScan(mode: ScanModeOption): Boolean {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            ScanRepository.update { it.copy(error = "Bluetooth is OFF. Turn it on and try again.") }
            return false
        }
        val s = adapter.bluetoothLeScanner
        if (s == null) {
            ScanRepository.update { it.copy(error = "BLE scanner unavailable. Toggle Bluetooth off/on.") }
            return false
        }
        val builder = ScanSettings.Builder()
            .setScanMode(mode.androidMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)   // every packet, not just the first
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)                                         // deliver immediately, no batching
        if (adapter.isLeExtendedAdvertisingSupported) {
            builder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) // also catch BLE 5 devices
        }
        return try {
            s.startScan(null, builder.build(), scanCallback)           // null filters = see EVERYTHING
            ScanLimiter.record(System.currentTimeMillis())
            scanner = s
            scanning = true
            Log.d(Constants.LOG_TAG, "Scan started in ${mode.name}")
            true
        } catch (e: Exception) {
            Log.e(Constants.LOG_TAG, "startScan failed", e)
            ScanRepository.update { it.copy(error = "Could not start scan: ${e.message}") }
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "stopScan failed", e)
        }
        scanning = false
        Log.d(Constants.LOG_TAG, "Scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }

        override fun onScanFailed(errorCode: Int) {
            val why = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed (scanning too often? wait 30 s)"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error (toggle Bluetooth)"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                else -> "error"
            }
            Log.e(Constants.LOG_TAG, "Scan failed $errorCode")
            main.post {
                scanning = false
                ScanRepository.update { it.copy(error = "Scan failed: $why (code $errorCode)") }
            }
        }
    }

    private fun handle(r: ScanResult) {
        val now = System.currentTimeMillis()
        val mac = r.device?.address ?: return
        val rssi = r.rssi
        val beaconId = parseBeaconId(r)
        val tx = r.txPower
        val maker = Manufacturers.label(r.scanRecord, beaconId)
        // "Complete/Shortened Local Name" field of the advertisement (or scan response), if the device sends one.
        val advName = r.scanRecord?.deviceName?.trim().orEmpty()
        val device = r.device
        worker.post { onPacket(now, mac, rssi, beaconId, tx, maker, advName, device) }
    }

    /** Worker thread. */
    private fun onPacket(
        now: Long, mac: String, rssi: Int, beaconId: Int?, txPower: Int, maker: String,
        advName: String, device: BluetoothDevice,
    ) {
        if (!accepting) return
        val hashId = HashUtil.hash(mac)
        // The real MAC and name are kept in memory only, for the optional on-screen display.
        // The CSV gets the hash only.
        tracker.onSighting(hashId, rssi, beaconId, now, mac, maker, advName)
        if (tracker.claimNameLookup(hashId)) {
            cachedName(device)?.let { tracker.setName(hashId, it) }
        }
        logger?.let {
            try {
                it.logEvent(now, now - tracker.runStart, hashId, rssi, beaconId, txPower, maker)
            } catch (e: Exception) {
                Log.e(Constants.LOG_TAG, "CSV write failed", e)
                logger = null
                ScanRepository.update { s -> s.copy(error = "CSV write failed: ${e.message}") }
            }
        }
        pushUi(now, force = false)
    }

    /**
     * Name from Android's Bluetooth cache (devices this phone has paired with or discovered before).
     * Needs BLUETOOTH_CONNECT on Android 12+; returns null if not granted or unknown.
     */
    @SuppressLint("MissingPermission")
    private fun cachedName(device: BluetoothDevice): String? = try {
        device.name?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: SecurityException) {
        null
    }

    private fun parseBeaconId(r: ScanResult): Int? {
        val md = r.scanRecord?.getManufacturerSpecificData(Constants.MANUFACTURER_ID) ?: return null
        return if (md.size >= 3 && md[0] == Constants.TAG[0] && md[1] == Constants.TAG[1]) {
            md[2].toInt() and 0xFF
        } else null
    }

    /** Worker thread. Throttled to ~4 updates/s unless forced. */
    private fun pushUi(now: Long, force: Boolean) {
        if (!force && now - lastUiPush < Constants.UI_THROTTLE_MS) return
        lastUiPush = now
        val beacons = tracker.beaconStats().mapValues { (id, b) ->
            BeaconLive(id, b.lastRssi, now - b.lastSeen, b.n, b.firstElapsedMs)
        }
        val recent = tracker.recentDevices(8).map { DeviceLive(it.hashId, it.lastRssi, now - it.lastSeen, it.beaconId, it.mac, it.manufacturer, it.name) }
        val wc = tracker.windowCount(now)
        val tu = tracker.totalUnique()
        val tp = tracker.totalPackets
        ScanRepository.update {
            it.copy(windowCount = wc, totalUnique = tu, totalPackets = tp, beacons = beacons, recent = recent)
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BLE Crowd")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: Exception) {
        Log.e(Constants.LOG_TAG, "startForeground failed", e)
        ScanRepository.update { it.copy(status = RunStatus.IDLE, error = "Cannot start scanning service: ${e.message}") }
        false
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {
            // Notification permission denied: not important.
        }
    }

    private fun endService() {
        state = State.IDLE
        tickerRunning = false
        main.removeCallbacks(ticker)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun readBattery(): Battery {
        val bm = getSystemService(BatteryManager::class.java) ?: return Battery(null, null)
        fun valid(v: Int) = if (v == Int.MIN_VALUE || v == Int.MAX_VALUE) null else v
        return Battery(
            valid(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)),
            valid(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)),
        )
    }

    private fun vibrate() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
        } catch (_: Exception) {
        }
    }

    /** Keep only numeric ground truth (e.g. "3" or an averaged headcount "41.7"). */
    private fun normaliseNumber(s: String): String? {
        val d = s.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}

/**
 * Android blocks an app that starts BLE scans more than 5 times in 30 s (Risk R2) - silently, with no error.
 * We allow at most 4 starts per 30 s and delay otherwise.
 */
object ScanLimiter {
    private const val MAX_STARTS = 4
    private const val WINDOW_MS = 30_000L
    private const val MARGIN_MS = 500L
    private val starts = ArrayDeque<Long>()

    @Synchronized
    fun record(t: Long) {
        starts.addLast(t)
        while (starts.size > MAX_STARTS) starts.removeFirst()
    }

    @Synchronized
    fun delayNeeded(t: Long): Long {
        if (starts.size < MAX_STARTS) return 0
        return max(0L, starts.first() + WINDOW_MS + MARGIN_MS - t)
    }
}
