# BLE Crowd Sensing: Placement and Scan Mode Study
### CS4473 Mobile Computing, Group 3: 3-Day Implementation Plan

**Members:** 220105A - De Silva W.G.K.G (**Member A**) · 220236B - Imalka T.G.Y (**Member B**)
**Option:** A, Counting Nearby Bluetooth Devices

---

## 0. TL;DR (read this first)

We are building **one Android app (Kotlin)** that has **two roles**:

| Role | Runs on | What it does |
|---|---|---|
| **Scanner** | Samsung S9 FE (main phone) | Scans BLE, hashes MAC addresses, shows a live unique-device count, logs to CSV |
| **Beacon (Advertiser)** | 3 × Samsung S6 Lite | Broadcasts a known BLE advertisement tagged with an ID (1, 2, 3) so the scanner can always recognise the **ground-truth devices** |

Then we:
1. Run **20 controlled runs**: 2 scan modes × 2 placements × 5 repetitions.
2. Run **2 field runs** in the university canteen: off-peak morning and peak lunch.
3. Analyse the CSVs with a **Python script** to produce charts (deliverable D4).

> **Why the same app on every device?** We only install and debug one project. The beacon role also uses our own **manufacturer-data tag**. That matters because Android randomises MAC addresses, so we can't identify our 3 beacons by MAC. We identify them by the tag in their advertisement payload.

### 3-day overview

| Day | Goal | Output |
|---|---|---|
| **Day 1** | Build the working app: scanner, beacon mode, hashing, live count, mode toggle | App running on all 4 devices (D1, D2) |
| **Day 2** | Add CSV logging and run control, then run all 20 controlled experiments (plus optional battery runs) | `runs/` CSV folder (D3, controlled part) |
| **Day 3** | Canteen field test (morning and lunch), Python analysis, charts, demo rehearsal | Field CSVs, charts (D3, D4), demo-ready |

> ⚠️ **Today is Sunday 22 Nov 2026.** The canteen test needs a normal **weekday** with real crowds. If Day 3 isn't a campus day, move the field test to the first weekday you can (see §9 Risks).

---

## 1. Key Concepts (for anyone new to BLE)

| Term | Plain-English meaning |
|---|---|
| **BLE (Bluetooth Low Energy)** | Low-power Bluetooth. Phones, watches, earbuds and laptops constantly send small "I'm here" packets called **advertisements**. |
| **Advertisement** | A tiny broadcast packet (≤ 31 bytes for legacy BLE) containing a MAC address and optional data. Anyone can receive it without pairing. |
| **Scanning** | Listening for advertisements. Android does this with `BluetoothLeScanner`. |
| **RSSI** | *Received Signal Strength Indicator*, in dBm and always negative. **-40 = very close/strong**, **-90 = far/weak**. Bodies, bags and walls lower it. |
| **Scan mode** | How aggressively Android listens. **LOW_POWER** listens ~0.5 s out of every ~5 s (≈10% duty cycle). **LOW_LATENCY** listens continuously (100% duty cycle). This is our main independent variable. |
| **MAC randomisation** | Modern phones change their BLE MAC address every ~15 minutes for privacy. One phone can therefore look like several "devices" over a long scan. |
| **Hashing** | We never store real MACs. We store `SHA-256(salt + MAC)`, which gives a stable ID within a session but can't be reversed. This covers privacy (D1). |
| **Sliding window count** | "Number of distinct hashed IDs seen in the last *W* seconds" (we use W = 30 s). This limits over-counting from MAC rotation and from devices that have left. |
| **Ground truth** | The real answer we compare against: **3 beacons** in the controlled tests, and a **manual headcount** in the canteen. |

---

## 2. Hardware and Software Requirements

### Hardware
- **Scanner:** Samsung Galaxy S9 FE (Android 13/14). Primary device.
- **Beacons:** 3 × Samsung Galaxy S6 Lite (Android 10+, API 29+).
- USB cables, plus chargers for all 4 devices (charge everything to 100% before experiments).
- A **backpack** (always the same one, with the same contents, for consistency).
- A **measuring tape** (or count floor tiles) to fix beacon distances.

### Software
| Tool | Version | Purpose |
|---|---|---|
| Android Studio | Latest stable (Ladybug or newer) | Build the app |
| Kotlin | Bundled with Android Studio | Language |
| Jetpack Compose + Material 3 | Default in the "Empty Activity" template | UI |
| Python | 3.10+ | Analysis |
| `pandas`, `matplotlib`, `numpy` | `pip install pandas matplotlib numpy` | Charts |
| `adb` | Comes with Android Studio (platform-tools) | Pull CSV files from the phone |

### Project settings
- `minSdk = 29` (Android 10, required by the S6 Lite)
- `targetSdk = 35`, `compileSdk = 35`
- Package name: `com.group3.blecrowd`

### Phone setup (do on **all 4 devices**)
1. Settings → About phone → Software information → tap **Build number** 7 times to unlock **Developer options**.
2. Developer options → enable **USB debugging**.
3. Turn **Bluetooth ON** and **Location ON** (location is required for BLE scan results on many Samsung builds).
4. Settings → Battery → set our app to **Unrestricted** after install, so Samsung doesn't kill the scan.

---

## 3. App Architecture

```
┌──────────────────────────────── MainActivity (Compose UI) ───────────────────────────────┐
│  Role switch: [Scanner | Beacon]                                                         │
│  Scanner screen:  Mode toggle [Low Power | Low Latency]   Placement [Hand | Bag | Field] │
│                   Duration [60 s]   Ground-truth count [__]   [START RUN]  [EXPORT]      │
│                   LIVE: Window count (30 s) · Total unique · Beacons seen 1✔ 2✔ 3✖ + RSSI│
│  Beacon screen:   Beacon ID [1|2|3]   [START ADVERTISING]                                │
└───────────────────────────────▲──────────────────────────────────────────────────────────┘
                                │ observes StateFlow
                    ┌───────────┴───────────┐
                    │  ScanRepository       │  (singleton object: shared live state)
                    └───────────▲───────────┘
                                │ updates
┌───────────────────────────────┴─────────────────────────────────────┐
│ BleScanService (Foreground Service)                                 │
│   ├── BluetoothLeScanner + ScanSettings(mode)                       │
│   ├── HashUtil      → SHA-256(salt + MAC) → 12-hex-char ID          │
│   ├── DeviceTracker → map<hashId, lastSeen/RSSI/beaconId>           │
│   └── CsvLogger     → events_<runId>.csv  +  summary.csv            │
└─────────────────────────────────────────────────────────────────────┘
BeaconAdvertiser (used in Beacon role) → BluetoothLeAdvertiser
```

### File structure
```
app/src/main/
├── AndroidManifest.xml
└── java/com/group3/blecrowd/
    ├── MainActivity.kt        # UI + permission requests
    ├── ScanRepository.kt      # Shared state (StateFlow) between service and UI
    ├── BleScanService.kt      # Foreground service: scanning + run timer
    ├── BeaconAdvertiser.kt    # Advertiser role
    ├── DeviceTracker.kt       # Counting logic
    ├── HashUtil.kt            # MAC hashing
    ├── CsvLogger.kt           # CSV writing
    └── Constants.kt           # Manufacturer ID, tag bytes, window size
```

---

## 4. DAY 1: Build the Core App

### Task split
| Member A | Member B |
|---|---|
| Project setup, manifest, permissions, `BleScanService`, `DeviceTracker`, `HashUtil` | `BeaconAdvertiser`, Compose UI, `ScanRepository`, testing on the S6 Lites |

### Step 1.1: Create the project (≈15 min)
Android Studio → **New Project → Empty Activity (Compose)** → name `BLECrowd`, package `com.group3.blecrowd`, minimum SDK **API 29**, language Kotlin.

### Step 1.2: Manifest permissions (≈15 min)

`AndroidManifest.xml`:
```xml
<!-- Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<!-- Location: needed on Android ≤11 for scanning; we also keep it on 12+ so beacon results are NOT filtered -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

<application ...>
    <activity android:name=".MainActivity" android:exported="true" ...> ... </activity>
    <service
        android:name=".BleScanService"
        android:exported="false"
        android:foregroundServiceType="connectedDevice" />
</application>
```

> **Important:** do **NOT** add `android:usesPermissionFlags="neverForLocation"` to `BLUETOOTH_SCAN`. With that flag, Android may **filter out beacon advertisements**, which would break our ground truth.

**Runtime permission request** (in `MainActivity`):
```kotlin
val perms = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
    if (result.values.all { it }) { /* ready */ } else { /* show "permissions required" */ }
}
launcher.launch(perms)
```

### Step 1.3: Constants (≈5 min)
```kotlin
object Constants {
    const val MANUFACTURER_ID = 0xFFFF          // Reserved "test" company ID in the Bluetooth spec
    val TAG = byteArrayOf(0x47, 0x33)           // "G3" = Group 3 marker
    const val WINDOW_MS = 30_000L               // Sliding window for the live count
    const val DEFAULT_RUN_SECONDS = 60
    const val START_DELAY_SECONDS = 5           // Time to put the phone in the bag
}
```

### Step 1.4: Beacon advertiser (Member B, ≈45 min)

Each S6 Lite advertises: **manufacturer data = `0xFFFF` + `"G3"` + beaconId**. That's 10 bytes total, well within the 31-byte limit.

```kotlin
class BeaconAdvertiser(context: Context) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(beaconId: Int, onResult: (String) -> Unit) {
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return onResult("This device does not support BLE advertising")

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // ~10 packets/s
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)   // keep the SAME on all 3
            .setConnectable(false)
            .setTimeout(0)                                                   // advertise until stopped
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)                                     // saves bytes + privacy
            .addManufacturerData(Constants.MANUFACTURER_ID,
                Constants.TAG + byteArrayOf(beaconId.toByte()))
            .build()

        callback = object : AdvertiseCallback() {
            override fun onStartSuccess(s: AdvertiseSettings) = onResult("Advertising as Beacon $beaconId")
            override fun onStartFailure(code: Int) = onResult("Advertise failed, code $code")
        }
        advertiser.startAdvertising(settings, data, callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() { callback?.let { adapter.bluetoothLeAdvertiser?.stopAdvertising(it) } }
}
```

✅ **Check:** install on an S6 Lite and press Start. You should see "Advertising as Beacon 1". If you get `ADVERTISE_FAILED_FEATURE_UNSUPPORTED` (code 5), see §9 Risks.

Also add `window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)` so beacons don't sleep during experiments.

### Step 1.5: Hashing (Member A, ≈15 min)
```kotlin
object HashUtil {
    // New random salt each app session, so the same device gets a different hash in different sessions
    // (stronger privacy). Within one session the hash stays stable, so counting still works.
    val sessionSalt: String = java.util.UUID.randomUUID().toString()

    fun hash(mac: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((sessionSalt + mac).toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }  // 12 hex characters
    }
}
```
> 12 hex characters = 48 bits, so collisions are practically impossible for a few hundred devices.

### Step 1.6: Device tracker / counting logic (Member A, ≈30 min)
```kotlin
data class DeviceInfo(
    val hashId: String,
    val firstSeen: Long,
    var lastSeen: Long,
    var lastRssi: Int,
    val beaconId: Int?,          // 1, 2, 3 for our beacons, null for unknown devices
    var packets: Int = 1
)

class DeviceTracker {
    private val devices = java.util.concurrent.ConcurrentHashMap<String, DeviceInfo>()
    var runStart = 0L; private set

    fun reset() { devices.clear(); runStart = System.currentTimeMillis() }

    fun onSighting(hashId: String, rssi: Int, beaconId: Int?, now: Long) {
        devices.compute(hashId) { _, d ->
            d?.apply { lastSeen = now; lastRssi = rssi; packets++ }
                ?: DeviceInfo(hashId, now, now, rssi, beaconId)
        }
    }

    fun totalUnique() = devices.size
    fun windowCount(now: Long, windowMs: Long = Constants.WINDOW_MS) =
        devices.values.count { now - it.lastSeen <= windowMs }
    fun beacons() = devices.values.filter { it.beaconId != null }.associateBy { it.beaconId!! }
    fun totalPackets() = devices.values.sumOf { it.packets }
}
```

> **Note on beacon dedup:** if a beacon's MAC rotates mid-run, it would appear as two hash IDs with the same `beaconId`. Count beacons by **distinct `beaconId`**, not by hash ID (that's what `beacons()` does).

### Step 1.7: Scanner service (Member A, ≈1.5 h)

Key scan settings:
```kotlin
@SuppressLint("MissingPermission")
private fun startScan(mode: Int) {   // mode = ScanSettings.SCAN_MODE_LOW_POWER or SCAN_MODE_LOW_LATENCY
    val builder = ScanSettings.Builder()
        .setScanMode(mode)
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)   // every packet, not just the first
        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
        .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
        .setReportDelay(0)                                         // deliver immediately, no batching
    if (adapter.isLeExtendedAdvertisingSupported) {
        builder.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) // also catch newer BLE 5 devices
    }
    scanner.startScan(null, builder.build(), scanCallback)         // null filters = see EVERYTHING
}

private val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val now = System.currentTimeMillis()
        val hashId = HashUtil.hash(result.device.address)
        val beaconId = parseBeaconId(result)
        tracker.onSighting(hashId, result.rssi, beaconId, now)
        logger?.logEvent(now, now - tracker.runStart, hashId, result.rssi, beaconId, result.txPower)
        ScanRepository.update(tracker, now)   // pushes the new count to the UI
    }
    override fun onScanFailed(errorCode: Int) { ScanRepository.setError("Scan failed: $errorCode") }
}

private fun parseBeaconId(r: ScanResult): Int? {
    val md = r.scanRecord?.getManufacturerSpecificData(Constants.MANUFACTURER_ID) ?: return null
    return if (md.size >= 3 && md[0] == Constants.TAG[0] && md[1] == Constants.TAG[1]) md[2].toInt() else null
}
```

**Foreground service skeleton:**
```kotlin
class BleScanService : Service() {
    // onStartCommand: read extras (mode, placement, duration, groundTruth),
    //   1. create notification channel + call ServiceCompat.startForeground(
    //        this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    //   2. wait START_DELAY_SECONDS (countdown shown in UI, so you can put the phone in the bag)
    //   3. tracker.reset(); logger = CsvLogger(...); read battery; startScan(mode)
    //   4. after `duration` seconds: stopScan(); write summary row; vibrate; stopSelf()
    // Use a Handler(Looper.getMainLooper()) or a CoroutineScope for the timers.
    override fun onBind(intent: Intent?) = null
}
```

**Live UI updates** (`ScanRepository`): a singleton holding a `MutableStateFlow<LiveState>` with `windowCount`, `totalUnique`, `beacons` (id → last RSSI), `secondsLeft`, `status`. Throttle UI updates to ~4 per second, because LOW_LATENCY can produce hundreds of callbacks per second.

### Step 1.8: Compose UI (Member B, ≈1.5 h)
Minimum screen elements:
- **Role selector:** Scanner / Beacon (use a different screen for each)
- **Scanner:**
  - **Scan mode toggle:** `SegmentedButton` or `Switch`, Low Power ↔ Low Latency (**D2**)
  - **Placement selector:** HAND / BAG / FIELD
  - **Duration:** text field, default 60 s
  - **Ground-truth count:** number field (3 for controlled runs, headcount for field runs)
  - **START RUN** button (shows a 5-second countdown) and **STOP** button
  - **Big live number:** "Devices nearby (last 30 s): **17**" (**D1**)
  - Smaller line: "Total unique this run: 23"
  - **Beacon panel:** `B1 ✔ -58 dBm  B2 ✔ -71 dBm  B3 ✖`
  - **EXPORT / SHARE CSV** button
- **Beacon:** ID picker (1/2/3) plus Start/Stop advertising
- Add `FLAG_KEEP_SCREEN_ON` (see Risk R1, it's critical)

### ✅ End-of-Day-1 checkpoint
- [ ] The 3 S6 Lites advertise as Beacon 1, 2, 3
- [ ] The S9 FE shows all 3 beacons with RSSI, plus other nearby devices
- [ ] The live count updates; toggling the scan mode visibly changes the update speed
- [ ] No real MAC address appears anywhere on screen or in logs (only 12-char hashes)

---

## 5. DAY 2: Logging, Run Control and Controlled Experiments

### Task split
| Member A | Member B |
|---|---|
| `CsvLogger`, summary stats, battery reading, export | Prepare the test room, measure positions, run sheet, then operate the experiments together |

### Step 2.1: CSV format (≈1.5 h coding)

Files are saved to `/sdcard/Android/data/com.group3.blecrowd/files/runs/`, which needs no storage permission.

**File 1: `events_<runId>.csv`** (one row per received advertisement)
```
timestamp_ms,elapsed_ms,run_id,mode,placement,hash_id,rssi,beacon_id,tx_power
1732612345123,1520,R07,LOW_LATENCY,BAG,a91f3c0be2d4,-74,2,127
1732612345180,1577,R07,LOW_LATENCY,BAG,5b20e1fa9c33,-88,,127
```

**File 2: `summary.csv`** (one row per run, appended)
```
run_id,phase,start_time,mode,placement,rep,duration_s,ground_truth,
total_unique,window_count_end,beacons_detected,
b1_first_ms,b2_first_ms,b3_first_ms,
b1_mean_rssi,b1_std_rssi,b1_n,b2_mean_rssi,b2_std_rssi,b2_n,b3_mean_rssi,b3_std_rssi,b3_n,
total_packets,battery_pct_start,battery_pct_end,charge_uah_start,charge_uah_end,notes
```

| Column | Meaning |
|---|---|
| `phase` | `CONTROLLED`, `FIELD` or `BATTERY` |
| `beacons_detected` | 0–3: how many ground-truth beacons were seen. **This is the core accuracy metric** |
| `bX_first_ms` | Time until beacon X was first seen (*time-to-detect*). Shows the LOW_POWER vs LOW_LATENCY difference |
| `bX_mean_rssi / std` | RSSI level and **stability** (std = how much it fluctuates) |
| `total_packets` | Advertisements received, a proxy for scan intensity |
| `charge_uah_*` | Battery charge counter in µAh (much finer than %) |

**Battery reading:**
```kotlin
val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
```

**Logger tips:** use a `BufferedWriter` and flush every ~2 s and at the end. Never open/close the file per packet (too slow in LOW_LATENCY).

**Export:** either
- `adb pull /sdcard/Android/data/com.group3.blecrowd/files/runs/ ./data/` (easiest), **or**
- a Share button using `FileProvider` + `Intent.ACTION_SEND_MULTIPLE` (to Google Drive or email).

### Step 2.2: Experiment design

**Why 5 repetitions instead of 3?** The proposal says 3 repetitions *and* ≥20 controlled runs (D3). 2 modes × 2 placements × 3 reps is only 12 runs, so we do **5 reps** to get 2 × 2 × 5 = **20 runs**. This also gives better standard deviations.

| Condition | Mode | Placement | Reps | Run IDs |
|---|---|---|---|---|
| C1 | LOW_POWER | HAND | 5 | R01–R05 |
| C2 | LOW_LATENCY | HAND | 5 | R06–R10 |
| C3 | LOW_POWER | BAG | 5 | R11–R15 |
| C4 | LOW_LATENCY | BAG | 5 | R16–R20 |

> **Better practice:** **interleave** the order (C1, C2, C3, C4, C1, C2, …) instead of doing all C1 runs first. That way, changes in the room over time (people walking in) affect all conditions equally. Record the actual order in `notes`.

### Step 2.3: Physical setup

```
          Beacon 1 (2 m)
               ●
               │
   Beacon 3 ●──┼──● Beacon 2
   (6 m)       │    (4 m)
               │
          [ SCANNER ]   ← person standing still, phone held at chest height
```
- Place the beacons at **different distances (2 m, 4 m, 6 m)** so the backpack effect shows up: the far beacon is most likely to drop out. Put them on a table or chair at ~1 m height.
- **Same room, same positions, same person, same backpack** for every run. Mark positions with tape.
- **HAND:** phone held at chest height, screen facing up, standing at the marked spot.
- **BAG:** phone inside the **closed** backpack, worn on the back, standing at the same spot and facing the same direction.
- Beacons: same TX power, screen-on, plugged in if possible.

### Step 2.4: Running protocol (per run, ≈1.5 min each, ≈45–60 min total)
1. Set the mode and placement in the app and enter ground truth = 3. Duration = **60 s**.
2. Press **START RUN**. During the 5 s countdown, put the phone in position (hand or bag).
3. Stand still and don't talk on the phone. The phone **vibrates** when the run ends.
4. Wait **≥ 10 s** before the next run (see Risk R2: Android blocks >5 scan starts per 30 s).
5. Tick the run on a paper run sheet: run ID, time, condition, anything unusual (e.g. "a group walked past").

### Step 2.5 (optional but recommended): Battery runs (≈25 min)
Our expectation mentions battery cost. 60 s runs are too short to measure it, so do **2 extra runs of 10 minutes each** (one per mode, phase = `BATTERY`), with the same screen brightness and other apps closed. Compare `charge_uah_start - charge_uah_end`.

### ✅ End-of-Day-2 checkpoint
- [ ] 20 rows in `summary.csv` with phase = CONTROLLED, plus 20 `events_*.csv` files
- [ ] Files copied to a laptop **and** backed up (Google Drive / Git)
- [ ] Quick sanity check: did HAND runs usually detect 3/3 beacons? Is BAG RSSI lower?

---

## 6. DAY 3: Field Test, Analysis and Demo Prep

### Step 3.1: Canteen field test (our twist)

| Run | Time | Duration | Mode | Placement |
|---|---|---|---|---|
| F1 | **Off-peak morning** (~9:00–10:00) | 5 min (300 s) | LOW_LATENCY | FIELD (in hand, seated at a central table) |
| F2 | **Peak lunch** (~12:15–13:00) | 5 min (300 s) | LOW_LATENCY | FIELD (same table, same position) |

> Optional extra runs if time allows: repeat each period in LOW_POWER, or do a BAG run, to see whether the controlled findings hold in a real crowd.

**Manual headcount protocol (ground truth):**
- Member B counts the people in a **defined area** (e.g. "the main seating hall"; decide this beforehand and note it) at **t = 0, 2.5 and 5 min**.
- Use the **average of the 3 counts** as ground truth and type it into the app (or the run sheet) after the run.
- Write down the notes too: rough number of laptops visible, whether there's a wall/kitchen nearby, etc.

**Ethics/privacy note for the report:** only salted hashes are stored, no names/photos/MACs are recorded, the salt changes each session, and the data is used for aggregate counting only.

**Metric:** `BLE ratio = window_count / headcount`. We **don't expect 1.0**: some people have Bluetooth off, others carry 2–3 devices (phone + watch + earbuds), and laptops/vending machines also advertise. The interesting question is whether the ratio is **stable** between off-peak and peak. If it is, raw BLE counts are a usable crowd-density proxy after calibration.

### Step 3.2: Python analysis (≈2–3 h, Member A)

Folder:
```
analysis/
├── data/            ← summary.csv + events_*.csv from the phone
├── analyze.py
└── charts/          ← generated PNGs
```

`analyze.py` (starter):
```python
import pandas as pd, numpy as np, matplotlib.pyplot as plt, glob, os
os.makedirs("charts", exist_ok=True)

s = pd.read_csv("data/summary.csv")
ctrl = s[s.phase == "CONTROLLED"]

# ---------- Table: mean ± std per condition ----------
table = ctrl.groupby(["mode", "placement"]).agg(
    beacons_mean=("beacons_detected", "mean"), beacons_std=("beacons_detected", "std"),
    unique_mean=("total_unique", "mean"),      unique_std=("total_unique", "std"),
    packets_mean=("total_packets", "mean"),
).round(2)
print(table); table.to_csv("charts/summary_table.csv")

# ---------- Chart 1: Beacon detection accuracy (bar + error bars) ----------
g = ctrl.groupby(["placement", "mode"])["beacons_detected"].agg(["mean", "std"]).unstack()
g["mean"].plot(kind="bar", yerr=g["std"], capsize=4)
plt.axhline(3, ls="--", c="gray", label="Ground truth (3)")
plt.ylabel("Beacons detected (of 3)"); plt.title("Detection accuracy by placement & scan mode")
plt.legend(); plt.tight_layout(); plt.savefig("charts/1_detection_accuracy.png", dpi=200); plt.clf()

# ---------- Chart 2: RSSI drop-off, hand vs bag, per beacon (D4) ----------
ev = pd.concat(pd.read_csv(f) for f in glob.glob("data/events_*.csv"))
ev = ev.merge(ctrl[["run_id"]], on="run_id")                  # controlled runs only
bev = ev.dropna(subset=["beacon_id"])
data, labels = [], []
for b in [1, 2, 3]:
    for p in ["HAND", "BAG"]:
        data.append(bev[(bev.beacon_id == b) & (bev.placement == p)].rssi)
        labels.append(f"B{b}\n{p}")
plt.boxplot(data, labels=labels)
plt.ylabel("RSSI (dBm)"); plt.title("RSSI drop-off: line-of-sight (hand) vs backpack")
plt.tight_layout(); plt.savefig("charts/2_rssi_dropoff.png", dpi=200); plt.clf()

# ---------- Chart 3: Time-to-first-detection per mode ----------
t = ctrl.melt(id_vars=["mode", "placement"], value_vars=["b1_first_ms", "b2_first_ms", "b3_first_ms"],
              value_name="ms").dropna()
t.groupby(["mode", "placement"])["ms"].mean().div(1000).unstack().plot(kind="bar")
plt.ylabel("Seconds until first detection"); plt.title("Detection latency: Low Power vs Low Latency")
plt.tight_layout(); plt.savefig("charts/3_time_to_detect.png", dpi=200); plt.clf()

# ---------- Chart 4: RSSI stability over time (one example run per condition) ----------
for (m, p), grp in bev[bev.beacon_id == 2].groupby(["mode", "placement"]):
    r = grp[grp.run_id == grp.run_id.iloc[0]]
    plt.plot(r.elapsed_ms / 1000, r.rssi.rolling(5, min_periods=1).mean(), label=f"{m}/{p}")
plt.xlabel("Time (s)"); plt.ylabel("RSSI (dBm), Beacon 2"); plt.legend()
plt.title("RSSI stability over a run"); plt.tight_layout()
plt.savefig("charts/4_rssi_timeseries.png", dpi=200); plt.clf()

# ---------- Chart 5: Field test, BLE count vs headcount ----------
f = s[s.phase == "FIELD"].copy()
x = np.arange(len(f))
plt.bar(x - 0.2, f.window_count_end, 0.4, label="BLE count (30 s window)")
plt.bar(x + 0.2, f.ground_truth, 0.4, label="Manual headcount")
plt.xticks(x, f.notes.fillna(f.run_id)); plt.ylabel("Count"); plt.legend()
plt.title("Canteen: BLE count vs people"); plt.tight_layout()
plt.savefig("charts/5_field_test.png", dpi=200)
print("Field BLE/headcount ratio:\n", (f.window_count_end / f.ground_truth).round(2))
```
Run it with `cd analysis && python analyze.py`.

**Statistics to report:**
- Mean ± SD of `beacons_detected`, `total_unique` and beacon RSSI for each of the 4 conditions.
- **RSSI attenuation from the bag** = mean RSSI(HAND) − mean RSSI(BAG), per beacon (expect roughly 5–15 dB).
- **RSSI stability** = SD of RSSI, compared across placements and modes.
- **Detection latency** = mean time-to-first-detect, LOW_POWER vs LOW_LATENCY.
- (Optional) A **t-test** (`scipy.stats.ttest_ind`) on beacon RSSI, HAND vs BAG.
- Battery drain per mode (from the BATTERY runs).
- Field: BLE count, headcount and ratio for off-peak vs peak.

### Step 3.3: Demo preparation (Member B, ≈1.5 h, in parallel with the analysis)

**Demo script (~5 min):**
1. Show the 3 S6 Lites in Beacon mode (IDs 1, 2, 3).
2. On the S9 FE: start scanning. The live count and the three beacon ticks appear (**D1**). Point out the hashed IDs: "no real MACs are stored".
3. Flip the **Low Power ↔ Low Latency** toggle and show how much faster the count fills up (**D2**).
4. Put the phone in a bag for a few seconds and show the RSSI dropping on the beacon panel.
5. Open the CSV folder / laptop: 20 controlled + 2 field runs (**D3**).
6. Show the charts and the key findings (**D4**).

**Final deliverables checklist:**
- [ ] **D1:** App with live unique count + hashing
- [ ] **D2:** Scan mode toggle in the UI
- [ ] **D3:** `data/` folder with `summary.csv` (≥20 CONTROLLED + 2 FIELD rows) and all `events_*.csv` files, zipped
- [ ] **D4:** Charts (at minimum `1_detection_accuracy.png` and `2_rssi_dropoff.png`)
- [ ] APK file (`Build → Build APK`) as a backup in case the laptop or Android Studio fails during the demo
- [ ] Short results summary / slides

---

## 7. Detailed Hour-by-Hour Schedule

### Day 1: Build
| Time | Member A | Member B |
|---|---|---|
| 09:00–09:30 | Create project, push to GitHub, enable dev mode on the S9 FE | Enable dev mode on the 3 S6 Lites, charge all devices |
| 09:30–11:00 | Manifest, permissions, `Constants`, `HashUtil`, `DeviceTracker` | `BeaconAdvertiser` + basic Beacon screen, test on an S6 Lite |
| 11:00–13:00 | `BleScanService` (scan + callback + foreground notification) | `ScanRepository` + Scanner UI (toggle, live count, beacon panel) |
| 13:00–14:00 | *Lunch* | *Lunch* |
| 14:00–16:00 | Wire the service to the UI, run timer, countdown, vibration | Role switch, keep-screen-on, polish the UI |
| 16:00–18:00 | **Together:** end-to-end test with all 4 devices, fix bugs | |
| Evening | Buffer for bugs | Buffer for bugs |

### Day 2: Data
| Time | Member A | Member B |
|---|---|---|
| 09:00–11:00 | `CsvLogger`, summary stats, battery reading | Choose the room, measure and tape positions, print the run sheet |
| 11:00–12:00 | Export (adb/share), dry-run 2 test runs, check the CSVs open correctly in Excel | Help test; confirm beacon IDs on screen |
| 12:00–13:00 | *Lunch* | *Lunch* |
| 13:00–14:30 | **Together:** 20 controlled runs (interleaved order) | |
| 14:30–15:00 | Optional: 2 × 10-min battery runs | |
| 15:00–16:00 | Copy the data and back it up; start `analyze.py` with the real data | Write the method section (setup, distances, protocol) |
| Evening | Buffer / re-run any failed runs | |

### Day 3: Field and Finish
| Time | Member A | Member B |
|---|---|---|
| 09:00–09:30 | **F1 off-peak canteen run** (operate the phone) | Headcount at 0 / 2.5 / 5 min |
| 09:30–12:00 | Finish `analyze.py`, generate charts | Demo script, slides/report: intro, method |
| 12:15–13:00 | **F2 peak lunch run** | Headcount |
| 13:00–15:00 | Add the field data, final charts, statistics | Results and discussion write-up |
| 15:00–17:00 | **Together:** build the APK, zip the data, rehearse the demo twice | |

---

## 8. What to Write in the Results/Discussion (expected findings to verify)

| Hypothesis (from the proposal) | How we test it | Chart |
|---|---|---|
| LOW_LATENCY detects devices faster and more completely | `bX_first_ms`, `beacons_detected`, `total_unique` per mode | 1, 3 |
| LOW_LATENCY uses more battery | `charge_uah` drop in the BATTERY runs | table |
| LOW_POWER may miss devices in short windows | `beacons_detected` < 3 or a higher time-to-detect in LOW_POWER | 1, 3 |
| Backpack attenuates RSSI significantly | HAND vs BAG mean RSSI per beacon | 2 |
| Attenuation causes far devices to drop out, lowering the count | Beacon 3 (6 m) detection rate in BAG vs HAND; `total_unique` HAND vs BAG | 1, 2 |
| RSSI is less stable in the bag | RSSI SD per placement | 4 |
| MAC randomisation inflates counts over long scans | Field run: compare `total_unique` (whole run) with `window_count_end` (last 30 s). The bigger the gap, the more inflation from rotation plus people coming and going | 5 |
| Raw BLE count is a feasible crowd proxy | BLE/headcount ratio, off-peak vs peak | 5 |

**Be honest about limitations in the report:** a single scanner phone model, only 3 beacons, a small number of reps, the canteen headcount is approximate, hashing protects privacy but **cannot by itself merge rotated MACs** (the sliding window only reduces that effect), and not everyone has Bluetooth on.

---

## 9. Risks and Fixes (troubleshooting guide)

| # | Problem | Cause | Fix |
|---|---|---|---|
| **R1** | Scan stops/returns nothing when the screen turns off (e.g. in the bag) | Android 8.1+ **pauses unfiltered scans while the screen is off** | Keep the screen ON: `FLAG_KEEP_SCREEN_ON` in the activity and keep the app in the foreground during runs. Set screen timeout to 10 min as a backup. |
| **R2** | Scan silently returns no results after many quick runs | Android blocks **>5 `startScan` calls within 30 s** | Wait ≥10 s between runs; never restart the scan in a loop. To switch mode, stop the scan → wait 1 s → start once. |
| **R3** | `ADVERTISE_FAILED_FEATURE_UNSUPPORTED` on an S6 Lite | Device doesn't support peripheral mode | Check `adapter.isMultipleAdvertisementSupported`. Fallback: use any other phone (team members/friends) as a beacon, or a free iOS/Android beacon app (e.g. "nRF Connect" → Advertiser, with manufacturer data `FFFF 4733 0X`). |
| **R4** | No scan results at all | Location off, or a permission denied | Turn on Location; Settings → Apps → BLECrowd → Permissions → allow all ("Nearby devices" + Location). |
| **R5** | Samsung kills the app in the background | Battery optimisation | Settings → Battery → App → **Unrestricted**. Keep the app in the foreground during runs. |
| **R6** | UI freezes in LOW_LATENCY | Too many UI updates per second | Throttle `ScanRepository` updates to ~250 ms; do file writes on a background thread/buffer. |
| **R7** | Touches inside the bag press buttons | Screen is on in the bag | Add a "lock" overlay during a run, or simply disable all buttons while a run is active. |
| **R8** | Canteen test day isn't a weekday / rain / canteen closed | Scheduling | Do the field test the next possible weekday; the controlled data is enough for a demo. Note the date in the report. |
| **R9** | Beacon shows up twice | Beacon MAC rotated mid-run | Already handled: we count beacons by `beaconId`, not by hash. |
| **R10** | Data loss | Phone reset, uninstalling the app **deletes the `Android/data` folder** | **Never uninstall the app before pulling the data.** Pull + back up after every session. |

---

## 10. Quick Reference: Commands

```bash
# List connected devices
adb devices

# Install the APK on a specific device
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk

# Pull all data from the scanner phone
adb -s <serial> pull /sdcard/Android/data/com.group3.blecrowd/files/runs/ ./analysis/data/

# Watch app logs (add Log.d("BLECrowd", ...) in the code)
adb logcat -s BLECrowd

# Analysis
pip install pandas matplotlib numpy scipy
cd analysis && python analyze.py
```

---

*Plan prepared for CS4473 Mobile Computing, Group 3. Follow the checkpoints at the end of each day. If a checkpoint fails, fix it before moving on, because each day depends on the previous one.*
