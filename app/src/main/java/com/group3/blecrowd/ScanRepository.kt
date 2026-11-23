package com.group3.blecrowd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RunStatus { IDLE, COUNTDOWN, RUNNING, LIVE }

data class BeaconLive(val id: Int, val rssi: Int, val ageMs: Long, val n: Int, val firstMs: Long)

data class DeviceLive(val hashId: String, val rssi: Int, val ageMs: Long, val beaconId: Int?, val mac: String = "", val manufacturer: String = "", val name: String = "",
)

data class LiveState(
    val status: RunStatus = RunStatus.IDLE,
    val runId: String = "",
    val phase: Phase = Phase.CONTROLLED,
    val mode: ScanModeOption = ScanModeOption.LOW_LATENCY,
    val placement: Placement = Placement.HAND,
    val secondsLeft: Int = 0,
    val elapsedSec: Int = 0,
    val windowCount: Int = 0,
    val totalUnique: Int = 0,
    val totalPackets: Long = 0,
    val beacons: Map<Int, BeaconLive> = emptyMap(),
    val recent: List<DeviceLive> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    /** Incremented every time a run is saved; the UI uses it to suggest the next run. */
    val completedRuns: Int = 0,
)

/** Shared live state between BleScanService and the Compose UI. */
object ScanRepository {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /** Editable during a run (field-test headcount is often known only at the end). Read when the run finishes. */
    val groundTruth = MutableStateFlow("3")
    val notes = MutableStateFlow("")

    /** UI-only toggle: show real MAC addresses + device names in "Recently seen". CSV files always contain hashes only. */
    val showRealMac = MutableStateFlow(false)

    fun update(transform: (LiveState) -> LiveState) = _state.update(transform)
}
