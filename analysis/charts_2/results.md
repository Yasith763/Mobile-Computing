# BLE Crowd Sensing: results

Runs: 24 total · CONTROLLED: 20 · BATTERY: 2 · FIELD: 2

## 1. Controlled runs: mean ± SD per condition

```
                       runs  beacons_mean  beacons_std  unique_mean  unique_std  window_mean  packets_mean  b1_rssi_mean  b1_rssi_sd  b1_detect_rate  b2_rssi_mean  b2_rssi_sd  b2_detect_rate  b3_rssi_mean  b3_rssi_sd  b3_detect_rate
mode        placement                                                                                                                                                                                                                   
LOW_LATENCY BAG           5           3.0         0.00         11.6        1.34         11.6        1914.2         -85.4        3.48             1.0         -93.8        2.18             1.0         -94.9        1.84             1.0
            HAND          5           3.0         0.00         12.0        1.73         11.2        2135.8         -84.8        2.43             1.0         -93.7        2.58             1.0         -92.6        1.90             1.0
LOW_POWER   BAG           5           3.0         0.00         11.8        0.84         11.0         379.2         -84.9        3.14             1.0         -94.3        1.38             1.0         -95.0        1.58             1.0
            HAND          5           2.6         0.55         11.4        0.89         10.8         408.2         -83.3        2.60             1.0         -92.9        2.87             0.6         -92.9        1.84             1.0
```

## 2. RSSI attenuation from the backpack (HAND - BAG)

| Beacon | HAND mean (dBm) | BAG mean (dBm) | Attenuation (dB) | HAND SD | BAG SD | t-test p |
|---|---|---|---|---|---|---|
| B1 (2 m) | -84.6 | -85.4 | 0.8 | 2.96 | 3.60 | 0.1055 |
| B2 (4 m) | -92.3 | -92.6 | 0.4 | 4.26 | 3.00 | 0.6369 |
| B3 (6 m) | -92.6 | -93.9 | 1.4 | 2.28 | 2.29 | 0.0121 |

(t-test = Welch's t-test on per-run mean RSSI; install scipy to get p-values.)

## 3. Detection latency (time to first sighting)

```
mode       LOW_POWER  LOW_LATENCY
placement                        
HAND            2.49         1.97
BAG             5.92         1.64
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
