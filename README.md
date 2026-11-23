# BLE Crowd Sensing (CS4473 Group 3)

One Android app with two roles, **Scanner** (S9 FE) and **Beacon** (3 × S6 Lite), plus a Python analysis script.
The full experiment design is in [PROJECT_PLAN.md](PROJECT_PLAN.md). This file covers how to build, run and use the app.

```
app/src/main/java/com/group3/blecrowd/
├── MainActivity.kt      UI shell: role switch, permission / Bluetooth / Location checks
├── ui/Screens.kt        Scanner screen + Beacon screen (Compose)
├── BleScanService.kt    Foreground service: scanning, run timer, countdown, CSV, vibration, ScanLimiter
├── BeaconAdvertiser.kt  Beacon role: advertises 0xFFFF + "G3" + id
├── DeviceTracker.kt     Counting: sliding 30 s window, total unique, per-beacon RSSI stats
├── HashUtil.kt          SHA-256(session salt + MAC) → 12 hex chars
├── CsvLogger.kt         events_<run>.csv + summary.csv
├── RunPlanner.kt        Next run ID, interleaved C1→C2→C3→C4 plan, file locations
├── ScanRepository.kt    Shared live state (StateFlow) between service and UI
└── Constants.kt
analysis/
├── analyze.py               Charts + statistics (D4)
├── generate_sample_data.py  Fake data so you can test analyze.py before the experiments
└── data/                    ← put the phone's CSVs here
```

---

## Step 1: Install Android Studio and open the project (once)

1. Install **Android Studio** (latest stable) from developer.android.com/studio. On first launch, let the setup wizard install the **Android SDK** and accept the licences.
2. **File → Open…** and choose this folder (the one containing `settings.gradle.kts`).
3. Wait for **Gradle sync** to finish. The first time it downloads Gradle 8.11.1, AGP 8.7.3 and the Compose libraries (a few minutes).
   - If Studio asks to install **SDK Platform 35**, click the link to install it.
   - If it offers to upgrade the Android Gradle Plugin, you can say **"Don't ask again"**. The pinned versions are fine.
4. **Build → Make Project**. It should finish with `BUILD SUCCESSFUL`.

## Step 2: Prepare all 4 devices

On the S9 FE **and** each S6 Lite:
1. Settings → About phone/tablet → Software information → tap **Build number** 7 times.
2. Settings → Developer options → **USB debugging ON**.
3. **Bluetooth ON** and **Location ON**.
4. Charge to 100%.

## Step 3: Install the app

Plug in one device at a time, select it in the device dropdown at the top of Android Studio, and press **▶ Run**.
Do this for all 4 devices. Then on each device:
- Accept **all** permission prompts ("Nearby devices", Location, Notifications).
- Settings → Apps → BLE Crowd → Battery → **Unrestricted** (Risk R5).

> Alternative: **Build → Build APK(s)**, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
> Keep that APK as the demo backup.

## Step 4: Day-1 checkpoint (smoke test)

1. On each S6 Lite: tap **Beacon**, choose **Beacon 1 / 2 / 3**, then **START ADVERTISING**. The screen shows a big **1/2/3**.
   - "feature unsupported" means that tablet can't advertise; see Risk R3 in the plan.
2. On the S9 FE: tap **Scanner**, then **LIVE SCAN**.
   - The big number (devices in the last 30 s) climbs.
   - The **Ground-truth beacons** panel shows `B1 ✔ B2 ✔ B3 ✔` with RSSI.
   - "Recently seen" lists **hashed IDs only**, never real MACs.
3. Flip **Low Power ↔ Low Latency** during the live scan. The count resets and fills noticeably faster in Low Latency. **This is the D2 demo.**
   (The app waits automatically if you switch too often, because Android allows only ~5 scan starts per 30 s.)
4. Tap **STOP LIVE**.

## Step 5: Day 2, the 20 controlled runs

Setup: beacons at **2 m / 4 m / 6 m**, ~1 m high, positions taped, same person, same backpack.

On the S9 FE (Scanner):
1. Phase **Controlled**. Leave **Follow interleaved plan** ON. The app then fills in **Run ID (R01…R20), Scan mode, Placement and Rep** in the interleaved order C1 → C2 → C3 → C4 → C1 …
2. Ground truth = **3**, duration = **60 s**.
3. Press **START RUN**. You get a 5 s countdown to put the phone in position (hand at chest height, or inside the **closed** backpack).
4. Stand still. The phone **vibrates twice** when the run is saved, and the next run is pre-filled automatically.
5. Wait at least 10 s and start the next run. The "Controlled runs saved: n / 20" counter tracks progress.

Notes:
- **STOP** during a run asks for confirmation and **discards** that run (nothing is written to summary.csv). Re-run it with the same ID.
- The **Notes** field is saved with the run ("group walked past", etc.).
- Optional battery runs: Phase **Battery** (600 s), once in Low Power and once in Low Latency.

## Step 6: Day 3, field runs (canteen)

Phase **Field** (sets 300 s, Low Latency, placement Field). Put e.g. `Off-peak morning` / `Peak lunch` in **Notes**; it becomes the chart label.
Member B counts people at 0, 2.5 and 5 min. Type the **average** into **Ground truth before the timer ends**; the field stays editable during the run.

## Step 7: Get the data off the phone

Either:
- **SHARE / EXPORT CSVs** button → Google Drive / Gmail, or
- with USB and adb (adb lives in `%LOCALAPPDATA%\Android\Sdk\platform-tools`):
  ```
  adb pull /sdcard/Android/data/com.group3.blecrowd/files/runs/ analysis/data/
  ```
  then move the CSVs so they sit directly in `analysis/data/` (not in `analysis/data/runs/`).

⚠️ **Never uninstall the app before pulling the data.** Uninstalling deletes the folder (Risk R10). Reinstalling over it with ▶ Run is safe.

## Step 8: Analysis (D4)

```
cd analysis
python -m venv .venv
.venv\Scripts\activate            (Windows)
pip install -r requirements.txt

# Optional: test the pipeline on fake data first
python generate_sample_data.py
python analyze.py --data sample_data --out sample_charts

# Real data
python analyze.py
```
Output in `analysis/charts/`:
| File | Shows |
|---|---|
| `1_detection_accuracy.png` | Beacons detected (of 3) per placement × mode |
| `2_rssi_dropoff.png` | RSSI hand vs bag per beacon (attenuation) |
| `3_time_to_detect.png` | Time to first detection, Low Power vs Low Latency |
| `4_rssi_timeseries.png` | RSSI stability during a run |
| `5_field_test.png` | Canteen BLE count vs headcount |
| `6_battery.png` | Battery drain per mode (if BATTERY runs exist) |
| `summary_table.csv`, `results.md` | Mean ± SD tables, attenuation, t-tests, field ratios. Paste these into the report |

## Troubleshooting

| Symptom | Fix |
|---|---|
| No devices found at all | Location ON? All permissions allowed? (App settings → Permissions) |
| Red "No packets after 10 s" | Wait 30 s (Android scan-start limit), or toggle Bluetooth off/on |
| "Scan failed: registration failed" | Scanning too often. Wait 30 s, or toggle Bluetooth |
| Beacon shows ✖ but the tablet says advertising | Check its distance and that its screen is on; restart advertising |
| Live count stops when the screen is off | Keep the app open (screen stays on automatically). Don't lock the phone in the bag |
| Gradle sync fails | File → Settings → Build Tools → Gradle → Gradle JDK = **jbr-17/21** (Android Studio's bundled JDK) |
