package com.group3.blecrowd

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Writes events_<runId>.csv (one row per received advertisement).
 * Buffered; flushed every ~2 s and on close. Only used from the service's worker thread.
 */
class CsvLogger(
    dir: File,
    private val runId: String,
    private val mode: ScanModeOption,
    private val placement: Placement,
) {
    val file = File(dir, "events_$runId.csv")
    private val writer = BufferedWriter(FileWriter(file), 64 * 1024)
    private var lastFlush = System.currentTimeMillis()

    init {
        writer.write(EVENTS_HEADER)
        writer.newLine()
    }

    fun logEvent(timestampMs: Long, elapsedMs: Long, hashId: String, rssi: Int, beaconId: Int?, txPower: Int) {
        writer.write(
            "$timestampMs,$elapsedMs,$runId,${mode.name},${placement.name},$hashId,$rssi,${beaconId ?: ""},$txPower"
        )
        writer.newLine()
        if (timestampMs - lastFlush >= Constants.FLUSH_INTERVAL_MS) {
            writer.flush()
            lastFlush = timestampMs
        }
    }

    fun close() {
        runCatching { writer.flush() }
        runCatching { writer.close() }
    }

    companion object {
        const val EVENTS_HEADER = "timestamp_ms,elapsed_ms,run_id,mode,placement,hash_id,rssi,beacon_id,tx_power"

        val SUMMARY_COLUMNS = listOf(
            "run_id", "phase", "start_time", "mode", "placement", "rep", "duration_s", "ground_truth",
            "total_unique", "window_count_end", "beacons_detected",
            "b1_first_ms", "b2_first_ms", "b3_first_ms",
            "b1_mean_rssi", "b1_std_rssi", "b1_n",
            "b2_mean_rssi", "b2_std_rssi", "b2_n",
            "b3_mean_rssi", "b3_std_rssi", "b3_n",
            "total_packets", "battery_pct_start", "battery_pct_end",
            "charge_uah_start", "charge_uah_end", "notes",
        )

        /** Appends one row to summary.csv (the header is written if the file is new). */
        fun appendSummary(dir: File, values: Map<String, Any?>) {
            val f = File(dir, Constants.SUMMARY_FILE)
            val isNew = !f.exists() || f.length() == 0L
            BufferedWriter(FileWriter(f, true)).use { w ->
                if (isNew) {
                    w.write(SUMMARY_COLUMNS.joinToString(","))
                    w.newLine()
                }
                w.write(SUMMARY_COLUMNS.joinToString(",") { escape(values[it]) })
                w.newLine()
            }
        }

        private const val QUOTE = '"'

        fun escape(v: Any?): String {
            val s = when (v) {
                null -> ""
                is Double -> if (v.isNaN()) "" else String.format(Locale.US, "%.2f", v)
                else -> v.toString()
            }
            val needsQuotes = s.any { it == ',' || it == QUOTE || it == '\n' || it == '\r' }
            if (!needsQuotes) return s
            val cleaned = s.replace('\r', ' ').replace('\n', ' ')
                .replace(QUOTE.toString(), QUOTE.toString() + QUOTE)
            return QUOTE + cleaned + QUOTE
        }
    }
}
