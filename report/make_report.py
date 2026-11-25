"""
Builds the editable final report (Word .docx, A4, two-column paper format).

    python make_figures.py   # diagrams -> figures/
    python make_report.py    # -> CS4473_Group03_Final_Report.docx

Inline markup in the text below:
    **bold**         bold
    [[placeholder]]  yellow-highlighted text that a group member must check or fill in
"""
import os
import re

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_COLOR_INDEX
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

HERE = os.path.dirname(os.path.abspath(__file__))
FIG = os.path.join(HERE, "figures")
OUT = os.path.join(HERE, "CS4473_Group03_Final_Report.docx")

FONT = "Times New Roman"
BODY_PT = 10
COL_WIDTH_CM = 8.6

doc = Document()

# ---------------------------------------------------------------------------------------------
# Page setup and base styles
# ---------------------------------------------------------------------------------------------
sec = doc.sections[0]
sec.page_height, sec.page_width = Cm(29.7), Cm(21.0)
sec.top_margin = sec.bottom_margin = Cm(1.8)
sec.left_margin = sec.right_margin = Cm(1.6)

normal = doc.styles["Normal"]
normal.font.name = FONT
normal.font.size = Pt(BODY_PT)
normal.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
normal.paragraph_format.space_after = Pt(3)
normal.paragraph_format.space_before = Pt(0)
normal.paragraph_format.line_spacing = 1.0

for name, size in (("Heading 1", 11), ("Heading 2", 10)):
    st = doc.styles[name]
    st.font.name = FONT
    st.font.size = Pt(size)
    st.font.bold = True
    st.font.italic = name == "Heading 2"
    st.font.color.rgb = RGBColor(0, 0, 0)
    st.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    rfonts = st.element.rPr.rFonts
    rfonts.set(qn("w:ascii"), FONT)
    rfonts.set(qn("w:hAnsi"), FONT)
    st.paragraph_format.space_before = Pt(7 if name == "Heading 1" else 4)
    st.paragraph_format.space_after = Pt(2)
    st.paragraph_format.keep_with_next = True


def set_columns(section, num, space_cm=0.6):
    sect_pr = section._sectPr
    cols = sect_pr.find(qn("w:cols"))
    if cols is None:
        cols = OxmlElement("w:cols")
        sect_pr.append(cols)
    cols.set(qn("w:num"), str(num))
    cols.set(qn("w:space"), str(int(space_cm * 567)))


TOKEN = re.compile(r"(\*\*.+?\*\*|\[\[.+?\]\])")


BOLD = re.compile(r"(\*\*.+?\*\*)")
HIGHLIGHT = re.compile(r"(\[\[.+?\]\])", re.S)


def add_runs(p, text, size=None, italic=False):
    for hpart in HIGHLIGHT.split(text):
        if not hpart:
            continue
        hl = hpart.startswith("[[")
        inner = hpart[2:-2] if hl else hpart
        for part in BOLD.split(inner):
            if not part:
                continue
            bold = part.startswith("**")
            r = p.add_run(part[2:-2] if bold else part)
            r.bold = bold or None
            if hl:
                r.font.highlight_color = WD_COLOR_INDEX.YELLOW
            if size:
                r.font.size = Pt(size)
            if italic:
                r.italic = True
    return p


def para(text, align=WD_ALIGN_PARAGRAPH.JUSTIFY, size=None, italic=False, space_after=None):
    p = doc.add_paragraph()
    p.alignment = align
    add_runs(p, text, size=size, italic=italic)
    if space_after is not None:
        p.paragraph_format.space_after = Pt(space_after)
    return p


def bullets(items, size=None):
    for it in items:
        p = doc.add_paragraph(style="List Bullet")
        p.alignment = WD_ALIGN_PARAGRAPH.LEFT
        p.paragraph_format.space_after = Pt(1)
        p.paragraph_format.left_indent = Cm(0.45)
        p.paragraph_format.first_line_indent = Cm(-0.3)
        add_runs(p, it, size=size)


def _heading(text, level):
    h = doc.add_heading(text, level=level)
    for r in h.runs:
        r.font.name = FONT
        r.font.color.rgb = RGBColor(0, 0, 0)
        rfonts = r._element.rPr.rFonts
        rfonts.set(qn("w:eastAsia"), FONT)
        rfonts.set(qn("w:cs"), FONT)
    return h


def h1(text):
    _heading(text, 1)


def h2(text):
    _heading(text, 2)


def figure(path, caption, width_cm=COL_WIDTH_CM):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.space_before = Pt(3)
    p.add_run().add_picture(path, width=Cm(width_cm))
    c = para(caption, align=WD_ALIGN_PARAGRAPH.CENTER, size=8.5, space_after=6)
    return c


def table(rows, widths_cm, caption, header=True, size=8.5):
    cap = para(caption, align=WD_ALIGN_PARAGRAPH.CENTER, size=8.5, space_after=2)
    cap.paragraph_format.keep_with_next = True
    cap.paragraph_format.space_before = Pt(4)
    t = doc.add_table(rows=len(rows), cols=len(rows[0]))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    # Fixed layout with explicit total width and column grid, so every Word version lays it out the same.
    tbl_pr = t._tbl.tblPr
    tbl_w = tbl_pr.find(qn("w:tblW"))
    if tbl_w is None:
        tbl_w = OxmlElement("w:tblW")
        tbl_pr.append(tbl_w)
    tbl_w.set(qn("w:w"), str(int(sum(widths_cm) * 567)))
    tbl_w.set(qn("w:type"), "dxa")
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tbl_pr.append(layout)
    for gc, w in zip(t._tbl.tblGrid.findall(qn("w:gridCol")), widths_cm):
        gc.set(qn("w:w"), str(int(w * 567)))
    for i, row in enumerate(rows):
        for j, val in enumerate(row):
            cell = t.cell(i, j)
            cell.width = Cm(widths_cm[j])
            cell.paragraphs[0].paragraph_format.space_after = Pt(0)
            add_runs(cell.paragraphs[0], f"**{val}**" if header and i == 0 and val else val, size=size)
    doc.add_paragraph().paragraph_format.space_after = Pt(2)
    return t


# ---------------------------------------------------------------------------------------------
# Title block (one column)
# ---------------------------------------------------------------------------------------------
set_columns(sec, 1)
p = para("**BLE Crowd Sensing: How Scan Mode and Phone Placement Affect Bluetooth Device Counting on Android**",
         align=WD_ALIGN_PARAGRAPH.CENTER, size=15, space_after=4)
para("CS4473 Mobile Computing, Group Research Project (Option A: Counting Nearby Bluetooth Devices), Group 3",
     align=WD_ALIGN_PARAGRAPH.CENTER, size=10, italic=True, space_after=2)
para("De Silva W.G.K.G (220105A)  ·  Imalka T.G.Y (220236B)", align=WD_ALIGN_PARAGRAPH.CENTER, size=10.5,
     space_after=8)

# ---------------------------------------------------------------------------------------------
# Body (two columns)
# ---------------------------------------------------------------------------------------------
body = doc.add_section(WD_SECTION.CONTINUOUS)
set_columns(body, 2)

para("**Abstract**: Many places want to know how crowded they are, and almost every person carries a "
     "Bluetooth Low Energy (BLE) device. We built an Android app in Kotlin that counts nearby BLE devices "
     "and studied how accurate the count is. We varied two factors over two levels each: the Android scan "
     "mode (Low Power vs. Low Latency) and the scanner placement (in the hand vs. inside a closed backpack). "
     "Three of our own tablets acted as known advertisers (ground truth), and every condition was repeated "
     "five times (20 controlled runs). As our own twist, we also tested the app in the university canteen "
     "at an off-peak and a peak time against a manual headcount.", size=9)

# 1 Introduction -------------------------------------------------------------------------------
h1("1. Introduction")
para("BLE devices such as phones, smartwatches and earbuds send short radio packets called "
     "**advertisements** several times per second. Anyone nearby can receive them without pairing. "
     "Counting the distinct devices that are heard is therefore a cheap way to estimate how crowded a "
     "place is, which is useful for canteens, libraries, buses and events [1], [5].")
para("In practice the count is not simple. It depends on mobile computing limits: the radio is shared and "
     "costs battery, so Android offers scan modes that trade detection speed for energy [2]; the human body "
     "and bags weaken the 2.4 GHz signal; Android limits how often an app may start a scan; and modern "
     "phones change (randomise) their Bluetooth address to protect privacy, which can make one device "
     "look like several [4], [6]. Permissions also differ between Android versions [3].")
para("**Research question:** How accurately can an Android device count nearby BLE devices, and how do "
     "the scan mode (Low Power vs. Low Latency) and the placement of the scanner (in hand vs. in a "
     "backpack) affect the count and the stability of the received signal strength (RSSI)?")
para("**Expectations (from our proposal):** Low Latency finds devices faster and more completely but uses "
     "more battery; Low Power may miss devices in short windows. A backpack lowers the RSSI, so distant "
     "devices may drop below the detection limit and the count goes down. Address randomisation may "
     "inflate counts over long scans.")

# 2 Prototype and method -----------------------------------------------------------------------
h1("2. Prototype and Method")
h2("2.1 Prototype")
para("The prototype is one Android app (Kotlin, Jetpack Compose UI, minSdk 29, targetSdk 35) with two "
     "roles, shown in Fig. 1. The same app runs on every device, so we only maintain one code base.")
figure(os.path.join(FIG, "fig1_block_diagram.png"),
       "Fig. 1. Block diagram of the prototype.")
para("**Beacon role.** Each ground-truth tablet uses the low-level **BluetoothLeAdvertiser** API. It "
     "sends non-connectable advertisements (ADVERTISE_MODE_LOW_LATENCY, TX power MEDIUM, the same on all "
     "three) that carry manufacturer data with the test company ID 0xFFFF, the tag \"G3\" and a beacon ID "
     "(1, 2 or 3). Because the beacon is recognised by this payload and not by its address, address "
     "randomisation cannot hide or duplicate our ground-truth devices.")
para("**Scanner role.** A foreground service (**BleScanService**, type connectedDevice) calls "
     "**BluetoothLeScanner.startScan()** with **ScanSettings**: the selected scan mode, no filters (so all "
     "devices are seen), CALLBACK_TYPE_ALL_MATCHES, aggressive matching and report delay 0, and extended "
     "advertising on all PHYs when the hardware supports it. Each **ScanResult** is passed to one worker "
     "thread that (i) hashes the MAC address with SHA-256 and a random per-session salt, keeping only 12 "
     "hex characters, (ii) reads the beacon ID, (iii) updates a **DeviceTracker** that keeps the number of "
     "distinct IDs in a 30 s sliding window, the total distinct IDs in the run and per-beacon RSSI "
     "statistics, and (iv) writes one CSV line per packet. A **StateFlow** sends the live count to the UI "
     "at most four times per second, because Low Latency can produce hundreds of callbacks per second.")


para("The UI has a Low Power / Low Latency toggle, a placement selector, a run timer, the live count, the beacon status with "
     "RSSI, and a share button for the CSV files. **BatteryManager** charge-counter readings are stored "
     "at the start and end of each run. Two CSV files are produced: **events_<run>.csv** (timestamp, "
     "hashed ID, RSSI, beacon ID, TX power, manufacturer) and **summary.csv** (one row per run: count in "
     "the window, total unique, beacons detected, time to first detection and RSSI mean/SD per beacon, "
     "packets, battery). A Python script (**analyze.py**; pandas, NumPy, SciPy, Matplotlib) turns these "
     "files into all tables and figures, so the analysis can be reproduced from the data package.")

h2("2.2 Devices")
table([
    ["Role", "Device (Samsung)", "Android"],
    ["Scanner", "Galaxy Tab S9 FE", "[[Android ?]]"],
    ["Beacons", "3 × Galaxy Tab S6 Lite", "[[Android ?]]"],
], [1.9, 4.3, 1.8], "Table I. Devices used in the experiments.")

h2("2.3 Controlled experiment")
para("We varied **two factors over two levels each** (Table II), as required for Option A. Ground truth "
     "was the three beacons, placed 2 m, 4 m and 6 m from the scanner at about 1 m height (Fig. 2). "
     "Different distances let us see whether the far beacon drops out in the bag. The room, beacon "
     "positions, operator, standing spot and backpack were the same in every run.")
table([
    ["Cond.", "Scan mode", "Placement", "Runs"],
    ["C1", "LOW_POWER", "Hand", "5"],
    ["C2", "LOW_LATENCY", "Hand", "5"],
    ["C3", "LOW_POWER", "Bag", "5"],
    ["C4", "LOW_LATENCY", "Bag", "5"],
], [1.2, 2.8, 2.0, 1.2], "Table II. Conditions (2 × 2 design, 20 runs).")

figure(os.path.join(FIG, "fig2_setup.png"),
       "Fig. 2. Controlled setup: distance of each beacon from the scanner.")

para("Each run had a 5 s countdown (to place the device), 60 s of scanning and logging, and an "
     "automatic save. Every condition was repeated **five times** (more than the minimum "
     "of three) and the order was interleaved (C1, C2, C3, C4, C1, ...), so slow changes in the room, "
     "such as people walking in, affect all conditions equally. Between runs we waited at least 10 s "
     "because of Android's scan-start limit. Location: [[room / building, date]].")

para("**Measurements.** For each run we report: beacons detected (0–3, compared with the true value 3), "
     "time until each beacon was first seen, mean and standard deviation (SD) of each beacon's RSSI "
     "(level and stability), the total and 30 s window counts of all devices, the number of packets, and "
     "the charge used. For every condition we give the mean and SD over the five runs.")

h2("2.4 Our twist: canteen field test")
para("We tested whether a raw BLE count can be a proxy for crowd size in a real Sri Lankan campus "
     "setting. The scanner was held at a fixed central table in the [[name]] canteen for 300 s in Low "
     "Latency mode, once at an off-peak time ([[time, date]]) and once at the lunch peak ([[time, "
     "date]]). Ground truth was a manual headcount of a fixed area, taken at 0, 2.5 and 5 min and "
     "averaged. We compare the BLE count with the headcount as a ratio (BLE devices per person).")

h2("2.5 Privacy")
para("Following the assignment rules, no identifiable data about other people is stored. Only salted, "
     "truncated hashes are written to disk; the salt changes every app session, so IDs cannot be linked "
     "across sessions. Real addresses and device names can be shown on screen for the demo, but are "
     "never saved. Field data is used only as aggregate counts.")

# 3 Results ------------------------------------------------------------------------------------
h1("3. Results")
para("[[To be completed by (member name).]]")
for _ in range(3):
    doc.add_paragraph()

# 4 Discussion ---------------------------------------------------------------------------------
h1("4. Discussion")
h2("4.1 Challenges and how we handled them")
bullets([
    "**Scan-start limit.** Android silently blocks an app that starts more than about five scans in 30 s. "
    "A ScanLimiter allows at most four starts per 30 s and delays the next start; a warning appears if no "
    "packet arrives within 10 s.",
    "**Screen-off and background limits.** Scans without filters may be paused when the screen is off, and "
    "Samsung may stop background apps. We used a foreground service, kept the screen on during runs "
    "(also in the bag) and set battery usage to Unrestricted.",
    "**Permissions.** Android 12+ needs BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT; older "
    "versions need location [3]. We did not use the neverForLocation flag, because it can filter out "
    "beacon packets, and the app checks that Bluetooth and Location are on.",
    "**Address randomisation.** One device can appear under several addresses. We identify ground-truth "
    "beacons by payload, not by address, and use a 30 s window for the live count. We also saw that one "
    "iPhone appeared as two devices, because it sends different Apple advertisement types from different "
    "addresses, while a Windows laptop with Bluetooth on did not advertise at all.",
    "**Data rate.** Low Latency can deliver hundreds of packets per second, so hashing and CSV writing run "
    "on a worker thread with a buffered writer, and UI updates are throttled.",
])
h2("4.2 Limitations")
bullets([
    "One scanner model and one beacon model; other phones have different radios and antennas.",
    "Only three beacons, one room and one canteen; five runs per condition is still a small sample.",
    "RSSI changes with body position and orientation, which we could only keep roughly constant.",
    "The canteen headcount is approximate, people move during the 5 min, and the number of BLE devices "
    "per person varies (0 if Bluetooth is off, 2–3 with a watch and earbuds).",
    "Hashing protects privacy but cannot join rotated addresses of the same device, so long scans can "
    "still over-count; devices that do not advertise are invisible.",
    "60 s runs are short for battery measurement; the charge counter is noisy over short periods.",
])

# 5 Contributions --------------------------------------------------------------------------------
h1("5. Contributions")
para("[[**De Silva W.G.K.G (220105A):** scanner service, hashing and counting logic, CSV logging, "
     "Python analysis script, controlled experiments, results section.]]")
para("[[**Imalka T.G.Y (220236B):** beacon advertiser, user interface, experiment setup and run sheet, "
     "controlled experiments, canteen field test and headcount, report writing and demo.]]")

# Attribution --------------------------------------------------------------------------------------
h1("Source and AI Attribution")
para("We used Claude Code (Anthropic, AI assistant) to draft the project plan, to generate the first "
     "version of the Android app (the Kotlin files of the app), the Python "
     "analysis scripts, the report diagrams, and a first draft of this report. We reviewed and tested "
     "all generated code on our own devices and changed it where needed: [[list your own changes]]. "
     "BLE scanning and advertising follow the official Android documentation [1]–[3]. All measurements "
     "were collected by us on real devices; no data was generated by AI. The full AI-usage log is in "
     "AI_USAGE_LOG.md in the data package.", align=WD_ALIGN_PARAGRAPH.LEFT)

# References -------------------------------------------------------------------------------------
h1("References")
refs = [
    "[1] Android Developers, \"Bluetooth Low Energy overview\" and \"Find BLE devices\". [Online]. "
    "Available: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview",
    "[2] Android Developers, \"ScanSettings\" API reference. [Online]. Available: "
    "https://developer.android.com/reference/android/bluetooth/le/ScanSettings",
    "[3] Android Developers, \"Bluetooth permissions\". [Online]. Available: "
    "https://developer.android.com/develop/connectivity/bluetooth/bt-permissions",
    "[4] Bluetooth SIG, \"Bluetooth Core Specification, Version 5.4\", Vol. 6, Part B (LE privacy and "
    "advertising), 2023.",
    "[5] J. Weppner and P. Lukowicz, \"Bluetooth based collaborative crowd density estimation with mobile "
    "phones,\" in Proc. IEEE PerCom, 2013, pp. 193–200.",
    "[6] J. Martin et al., \"A study of MAC address randomization in mobile devices and when it fails,\" "
    "Proc. Privacy Enhancing Technologies, vol. 2017, no. 4, pp. 365–383, 2017.",
    "[7] CS4473 Mobile Computing, \"Continuous Assessment: Group Research Project (Assignment 2)\", "
    "course handout, 2026.",
]
for r in refs:
    p = para(r, align=WD_ALIGN_PARAGRAPH.LEFT, size=8, space_after=1)

doc.save(OUT)
print("Saved", OUT)
