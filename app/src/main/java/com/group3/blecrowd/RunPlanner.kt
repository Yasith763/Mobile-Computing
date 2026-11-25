package com.group3.blecrowd

import android.content.Context
import java.io.File

/**
 * Helpers for the experiment protocol:
 *  - where the CSV files live
 *  - suggesting the next run ID (R01.., F1.., B1..)
 *  - following the INTERLEAVED controlled order C1, C2, C3, C4, C1, ... (5 reps -> 20 runs)
 */
object RunPlanner {

    /** C1..C4 from the project plan. */
    val CONDITIONS = listOf(
        ScanModeOption.LOW_POWER to Placement.HAND,     // C1
        ScanModeOption.LOW_LATENCY to Placement.HAND,   // C2
        ScanModeOption.LOW_POWER to Placement.BAG,      // C3
        ScanModeOption.LOW_LATENCY to Placement.BAG,    // C4
    )
    const val CONTROLLED_TARGET = 20

    /** /sdcard/Android/data/com.group3.blecrowd/files/runs (no storage permission needed). */
    fun runsDir(context: Context): File {
        val dir = context.getExternalFilesDir(Constants.RUNS_DIR) ?: File(context.filesDir, Constants.RUNS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** (run_id, phase) of every saved run. run_id and phase are never quoted, so a simple split is safe. */
    fun savedRuns(context: Context): List<Pair<String, String>> {
        val f = File(runsDir(context), Constants.SUMMARY_FILE)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split(",", limit = 3)
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
    }

    fun countPhase(context: Context, phase: Phase): Int = savedRuns(context).count { it.second == phase.name }

    fun nextRunId(context: Context, phase: Phase): String {
        val maxN = savedRuns(context)
            .map { it.first }
            .filter { it.startsWith(phase.prefix) }
            .mapNotNull { id -> id.drop(phase.prefix.length).takeWhile { it.isDigit() }.toIntOrNull() }
            .maxOrNull() ?: 0
        val n = maxN + 1
        return if (phase == Phase.CONTROLLED) "%s%02d".format(phase.prefix, n) else "${phase.prefix}$n"
    }

    data class Suggestion(val mode: ScanModeOption, val placement: Placement, val rep: Int, val conditionIndex: Int)

    /** Next controlled condition when following the interleaved order. */
    fun nextControlled(context: Context): Suggestion {
        val done = countPhase(context, Phase.CONTROLLED)
        val idx = done % CONDITIONS.size
        val (mode, placement) = CONDITIONS[idx]
        return Suggestion(mode, placement, done / CONDITIONS.size + 1, idx)
    }

    /** If events_<id>.csv already exists (e.g. a re-run), append b, c, ... so nothing is overwritten. */
    fun uniqueRunId(dir: File, requested: String): String {
        val base = requested.trim().ifEmpty { "RUN" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (!File(dir, "events_$base.csv").exists()) return base
        var c = 'b'
        while (File(dir, "events_$base$c.csv").exists() && c < 'z') c++
        return "$base$c"
    }

    /**
     * Restarts a phase from run 1 WITHOUT deleting anything: the phase's rows and events files are moved to
     * runs/archive_<phase>_<timestamp>/ (rows go into that folder's own summary.csv). Other phases are untouched.
     * Returns the number of runs archived.
     */
    fun archivePhase(context: Context, phase: Phase): Int {
        val dir = runsDir(context)
        val summary = File(dir, Constants.SUMMARY_FILE)
        if (!summary.exists()) return 0
        val lines = summary.readLines()
        if (lines.isEmpty()) return 0
        val header = lines.first()
        val body = lines.drop(1).filter { it.isNotBlank() }
        fun phaseOf(line: String) = line.split(",", limit = 3).getOrNull(1)
        val (mine, keep) = body.partition { phaseOf(it) == phase.name }
        if (mine.isEmpty()) return 0

        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val archive = File(dir, "archive_${phase.name.lowercase()}_$stamp")
        if (!archive.mkdirs()) return 0
        File(archive, Constants.SUMMARY_FILE).writeText((listOf(header) + mine).joinToString("\n") + "\n")
        mine.mapNotNull { it.split(",", limit = 2).firstOrNull() }.forEach { id ->
            File(dir, "events_$id.csv").takeIf { it.exists() }?.renameTo(File(archive, "events_$id.csv"))
        }
        summary.writeText((listOf(header) + keep).joinToString("\n") + "\n")
        return mine.size
    }

    fun csvFiles(context: Context): List<File> =
        runsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()
}
