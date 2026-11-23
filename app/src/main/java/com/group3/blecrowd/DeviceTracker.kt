package com.group3.blecrowd

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

data class DeviceInfo(
    val hashId: String,
    val firstSeen: Long,
    var lastSeen: Long,
    var lastRssi: Int,
    val beaconId: Int?,          // 1, 2, 3 for our beacons; null for unknown devices
    var packets: Int = 1,
    val mac: String = "",        // in memory only, for the optional on-screen display; never logged to CSV
)

/** Running statistics for one ground-truth beacon (keyed by beaconId, not hash, so MAC rotation is harmless). */
class BeaconStats {
    var firstElapsedMs: Long = -1; private set
    var lastSeen: Long = 0; private set
    var lastRssi: Int = 0; private set
    var n: Int = 0; private set
    private var sum = 0.0
    private var sumSq = 0.0

    fun add(rssi: Int, elapsedMs: Long, now: Long) {
        if (firstElapsedMs < 0) firstElapsedMs = elapsedMs
        lastSeen = now
        lastRssi = rssi
        n++
        sum += rssi
        sumSq += rssi.toDouble() * rssi
    }

    val mean: Double get() = if (n > 0) sum / n else Double.NaN

    /** Sample standard deviation. */
    val std: Double get() = if (n > 1) sqrt(max(0.0, (sumSq - sum * sum / n) / (n - 1))) else Double.NaN
}

/** Counting logic. All mutation happens on the service's single worker thread. */
class DeviceTracker {
    private val devices = ConcurrentHashMap<String, DeviceInfo>()
    private val beacons = ConcurrentHashMap<Int, BeaconStats>()

    @Volatile var runStart = 0L; private set
    @Volatile var totalPackets = 0L; private set

    fun reset(now: Long = System.currentTimeMillis()) {
        devices.clear()
        beacons.clear()
        totalPackets = 0
        runStart = now
    }

    fun onSighting(hashId: String, rssi: Int, beaconId: Int?, now: Long, mac: String = "") {
        totalPackets++
        devices.compute(hashId) { _, d ->
            d?.apply { lastSeen = now; lastRssi = rssi; packets++ }
                ?: DeviceInfo(hashId, now, now, rssi, beaconId, mac = mac)
        }
        if (beaconId != null) {
            beacons.getOrPut(beaconId) { BeaconStats() }.add(rssi, now - runStart, now)
        }
    }

    fun totalUnique(): Int = devices.size

    fun windowCount(now: Long, windowMs: Long = Constants.WINDOW_MS): Int =
        devices.values.count { now - it.lastSeen <= windowMs }

    fun beaconStats(): Map<Int, BeaconStats> = beacons.toMap()

    /** Number of distinct ground-truth beacons (1..3) seen this run. */
    fun beaconsDetected(): Int = beacons.keys.count { it in 1..Constants.BEACON_COUNT }

    fun recentDevices(limit: Int): List<DeviceInfo> =
        devices.values.sortedByDescending { it.lastSeen }.take(limit).map { it.copy() }
}
