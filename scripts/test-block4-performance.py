#!/usr/bin/env python3
"""Block4纯统计契约的独立单测；只证明统计与时间表算法，不冒充真实HTTP对照。"""
import importlib.util
from pathlib import Path
import unittest

SOURCE = Path(__file__).with_name("block4-performance.py")
spec = importlib.util.spec_from_file_location("flowgate_block4", SOURCE)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkStatsTest(unittest.TestCase):
    def test_rejections_and_client_shed_do_not_raise_business_success(self):
        rows = [{"kind": "fast", "status": 200, "latencyMs": 10},
                {"kind": "fast", "status": 200, "latencyMs": 30},
                {"kind": "slow", "status": 429, "latencyMs": 1},
                {"kind": "slow", "status": 503, "latencyMs": 2},
                {"kind": "fast", "status": "CLIENT_SHED"},
                {"kind": "fast", "status": "NETWORK_ERROR"}]
        report = benchmark.summarize(rows, 2)
        self.assertEqual(report["businessSuccess"], 2)
        self.assertEqual(report["businessSuccessRps"], 1.0)
        self.assertEqual(report["clientShed"], 1)
        self.assertEqual(report["networkErrors"], 1)
        self.assertEqual(report["fastSuccess"]["p95Ms"], 30)
        self.assertEqual(report["allHttp"]["samples"], 4)

    def test_fixed_arrival_schedule_is_reproducible(self):
        items = benchmark.scheduled_requests(2, 3, 5)
        self.assertEqual(items, benchmark.scheduled_requests(2, 3, 5))
        self.assertEqual(len(items), 16)
        self.assertEqual(sum(kind == "fast" for _, kind in items), 6)
        self.assertEqual(sum(kind == "slow" for _, kind in items), 10)
        self.assertLess(items[-1][0], 2)

    def test_no_success_does_not_invent_latency(self):
        report = benchmark.summarize([{"kind": "slow", "status": 503, "latencyMs": 3}], 5)
        self.assertEqual(report["businessSuccessRps"], 0)
        self.assertIsNone(report["allSuccess"]["p99Ms"])


if __name__ == "__main__":
    unittest.main()
