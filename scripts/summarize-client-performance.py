#!/usr/bin/env python3
"""Summarize complete benchmark runs; never discard failed/incomplete trials.

Accept the controller manifest, or a portable JSON(.gz) bundle with manifest/trials.
Run-level CPU/RSS medians are not pooled as if 1Hz samples were independent runs.
"""
import argparse
import gzip
import importlib.util
import json
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("client_performance", Path(__file__).with_name("client-performance.py"))
bench = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bench)


def dist(values):
    if not values:
        raise ValueError("empty metric population")
    return {**bench.distribution(values), "min": min(values)}


def timing_metrics(trial):
    t = trial["timings_s"]
    return {
        "cli_to_wrapper_s": t["wrapper_process_created"],
        "wrapper_to_mc_s": t["mc_process_created"] - t["wrapper_process_created"],
        "mc_to_client_rpc_s": t["client_rpc_ready"] - t["mc_process_created"],
        "cli_to_client_rpc_s": t["client_rpc_ready"],
        "cli_to_first_gui_status_s": t["first_gui_status"],
        "cli_to_world_ready_s": t["world_ready"],
        "cli_to_visual_ready_s": t["visually_ready"],
        "mc_to_visual_ready_s": t["visually_ready"] - t["mc_process_created"],
        "cli_to_first_png_s": t["first_window_response"],
        "session_start_return_s": t["session_cli_return"],
        "first_window_rpc_ms": trial["first_window_shot"]["latency_ms"],
        "first_gui_rpc_ms": trial["first_gui_shot"]["latency_ms"],
    }


def summarize(manifest, trials):
    expected = [(f"r{r + 1:02d}-{i + 1}-{m}", m)
                for r in range(manifest["arguments"]["rounds"])
                for i, m in enumerate(bench.mode_order(r))]
    if [(x["trial"], x["mode"]) for x in trials] != expected:
        raise ValueError("missing, duplicate or out-of-order trials")
    for t in trials:
        if t["status"] != "success" or not t["cleanup"].get("ok") or t["errors"]:
            raise ValueError("failed trial cannot be silently excluded: " + t["trial"])
        if len(t["shots"]) != manifest["arguments"]["shots"] or t.get("failed_shots"):
            raise ValueError("incomplete screenshot population: " + t["trial"])
        frames = [s["result"]["frameId"] for s in t["shots"]]
        if any(not s.get("verified") for s in t["shots"]) or any(b <= a for a, b in zip(frames, frames[1:])):
            raise ValueError("unverified/non-increasing screenshots: " + t["trial"])
        if any(not s.get("complete") for s in t["stages"].values()):
            raise ValueError("incomplete stage: " + t["trial"])
    graphics = [t["visually_ready_status"]["graphics"] for t in trials]
    if not graphics or any(g != graphics[0] for g in graphics):
        raise ValueError("different graphics environments must not be pooled")
    result = {
        "schemaVersion": 1, "inputs": manifest["inputs"], "arguments": manifest["arguments"],
        "graphics": graphics[0], "trial_count": len(trials), "failed_trials": 0,
        "methodology": manifest["methodology"] + [
            "CPU uses the actual in-stage /proc sample subwindow, not the complete RPC endpoint interval",
            "CPU/RSS/TPS summary populations are one value per trial; screenshot latencies are pooled requests",
            "p95 uses linear interpolation; three trials are not a population confidence bound"],
        "modes": {},
    }
    for mode in bench.MODES:
        group = [t for t in trials if t["mode"] == mode]
        metrics = [timing_metrics(t) for t in group]
        all_shots = [s for t in group for s in t["shots"]]
        warm_shots = [s for t in group for s in t["shots"][5:]]
        per_mode = {
            "n_trials": len(group),
            "timings": {key: dist([x[key] for x in metrics]) for key in metrics[0]},
            "screenshots": {
                "all_latency_ms": dist([s["latency_ms"] for s in all_shots]),
                "after_first_five_latency_ms": dist([s["latency_ms"] for s in warm_shots]) if warm_shots else None,
                "bytes": dist([s["result"]["bytes"] for s in all_shots]),
                "dimensions": sorted({(s["result"]["width"], s["result"]["height"]) for s in all_shots}),
                "errors": 0,
            },
            "observed_lifetime_hwm_mib": dist([max(v["hwm_mib"] for v in t["proc_samples"]["mc"]) for t in group]),
            "stages": {},
        }
        for stage in ("world_idle", "gui_idle", "gui_screenshots"):
            values = [t["stages"][stage] for t in group]
            if any(v["process"]["mc"]["n"] < 2 for v in values):
                raise ValueError("insufficient CPU samples: " + stage)
            per_mode["stages"][stage] = {
                "rpc_interval_s": dist([v["duration_s"] for v in values]),
                "cpu_sample_interval_s": dist([v["process"]["mc"]["sample_duration_s"] for v in values]),
                "mc_cpu_percent_one_core_100": dist([v["process"]["mc"]["cpu_percent_one_core_100"] for v in values]),
                "mc_rss_mib": dist([v["process"]["mc"]["rss_mib"]["median"] for v in values]),
                "mc_threads": dist([v["process"]["mc"]["threads"]["median"] for v in values]),
                "mc_fd": dist([v["process"]["mc"]["fd"]["median"] for v in values]),
                **{key: dist([v[key] for v in values]) for key in
                   ("natural_tps", "client_ticks_per_s", "render_frames_per_s", "skipped_frames_per_s")},
            }
        result["modes"][mode] = per_mode
    baseline = result["modes"]["continuous"]
    for mode in ("demand", "gui-only"):
        target = result["modes"][mode]
        target["idle_cpu_reduction_vs_continuous_percent"] = {
            phase: 100 * (1 - target["stages"][phase]["mc_cpu_percent_one_core_100"]["median"] /
                         baseline["stages"][phase]["mc_cpu_percent_one_core_100"]["median"])
            for phase in ("world_idle", "gui_idle")}
    return result


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    source = p.add_mutually_exclusive_group(required=True)
    source.add_argument("--manifest", type=Path)
    source.add_argument("--bundle", type=Path)
    args = p.parse_args(argv)
    if args.manifest:
        manifest = json.loads(args.manifest.read_text())
        trials = []
        for entry in manifest["trials"]:
            name = entry["name"]
            if Path(name).name != name or name in (".", ".."):
                raise ValueError("unsafe trial name")
            trials.append(json.loads((args.manifest.parent / name / "result.json").read_text()))
    else:
        opener = gzip.open if args.bundle.suffix == ".gz" else open
        with opener(args.bundle, "rt", encoding="utf-8") as f:
            bundle = json.load(f)
        manifest, trials = bundle["manifest"], bundle["trials"]
    print(json.dumps(summarize(manifest, trials), ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
