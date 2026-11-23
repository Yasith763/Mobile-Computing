package com.group3.blecrowd

import android.bluetooth.le.ScanSettings

object Constants {
    const val LOG_TAG = "BLECrowd"

    /** 0xFFFF = reserved "test" company ID in the Bluetooth spec. */
    const val MANUFACTURER_ID = 0xFFFF

    /** "G3" = Group 3 marker inside the manufacturer data. Payload = TAG + beaconId. */
    val TAG = byteArrayOf(0x47, 0x33)

    /** Sliding window used for the live "devices nearby" count. */
    const val WINDOW_MS = 30_000L

    const val DEFAULT_RUN_SECONDS = 60

    /** Countdown before a run starts, so the phone can be put in the bag. */
    const val START_DELAY_SECONDS = 5

    /** LOW_LATENCY can produce hundreds of callbacks/s; push to the UI at most ~4x per second. */
    const val UI_THROTTLE_MS = 250L

    const val FLUSH_INTERVAL_MS = 2_000L

    const val RUNS_DIR = "runs"
    const val SUMMARY_FILE = "summary.csv"

    const val BEACON_COUNT = 3
}

enum class ScanModeOption(val androidMode: Int, val label: String) {
    LOW_POWER(ScanSettings.SCAN_MODE_LOW_POWER, "Low Power"),
    LOW_LATENCY(ScanSettings.SCAN_MODE_LOW_LATENCY, "Low Latency"),
}

enum class Placement(val label: String) {
    HAND("Hand"),
    BAG("Bag"),
    FIELD("Field"),
}

enum class Phase(val prefix: String, val defaultSeconds: Int, val label: String) {
    CONTROLLED("R", 60, "Controlled"),
    FIELD("F", 300, "Field"),
    BATTERY("B", 600, "Battery"),
}
