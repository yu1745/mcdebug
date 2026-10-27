import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("summary", Path(__file__).with_name("summarize-client-performance.py"))
summary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(summary)


def dataset():
    manifest = {"arguments": {"rounds": 1, "shots": 6}, "inputs": {}, "methodology": []}
    trials = []
    for index, mode in enumerate(summary.bench.MODES):
        cpu = (100, 10, 5)[index]
        stages = {}
        for stage in ("world_idle", "gui_idle", "gui_screenshots"):
            stages[stage] = {"complete": True, "duration_s": 20, "natural_tps": 20,
                "client_ticks_per_s": 20, "render_frames_per_s": 60 if index == 0 else 1,
                "skipped_frames_per_s": 0 if index == 0 else 19,
                "process": {"mc": {"n": 20, "sample_duration_s": 19,
                    "cpu_percent_one_core_100": cpu,
                    **{key: {"median": value} for key, value in
                       (("rss_mib", 1024), ("threads", 10), ("fd", 20))}}}}
        trials.append({"trial": f"r01-{index + 1}-{mode}", "mode": mode, "status": "success",
            "cleanup": {"ok": True}, "errors": [], "stages": stages,
            "proc_samples": {"mc": [{"hwm_mib": 1200}]},
            "visually_ready_status": {"graphics": {"renderer": "test", "vendor": "test", "version": "1"}},
            "timings_s": dict(wrapper_process_created=.5, mc_process_created=1, client_rpc_ready=3,
                              first_gui_status=4, world_ready=5, visually_ready=6,
                              first_window_response=7, session_cli_return=5.8),
            "first_window_shot": {"latency_ms": 100}, "first_gui_shot": {"latency_ms": 40},
            "shots": [{"verified": True, "latency_ms": i + 1,
                       "result": {"frameId": i + 3, "bytes": 100, "width": 200, "height": 100}}
                      for i in range(6)]})
    return manifest, trials


class SummaryTest(unittest.TestCase):
    def test_run_level_population_and_request_population_are_separate(self):
        result = summary.summarize(*dataset())
        mode = result["modes"]["demand"]
        self.assertEqual(mode["stages"]["world_idle"]["mc_cpu_percent_one_core_100"]["n"], 1)
        self.assertEqual(mode["screenshots"]["all_latency_ms"]["n"], 6)
        self.assertEqual(mode["screenshots"]["after_first_five_latency_ms"]["n"], 1)
        self.assertAlmostEqual(mode["screenshots"]["all_latency_ms"]["p95"], 5.75)
        self.assertEqual(mode["idle_cpu_reduction_vs_continuous_percent"]["world_idle"], 90)
        self.assertEqual(mode["timings"]["mc_to_visual_ready_s"]["median"], 5)
        self.assertEqual(mode["stages"]["world_idle"]["cpu_sample_interval_s"]["median"], 19)

    def test_incomplete_or_failed_trials_are_not_filtered(self):
        manifest, trials = dataset()
        with self.assertRaisesRegex(ValueError, "missing"):
            summary.summarize(manifest, trials[:-1])
        trials[0]["status"] = "failed"
        with self.assertRaisesRegex(ValueError, "failed trial"):
            summary.summarize(manifest, trials)

    def test_mixed_renderer_cannot_be_pooled(self):
        manifest, trials = dataset()
        trials[1]["visually_ready_status"]["graphics"]["renderer"] = "another GPU"
        with self.assertRaisesRegex(ValueError, "graphics"):
            summary.summarize(manifest, trials)

    def test_invalid_shots_and_sparse_cpu_are_rejected(self):
        manifest, trials = dataset()
        broken = copy.deepcopy(trials)
        broken[0]["shots"][1]["result"]["frameId"] = broken[0]["shots"][0]["result"]["frameId"]
        with self.assertRaisesRegex(ValueError, "screenshots"):
            summary.summarize(manifest, broken)
        trials[0]["stages"]["world_idle"]["process"]["mc"]["n"] = 1
        with self.assertRaisesRegex(ValueError, "insufficient"):
            summary.summarize(manifest, trials)


if __name__ == "__main__":
    unittest.main()
