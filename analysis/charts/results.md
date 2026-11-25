# BLE Crowd Sensing: results

Runs: 24 total · CONTROLLED: 20 · BATTERY: 2 · FIELD: 2

## 1. Controlled runs: mean ± SD per condition

```
                       runs  beacons_mean  beacons_std  unique_mean  unique_std  window_mean  packets_mean  b1_rssi_mean  b1_rssi_sd  b1_detect_rate  b2_rssi_mean  b2_rssi_sd  b2_detect_rate  b3_rssi_mean  b3_rssi_sd  b3_detect_rate
mode        placement                                                                                                                                                                                                                   
LOW_LATENCY BAG           5           2.8         0.45         11.2        2.28          9.0        1471.4         -82.5        4.31             1.0         -87.5        3.23             1.0         -95.1        2.04             0.8
            HAND          5           2.8         0.45          8.8        0.45          8.8        1708.4         -84.1        3.77             1.0         -87.5        3.82             1.0         -94.6        2.16             0.8
LOW_POWER   BAG           5           2.2         0.45          9.8        1.79          8.6         280.2         -83.0        4.41             1.0         -87.2        3.53             1.0         -90.2        0.96             0.2
            HAND          5           2.4         0.55          8.6        0.55          7.8         267.6         -83.3        3.99             1.0         -86.0        3.90             1.0         -96.0         NaN             0.4
```

## 2. RSSI attenuation from the backpack (HAND - BAG)

| Beacon | HAND mean (dBm) | BAG mean (dBm) | Attenuation (dB) | HAND SD | BAG SD | t-test p |
|---|---|---|---|---|---|---|
| B1 (2 m) | -84.0 | -83.2 | -0.7 | 4.22 | 4.50 | 0.2941 |
| B2 (4 m) | -87.3 | -87.5 | 0.1 | 3.98 | 3.62 | 0.3393 |
| B3 (6 m) | -94.2 | -94.4 | 0.2 | 2.13 | 1.74 | 0.4871 |

(t-test = Welch's t-test on per-run mean RSSI; install scipy to get p-values.)

## 3. Detection latency (time to first sighting)

```
mode       LOW_POWER  LOW_LATENCY
placement                        
HAND            3.52         3.14
BAG             1.32         2.97
```

## 4. Battery cost (BATTERY runs)

```
run_id        mode  duration_s  drain_mah  drain_mah_per_h  pct_drop  total_packets
    B1   LOW_POWER         600       15.0             90.0         1           2133
    B2 LOW_LATENCY         600       36.0            216.0         1          18583
```

## 5. Field test (canteen)

| Run | Label | Headcount | BLE window | BLE total unique | BLE/headcount | total/window (MAC rotation + turnover) |
|---|---|---|---|---|---|---|
| F1 | Off-peak morning | 18.7 | 69 | 127 | 3.69 | 1.84 |
| F2 | Peak lunch | 96.3 | 288 | 524 | 2.99 | 1.82 |
