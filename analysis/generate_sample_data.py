"""
Generates FAKE data in the exact CSV format the app writes, so analyze.py can be tested
before the real experiments. Output goes to sample_data/ (never mix it with data/).

    python generate_sample_data.py
    python analyze.py --data sample_data --out sample_charts
"""
import csv
import os
import random

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "sample_data")
COLUMNS = ["run_id", "phase", "start_time", "mode", "placement", "rep", "duration_s", "ground_truth",
           "total_unique", "window_count_end", "beacons_detected",
           "b1_first_ms", "b2_first_ms", "b3_first_ms",
           "b1_mean_rssi", "b1_std_rssi", "b1_n", "b2_mean_rssi", "b2_std_rssi", "b2_n",
           "b3_mean_rssi", "b3_std_rssi", "b3_n",
           "total_packets", "battery_pct_start", "battery_pct_end", "charge_uah_start", "charge_uah_end", "notes"]
BASE_RSSI = {1: -62, 2: -72, 3: -80}
CONDS = [("LOW_POWER", "HAND"), ("LOW_LATENCY", "HAND"), ("LOW_POWER", "BAG"), ("LOW_LATENCY", "BAG")]
rng = np.random.default_rng(42)


def fake_run(run_id, phase, mode, placement, rep, dur, gt, n_others, notes=""):
    t0 = 1764000000000 + random.randint(0, 10 ** 7)
    rate = 10 if mode == "LOW_LATENCY" else 1.2          # received packets/s per beacon
    bag_loss = 10 if placement == "BAG" else 0
    events, row = [], {c: "" for c in COLUMNS}
    found = 0
    for b in (1, 2, 3):
        mean = BASE_RSSI[b] - bag_loss
        p_detect = 1.0 if mean > -86 else 0.4
        if rng.random() > p_detect:
            row[f"b{b}_n"] = 0
            continue
        n = max(1, int(rng.poisson(rate * dur * (0.6 if placement == "BAG" else 1))))
        times = np.sort(rng.uniform(0, dur * 1000, n)) + (0 if mode == "LOW_LATENCY" else rng.uniform(0, 4000))
        rssi = np.round(rng.normal(mean, 6 if placement == "BAG" else 3.5, n)).astype(int)
        h = f"beacon{b}{rep:02d}{run_id[-1]}"[:12]
        events += [(t, h, r, b) for t, r in zip(times, rssi)]
        found += 1
        row.update({f"b{b}_first_ms": int(times[0]), f"b{b}_mean_rssi": round(rssi.mean(), 2),
                    f"b{b}_std_rssi": round(rssi.std(ddof=1), 2) if n > 1 else "", f"b{b}_n": n})
    for i in range(n_others):
        seen = rng.random() < (0.95 if mode == "LOW_LATENCY" else 0.75)
        if not seen:
            continue
        k = int(rng.integers(1, 30 if mode == "LOW_LATENCY" else 5))
        for t in rng.uniform(0, dur * 1000, k):
            events.append((t, f"{rng.integers(0, 16 ** 12):012x}" if i % 7 == 0 else f"dev{i:09d}",
                           int(rng.normal(-85, 6)), ""))
    events.sort()
    with open(os.path.join(OUT, f"events_{run_id}.csv"), "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["timestamp_ms", "elapsed_ms", "run_id", "mode", "placement", "hash_id", "rssi", "beacon_id", "tx_power"])
        for t, h, r, b in events:
            w.writerow([int(t0 + t), int(t), run_id, mode, placement, h, r, b, 127])
    uniq = len({e[1] for e in events})
    pct = 90 - rep
    drain = int(dur * (60 if mode == "LOW_LATENCY" else 25))
    row.update(run_id=run_id, phase=phase, start_time="2026-11-24 13:%02d:00" % (rep * 3 % 60), mode=mode,
               placement=placement, rep=rep, duration_s=dur, ground_truth=gt, total_unique=uniq,
               window_count_end=int(uniq * (0.55 if dur > 100 else 0.9)), beacons_detected=found,
               total_packets=len(events), battery_pct_start=pct, battery_pct_end=pct - (1 if dur > 100 else 0),
               charge_uah_start=3_500_000, charge_uah_end=3_500_000 - drain, notes=notes)
    return row


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for n in range(20):
        mode, placement = CONDS[n % 4]
        rows.append(fake_run(f"R{n + 1:02d}", "CONTROLLED", mode, placement, n // 4 + 1, 60, 3, 25))
    rows.append(fake_run("B1", "BATTERY", "LOW_POWER", "HAND", 1, 600, 3, 25))
    rows.append(fake_run("B2", "BATTERY", "LOW_LATENCY", "HAND", 1, 600, 3, 25))
    rows.append(fake_run("F1", "FIELD", "LOW_LATENCY", "FIELD", 1, 300, 18.7, 40, "Off-peak morning"))
    rows.append(fake_run("F2", "FIELD", "LOW_LATENCY", "FIELD", 1, 300, 96.3, 190, "Peak lunch"))
    with open(os.path.join(OUT, "summary.csv"), "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(rows)
    print(f"Wrote {len(rows)} fake runs to {OUT}")


if __name__ == "__main__":
    main()
