"""Draws the (simple) report diagrams into report/figures/, sized for one column of a two-column page."""
import os

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch, Circle

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "figures")
os.makedirs(OUT, exist_ok=True)

plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8})

BLUE = "#dbe8f7"
GREEN = "#dff0d8"
ORANGE = "#fde9d0"
GREY = "#eeeeee"
EDGE = "#33475b"


def box(ax, x, y, w, h, title, sub="", fc=BLUE, ls="-"):
    ax.add_patch(FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.02,rounding_size=0.12",
                                fc=fc, ec=EDGE, lw=0.9, ls=ls))
    if sub:
        ax.text(x + w / 2, y + h * 0.66, title, ha="center", va="center", fontsize=8.5, fontweight="bold")
        ax.text(x + w / 2, y + h * 0.3, sub, ha="center", va="center", fontsize=7.3)
    else:
        ax.text(x + w / 2, y + h / 2, title, ha="center", va="center", fontsize=8.5, fontweight="bold")


def arrow(ax, x1, y1, x2, y2):
    ax.add_patch(FancyArrowPatch((x1, y1), (x2, y2), arrowstyle="-|>", mutation_scale=10, lw=1.1, color=EDGE))


# Figure 1: simple system overview (vertical) --------------------------------------------------
def fig_block():
    fig, ax = plt.subplots(figsize=(3.4, 3.45))
    ax.set_xlim(0, 10)
    ax.set_ylim(0, 11.6)
    ax.axis("off")

    # Top: advertisers
    box(ax, 0.1, 9.9, 4.7, 1.6, "3 Beacons", "Tab S6 Lite\nBluetoothLeAdvertiser", fc=GREEN)
    box(ax, 5.2, 9.9, 4.7, 1.6, "Other devices", "people's phones,\nwatches, earbuds", fc=GREY, ls="--")
    arrow(ax, 2.45, 9.85, 3.9, 8.95)
    arrow(ax, 7.55, 9.85, 6.1, 8.95)
    ax.text(5.0, 9.45, "BLE signals", ha="center", va="center", fontsize=7.3, style="italic")

    # Middle: scanner app
    ax.add_patch(FancyBboxPatch((0.6, 2.45), 8.8, 6.45, boxstyle="round,pad=0.02,rounding_size=0.2",
                                fc="white", ec=EDGE, lw=1.2))
    ax.text(5.0, 8.45, "Scanner app (Tab S9 FE)", ha="center", va="center", fontsize=9, fontweight="bold")
    steps = [(6.45, "1. Scan (BluetoothLeScanner)", "Low Power or Low Latency mode", BLUE),
             (4.6, "2. Hash and count", "SHA-256 ID, devices in last 30 s", BLUE),
             (2.75, "3. Show and save", "live count on screen + CSV files", ORANGE)]
    for y, t, s, c in steps:
        box(ax, 1.0, y, 8.0, 1.5, t, s, fc=c)
    arrow(ax, 5.0, 6.43, 5.0, 6.12)
    arrow(ax, 5.0, 4.58, 5.0, 4.27)

    # Bottom: analysis
    arrow(ax, 5.0, 2.43, 5.0, 1.75)
    ax.text(5.25, 2.1, "CSV files", ha="left", va="center", fontsize=7.3, style="italic")
    box(ax, 1.6, 0.1, 6.8, 1.6, "Analysis (laptop)", "Python script: tables and charts", fc=ORANGE)

    fig.tight_layout(pad=0.1)
    fig.savefig(os.path.join(OUT, "fig1_block_diagram.png"), dpi=300)
    plt.close(fig)


# Figure 2: simple setup (beacons on a line) ---------------------------------------------------
def fig_setup():
    fig, ax = plt.subplots(figsize=(3.4, 0.8))
    ax.set_xlim(-0.75, 6.6)
    ax.set_ylim(-0.95, 0.5)
    ax.set_aspect("equal")
    ax.axis("off")

    ax.plot([0, 6], [0, 0], color=EDGE, lw=0.9, ls="--", zorder=1)
    ax.add_patch(Circle((0, 0), 0.36, fc=BLUE, ec=EDGE, lw=0.9, zorder=2))
    ax.text(0, 0, "S", ha="center", va="center", fontsize=9, fontweight="bold", zorder=3)
    ax.text(0, -0.64, "Scanner\n(hand or bag)", ha="center", va="center", fontsize=7)
    for d, name in ((2, "B1"), (4, "B2"), (6, "B3")):
        ax.add_patch(FancyBboxPatch((d - 0.42, -0.26), 0.84, 0.52, boxstyle="round,pad=0.02,rounding_size=0.08",
                                    fc=GREEN, ec=EDGE, lw=0.9, zorder=2))
        ax.text(d, 0, name, ha="center", va="center", fontsize=8.5, fontweight="bold", zorder=3)
        ax.text(d, -0.56, f"{d} m", ha="center", va="center", fontsize=8)

    fig.tight_layout(pad=0.05)
    fig.savefig(os.path.join(OUT, "fig2_setup.png"), dpi=300)
    plt.close(fig)


if __name__ == "__main__":
    fig_block()
    fig_setup()
    old = os.path.join(OUT, "fig3_protocol.png")
    if os.path.exists(old):
        os.remove(old)  # no longer used in the report
    print("Figures written to", OUT)
