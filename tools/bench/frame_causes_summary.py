#!/usr/bin/env python3
"""Stutter summary of a bench.csv written with WWHD_BENCH_ALL=1 (docs/stutter.md): per phase, frame-time
percentiles, the count of long frames and the main cause of each one, and the worst frames."""
import csv
import sys

CAUSES = ["shader_translate", "shader_pack", "pipeline_create", "pipeline_wait", "texture_upload",
          "gpu_wait", "acquire", "present", "game_idle", "game_work"]


def pct(values, p):
    if not values:
        return 0.0
    values = sorted(values)
    return values[min(len(values) - 1, int(p / 100.0 * len(values)))]


def main_cause(row):
    dt = float(row["dt_ms"])
    best, best_ms = "other", 0.0
    for c in CAUSES:
        ms = float(row.get(c + "_ms", 0) or 0)
        if ms > best_ms:
            best, best_ms = c, ms
    return best if best_ms > 0.3 * dt else "other"


def summarize(path, worst=15, skip_boot=120, fps=0):
    """fps: the target frame rate. 0 keeps the 10/16/33 ms columns; 60 counts late frames against the period
    instead: >17.5 ms (5% over 16.67: a frame later than its vsync), >25 ms (1.5 periods: one shown twice), >33.4 ms"""
    lim = [10, 16, 33] if not fps else [1000.0 / fps * 1.05, 1000.0 / fps * 1.5, 1000.0 / fps * 2 + 0.1]
    rows = list(csv.DictReader(open(path, newline="")))[skip_boot:]  # the window opening, the first swaps
    out = []
    groups = {"all": rows}
    for r in rows:
        groups.setdefault(r["phase"], []).append(r)
    out.append("%-10s %6s %7s %7s %7s %8s %8s %6s %6s %6s %7s %7s %6s" % (
        "phase", "frames", "p50", "p99", "p99.9", "max", "avg_fps", ">%gms" % round(lim[0], 1), ">%gms" % round(lim[1], 1),
        ">%gms" % round(lim[2], 1), "skips", "shaders", "pipes"))
    for name in ["all", "menus", "loading", "settling", "measuring"]:
        g = groups.get(name)
        if not g:
            continue
        dt = [float(r["dt_ms"]) for r in g]
        out.append("%-10s %6d %7.2f %7.2f %7.2f %8.2f %8.1f %6d %6d %6d %7d %7d %6d" % (
            name, len(g), pct(dt, 50), pct(dt, 99), pct(dt, 99.9), max(dt), 1000 * len(dt) / sum(dt),
            sum(d > lim[0] for d in dt), sum(d > lim[1] for d in dt), sum(d > lim[2] for d in dt),
            sum(int(r["skipped_draws"]) for r in g), sum(int(r["new_shaders"]) for r in g),
            sum(int(r["new_pipelines"]) for r in g)))
    blame = {}
    for r in rows:
        if float(r["dt_ms"]) > lim[0]:
            c = main_cause(r)
            blame[c] = blame.get(c, 0) + 1
    out.append("frames over %g ms by main cause: " % round(lim[0], 1) + (", ".join("%s=%d" % kv for kv in sorted(blame.items(), key=lambda kv: -kv[1])) or "-"))
    out.append("worst frames:")
    for r in sorted(rows, key=lambda r: -float(r["dt_ms"]))[:worst]:
        parts = ["%s=%.1f" % (c, float(r[c + "_ms"])) for c in CAUSES if float(r[c + "_ms"]) >= 0.5]
        out.append("  frame %s %-9s %7.2f ms draws %s shaders %s pipes %s skips %s | %s" % (
            r["frame"], r["phase"], float(r["dt_ms"]), r["draws"], r["new_shaders"], r["new_pipelines"],
            r["skipped_draws"], " ".join(parts)))
    return "\n".join(out)


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--fps=")]
    fps = [int(a[6:]) for a in sys.argv[1:] if a.startswith("--fps=")]
    print(summarize(args[0], fps=fps[0] if fps else 0))
