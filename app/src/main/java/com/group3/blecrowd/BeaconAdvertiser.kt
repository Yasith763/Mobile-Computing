package com.group3.blecrowd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beacon role (runs on the 3 x S6 Lite).
 * Advertises manufacturer data = 0xFFFF + "G3" + beaconId, so the scanner can identify ground-truth
 * devices even though Android randomises their MAC addresses.
 *
 * Process-wide singleton so advertising survives screen rotation / activity re-creation.
 */
object BeaconAdvertiser {
    private val _status = MutableStateFlow("Not advertising")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _activeId = MutableStateFlow<Int?>(null)
    val activeId: StateFlow<Int?> = _activeId.asStateFlow()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(context: Context, beaconId: Int) {
        stop()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            _status.value = "This device has no Bluetooth adapter"
            return
        }
        if (!adapter.isEnabled) {
            _status.value = "Bluetooth is OFF. Turn it on first."
            return
        }
        if (!adapter.isMultipleAdvertisementSupported) {
            _status.value = "This device does not support BLE advertising (see Risk R3 in the plan)"
            return
        }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            _status.value = "BLE advertiser unavailable (is Bluetooth on?)"
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // ~10 packets/s
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)   // SAME on all 3 beacons
            .setConnectable(false)
            .setTimeout(0)                                                   // until stopped
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(Constants.MANUFACTURER_ID, Constants.TAG + byteArrayOf(beaconId.toByte()))
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                _activeId.value = beaconId
                _status.value = "Advertising as Beacon $beaconId"
                Log.d(Constants.LOG_TAG, "Advertising as beacon $beaconId")
            }

            override fun onStartFailure(errorCode: Int) {
                _activeId.value = null
                _status.value = "Advertise failed: ${describe(errorCode)} (code $errorCode)"
                Log.w(Constants.LOG_TAG, "Advertise failed $errorCode")
            }
        }
        try {
            _status.value = "Starting..."
            adv.startAdvertising(settings, data, cb)
            advertiser = adv
            callback = cb
        } catch (e: SecurityException) {
            _status.value = "Missing Bluetooth permission: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        try {
            advertiser?.stopAdvertising(cb)
        } catch (e: Exception) {
            Log.w(Constants.LOG_TAG, "stopAdvertising failed", e)
        }
        callback = null
        advertiser = null
        _activeId.value = null
        _status.value = "Not advertising"
    }

    private fun describe(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers (restart Bluetooth)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error (toggle Bluetooth off/on)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported on this device"
        else -> "unknown error"
    }
}
