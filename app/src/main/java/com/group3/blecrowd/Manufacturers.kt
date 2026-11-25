package com.group3.blecrowd

import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid

/**
 * Identifies who made a BLE device from its advertisement.
 *
 * 1. Manufacturer Specific Data starts with a 16-bit Company ID assigned by the Bluetooth SIG
 *    (e.g. 0x004C = Apple). This is the most reliable hint.
 * 2. If there is none, some well-known 16-bit Service UUIDs also identify the vendor.
 *
 * For Apple devices we also decode the first payload byte (the "Continuity" message type), which
 * explains why one iPhone can appear as several devices: it broadcasts several message types.
 */
object Manufacturers {

    /** Common Bluetooth SIG company identifiers (subset of the official assigned-numbers list). */
    private val COMPANIES = mapOf(
        0x0002 to "Intel",
        0x0006 to "Microsoft",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x001D to "Qualcomm",
        0x0030 to "STMicroelectronics",
        0x0046 to "MediaTek",
        0x004C to "Apple",
        0x0059 to "Nordic Semiconductor",
        0x005D to "Realtek",
        0x0075 to "Samsung",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x00C4 to "LG Electronics",
        0x00E0 to "Google",
        0x012D to "Sony",
        0x0131 to "Cypress",
        0x0157 to "Huami (Amazfit)",
        0x0171 to "Amazon",
        0x027D to "Huawei",
        0x02E5 to "Espressif",
        0x038F to "Xiaomi",
        0x0499 to "Ruuvi",
        Constants.MANUFACTURER_ID to "Test ID 0xFFFF",
    )

    /** Well-known 16-bit service UUIDs that identify a vendor/feature when there is no manufacturer data. */
    private val SERVICES = mapOf(
        0xFE2C to "Google (Fast Pair)",
        0xFE9F to "Google",
        0xFEAA to "Google (Eddystone)",
        0xFD6F to "Exposure Notification",
    )

    /** Apple Continuity message types (first byte of Apple's manufacturer data). */
    private val APPLE_TYPES = mapOf(
        0x02 to "iBeacon",
        0x05 to "AirDrop",
        0x07 to "AirPods/Proximity Pairing",
        0x09 to "AirPlay",
        0x0C to "Handoff",
        0x0F to "Nearby Action",
        0x10 to "Nearby Info",
        0x12 to "Find My",
    )

    /** Returns a short label like "Apple · Nearby Info", "Samsung", "0x0A12", or "" if nothing identifies it. */
    fun label(record: ScanRecord?, beaconId: Int?): String {
        if (beaconId != null) return "Group 3 Beacon"
        if (record == null) return ""

        val md = record.manufacturerSpecificData
        if (md != null && md.size() > 0) {
            val id = md.keyAt(0)
            val name = COMPANIES[id] ?: return "0x%04X".format(id)
            if (id == 0x004C) {
                val payload = md.valueAt(0)
                val type = payload?.firstOrNull()?.toInt()?.and(0xFF)
                type?.let { APPLE_TYPES[it] }?.let { return "$name · $it" }
            }
            return name
        }

        record.serviceUuids?.forEach { uuid -> serviceLabel(uuid)?.let { return it } }
        record.serviceData?.keys?.forEach { uuid -> serviceLabel(uuid)?.let { return it } }
        return ""
    }

    /** Extracts the 16-bit short UUID from a standard Bluetooth base UUID (0000XXXX-0000-1000-8000-00805F9B34FB). */
    private fun serviceLabel(uuid: ParcelUuid): String? {
        val u = uuid.uuid
        if (u.leastSignificantBits != BASE_LSB) return null
        val short = ((u.mostSignificantBits ushr 32) and 0xFFFFFFFFL).toInt()
        return SERVICES[short]
    }

    private const val BASE_LSB = -0x7fffff7fa064cb05L // 0x800000805F9B34FB
}
