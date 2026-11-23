"""
BLE Crowd Sensing - analysis script (deliverable D4).

Usage:
    python analyze.py                      # reads ./data, writes ./charts
    python analyze.py --data sample_data   # try it on synthetic data first

Input  (copied from the phone):  data/summary.csv + data/events_*.csv
Output: charts/*.png, charts/summary_table.csv, charts/results.md
"""
import argparse
import glob
import os
import sys

import matplotlib

matplotlib.use("Agg")  # no GUI needed
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

try:
    from scipy import stats as sstats
except ImportError:  # t-tests are optional
    sstats = None

HERE = os.path.dirname(os.path.abspath(__file__))
MODES = ["LOW_POWER", "LOW_LATENCY"]
PLACEMENTS = ["HAND", "BAG"]
MODE_COLORS = {"LOW_POWER": "#4C72B0", "LOW_LATENCY": "#DD8452"}
BEACON_DIST = {1: "2 m", 2: "4 m", 3: "6 m"}

report = []  # lines for results.md


def say(line=""):
    print(line)
    report.append(line)


def save(fig, out, name):
    path = os.path.join(out, name)
    fig.tight_layout()
    fig.savefig(path, dpi=200)
    plt.close(fig)
    print(f"  -> {path}")


def load(data_dir):
    summary_path = os.path.join(data_dir, "summary.csv")
    if not os.path.exists(summary_path):
        sys.exit(f"Missing {summary_path}. Copy the phone's runs/ folder into {data_dir}/ first.")
    s = pd.read_csv(summary_path)
    files = sorted(glob.glob(os.path.join(data_dir, "events_*.csv")))
    if files:
        ev = pd.concat((pd.read_csv(f) for f in files), ignore_index=True)
    else:
        ev = pd.DataFrame(columns=["timestamp_ms", "elapsed_ms", "run_id", "mode", "placement",
                                   "hash_id", "rssi", "beacon_id", "tx_power"])
    ev["beacon_id"] = pd.to_numeric(ev["beacon_id"], errors="coerce")
    return s, ev


def condition_table(ctrl, out):
    say("## 1. Controlled runs: mean ± SD per condition\n")
    g = ctrl.groupby(["mode", "placement"])
    table = g.agg(
        runs=("run_id", "count"),
        beacons_mean=("beacons_detected", "mean"), beacons_std=("beacons_detected", "std"),
        unique_mean=("total_unique", "mean"), unique_std=("total_unique", "std"),
        window_mean=("window_count_end", "mean"),
        packets_mean=("total_packets", "mean"),
    ).round(2)
    for b in (1, 2, 3):
        table[f"b{b}_rssi_mean"] = g[f"b{b}_mean_rssi"].mean().round(1)
        table[f"b{b}_rssi_sd"] = g[f"b{b}_std_rssi"].mean().round(2)
        table[f"b{b}_detect_rate"] = g[f"b{b}_n"].apply(lambda x: (x > 0).mean()).round(2)
    table.to_csv(os.path.join(out, "summary_table.csv"))
    say("```")
    say(table.to_string())
    say("```\n")


def chart_detection(ctrl, out):
    g = ctrl.groupby(["placement", "mode"])["beacons_detected"].agg(["mean", "std"]).unstack()
    g = g.reindex([p for p in PLACEMENTS if p in g.index])
    fig, ax = plt.subplots(figsize=(6, 4))
    means = g["mean"].reindex(columns=[m for m in MODES if m in g["mean"].columns])
    stds = g["std"].reindex(columns=means.columns).fillna(0)
    means.plot(kind="bar", yerr=stds, capsize=4, ax=ax, color=[MODE_COLORS[m] for m in means.columns], rot=0)
    ax.axhline(3, ls="--", c="gray", label="Ground truth (3)")
    ax.set_ylim(0, 3.5)
    ax.set_ylabel("Beacons detected (of 3)")
    ax.set_xlabel("Placement")
    ax.set_title("Detection accuracy by placement & scan mode")
    ax.legend(loc="lower right")
    save(fig, out, "1_detection_accuracy.png")


def chart_rssi_dropoff(ctrl, bev, out):
    say("## 2. RSSI attenuation from the backpack (HAND - BAG)\n")
    data, labels = [], []
    for b in (1, 2, 3):
        for p in PLACEMENTS:
            data.append(bev[(bev.beacon_id == b) & (bev.placement == p)].rssi.dropna().values)
            labels.append(f"B{b} ({BEACON_DIST[b]})\n{p}")
    fig, ax = plt.subplots(figsize=(8, 4.5))
    non_empty = [d if len(d) else np.array([np.nan]) for d in data]
    bp = ax.boxplot(non_empty, patch_artist=True, showfliers=False)
    for i, patch in enumerate(bp["boxes"]):
        patch.set_facecolor("#8FBC8F" if i % 2 == 0 else "#C9A0A0")
    ax.set_xticks(range(1, len(labels) + 1))
    ax.set_xticklabels(labels)
    ax.set_ylabel("RSSI (dBm)")
    ax.set_title("RSSI drop-off: line-of-sight (hand) vs backpack")
    save(fig, out, "2_rssi_dropoff.png")

    say("| Beacon | HAND mean (dBm) | BAG mean (dBm) | Attenuation (dB) | HAND SD | BAG SD | t-test p |")
    say("|---|---|---|---|---|---|---|")
    for b in (1, 2, 3):
        h = bev[(bev.beacon_id == b) & (bev.placement == "HAND")].rssi.dropna()
        k = bev[(bev.beacon_id == b) & (bev.placement == "BAG")].rssi.dropna()
        if len(h) == 0 or len(k) == 0:
            say(f"| B{b} | {h.mean():.1f} | {k.mean():.1f} | n/a | | | |")
            continue
        # t-test on per-RUN means (packets within a run are not independent)
        hr = ctrl[ctrl.placement == "HAND"][f"b{b}_mean_rssi"].dropna()
        kr = ctrl[ctrl.placement == "BAG"][f"b{b}_mean_rssi"].dropna()
        p = ""
        if sstats is not None and len(hr) > 1 and len(kr) > 1:
            p = f"{sstats.ttest_ind(hr, kr, equal_var=False).pvalue:.4f}"
        say(f"| B{b} ({BEACON_DIST[b]}) | {h.mean():.1f} | {k.mean():.1f} | {h.mean() - k.mean():.1f} "
            f"| {h.std():.2f} | {k.std():.2f} | {p} |")
    say("\n(t-test = Welch's t-test on per-run mean RSSI; install scipy to get p-values.)\n")


def chart_time_to_detect(ctrl, out):
    say("## 3. Detection latency (time to first sighting)\n")
    t = ctrl.melt(id_vars=["mode", "placement"], value_vars=["b1_first_ms", "b2_first_ms", "b3_first_ms"],
                  value_name="ms").dropna()
    g = t.groupby(["placement", "mode"])["ms"].agg(["mean", "std"]).div(1000).unstack()
    g = g.reindex([p for p in PLACEMENTS if p in g.index])
    fig, ax = plt.subplots(figsize=(6, 4))
    means = g["mean"].reindex(columns=[m for m in MODES if m in g["mean"].columns])
    stds = g["std"].reindex(columns=means.columns).fillna(0)
    means.plot(kind="bar", yerr=stds, capsize=4, ax=ax, color=[MODE_COLORS[m] for m in means.columns], rot=0)
    ax.set_ylabel("Seconds until first detection")
    ax.set_xlabel("Placement")
    ax.set_title("Detection latency: Low Power vs Low Latency")
    save(fig, out, "3_time_to_detect.png")
    say("```")
    say(means.round(2).to_string())
    say("```\n")


def chart_rssi_timeseries(bev, out, beacon=2):
    sub = bev[bev.beacon_id == beacon]
    if sub.empty:
        return
    fig, ax = plt.subplots(figsize=(8, 4))
    for (m, p), grp in sub.groupby(["mode", "placement"]):
        first_run = sorted(grp.run_id.unique())[0]
        r = grp[grp.run_id == first_run].sort_values("elapsed_ms")
        ax.plot(r.elapsed_ms / 1000, r.rssi.rolling(5, min_periods=1).mean(), label=f"{m} / {p} ({first_run})",
                ls="-" if p == "HAND" else "--", color=MODE_COLORS.get(m))
    ax.set_xlabel("Time (s)")
    ax.set_ylabel(f"RSSI (dBm), Beacon {beacon}")
    ax.set_title("RSSI stability over a run (5-packet rolling mean)")
    ax.legend(fontsize=8)
    save(fig, out, "4_rssi_timeseries.png")


def battery(s, out):
    b = s[s.phase == "BATTERY"].copy()
    if b.empty:
        return
    say("## 4. Battery cost (BATTERY runs)\n")
    b["drain_mah"] = (b.charge_uah_start - b.charge_uah_end) / 1000
    b["drain_mah_per_h"] = b.drain_mah / (b.duration_s / 3600)
    b["pct_drop"] = b.battery_pct_start - b.battery_pct_end
    cols = ["run_id", "mode", "duration_s", "drain_mah", "drain_mah_per_h", "pct_drop", "total_packets"]
    say("```")
    say(b[cols].round(2).to_string(index=False))
    say("```\n")
    fig, ax = plt.subplots(figsize=(5, 4))
    g = b.groupby("mode")["drain_mah_per_h"].mean().reindex([m for m in MODES if m in set(b["mode"])])
    g.plot(kind="bar", ax=ax, color=[MODE_COLORS[m] for m in g.index], rot=0)
    ax.set_ylabel("Battery drain (mAh per hour)")
    ax.set_title("Battery cost of scan mode")
    save(fig, out, "6_battery.png")


def field(s, out):
    f = s[s.phase == "FIELD"].copy()
    if f.empty:
        say("## 5. Field test\n\nNo FIELD runs yet.\n")
        return
    say("## 5. Field test (canteen)\n")
    f["label"] = f.notes.fillna("").astype(str).str.strip()
    f.loc[f.label == "", "label"] = f.run_id
    x = np.arange(len(f))
    fig, ax = plt.subplots(figsize=(7, 4))
    ax.bar(x - 0.27, f.window_count_end, 0.27, label="BLE count (last 30 s)")
    ax.bar(x, f.total_unique, 0.27, label="BLE total unique (whole run)")
    ax.bar(x + 0.27, f.ground_truth, 0.27, label="Manual headcount")
    ax.set_xticks(x)
    ax.set_xticklabels(f.label, fontsize=8)
    ax.set_ylabel("Count")
    ax.set_title("Canteen: BLE count vs people")
    ax.legend(fontsize=8)
    save(fig, out, "5_field_test.png")

    f["ratio_window"] = (f.window_count_end / f.ground_truth).round(2)
    f["inflation"] = (f.total_unique / f.window_count_end).round(2)
    say("| Run | Label | Headcount | BLE window | BLE total unique | BLE/headcount | total/window (MAC rotation + turnover) |")
    say("|---|---|---|---|---|---|---|")
    for _, r in f.iterrows():
        say(f"| {r.run_id} | {r.label} | {r.ground_truth} | {r.window_count_end} | {r.total_unique} "
            f"| {r.ratio_window} | {r.inflation} |")
    say("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(HERE, "data"))
    ap.add_argument("--out", default=os.path.join(HERE, "charts"))
    a = ap.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    os.makedirs(a.out, exist_ok=True)

    s, ev = load(a.data)
    say("# BLE Crowd Sensing: results\n")
    say(f"Runs: {len(s)} total · " + " · ".join(f"{k}: {v}" for k, v in s.phase.value_counts().items()) + "\n")

    ctrl = s[s.phase == "CONTROLLED"]
    if ctrl.empty:
        say("No CONTROLLED runs yet.\n")
    else:
        condition_table(ctrl, a.out)
        chart_detection(ctrl, a.out)
        bev = ev.merge(ctrl[["run_id"]], on="run_id").dropna(subset=["beacon_id"])
        chart_rssi_dropoff(ctrl, bev, a.out)
        chart_time_to_detect(ctrl, a.out)
        chart_rssi_timeseries(bev, a.out)
    battery(s, a.out)
    field(s, a.out)

    with open(os.path.join(a.out, "results.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(report))
    print(f"\nDone. Charts + results.md in {a.out}")


if __name__ == "__main__":
    main()
