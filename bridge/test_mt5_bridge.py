"""Offline freshness checks; no broker connection or password is used."""
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

with patch.dict(sys.modules, {"MetaTrader5": SimpleNamespace(), "requests": SimpleNamespace()}):
    spec = importlib.util.spec_from_file_location("kmr_bridge", Path(__file__).with_name("mt5_bridge.py"))
    bridge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bridge)

SERVER = 1_800_000_000

class FreshnessTest(unittest.TestCase):
    def quote(self, age, bid=100, ask=101):
        return SimpleNamespace(time=SERVER-age, bid=bid, ask=ask)

    def check(self, tick):
        return bridge.checked_quote(tick, SimpleNamespace(now=lambda: SERVER))

    def test_fresh_quote_preserves_broker_time(self):
        result = self.check(self.quote(5))
        self.assertEqual(result["time"], bridge.iso(SERVER-5))

    def test_old_quote_is_not_retimestamped(self):
        with self.assertRaisesRegex(bridge.QuoteUnavailable, "stale"):
            self.check(self.quote(100))

    def test_future_quote_is_not_uploaded(self):
        with self.assertRaisesRegex(bridge.QuoteUnavailable, "future"):
            self.check(self.quote(-3600))

    def test_transit_margin_boundaries(self):
        for age in (75, -25):
            self.check(self.quote(age))
        for age in (76, -26):
            with self.assertRaises(bridge.QuoteUnavailable):
                self.check(self.quote(age))

    def test_windows_clock_drift_does_not_change_quote_age(self):
        with patch.object(bridge.time, "time", return_value=SERVER-19800):
            self.assertEqual(self.check(self.quote(1))["time"], bridge.iso(SERVER-1))
            with self.assertRaises(bridge.QuoteUnavailable):
                self.check(self.quote(-19800))

    def test_missing_and_invalid_quotes_wait(self):
        for tick in (None, self.quote(0, ask=99), self.quote(0, bid=float("nan")), SimpleNamespace(time=float("nan"))):
            with self.assertRaises(bridge.QuoteUnavailable):
                self.check(tick)

class ClockTest(unittest.TestCase):
    def test_server_clock_uses_monotonic_time_not_windows_clock(self):
        clock = bridge.ServerClock()
        with patch.object(bridge, "request", return_value={"serverTime": bridge.iso(SERVER)}), patch.object(bridge.time, "monotonic", side_effect=[10,12,15]), patch.object(bridge.time, "time", return_value=0):
            clock.sync(None, "https://example.test")
            self.assertEqual(clock.now(), SERVER+4)

    def test_cold_start_is_resampled(self):
        clock = bridge.ServerClock()
        with patch.object(bridge, "request", side_effect=[{"serverTime": bridge.iso(SERVER-100)}, {"serverTime": bridge.iso(SERVER)}]) as request, patch.object(bridge.time, "monotonic", side_effect=[0,60,60,62,63]):
            clock.sync(None, "https://example.test")
            self.assertEqual(request.call_count, 2)
            self.assertEqual(clock.now(), SERVER+2)

    def test_all_slow_samples_fail_without_using_old_estimate(self):
        clock = bridge.ServerClock()
        with patch.object(bridge, "request", return_value={"serverTime": bridge.iso(SERVER)}), patch.object(bridge.time, "monotonic", side_effect=[0,20,20,40,40,60]):
            with self.assertRaisesRegex(RuntimeError, "too slow"):
                clock.sync(None, "https://example.test")

    def test_timezone_is_required(self):
        with patch.object(bridge, "request", return_value={"serverTime": "2026-10-11T16:00:00"}), patch.object(bridge.time, "monotonic", side_effect=[0,1]):
            with self.assertRaisesRegex(RuntimeError, "UTC offset"):
                bridge.ServerClock().sync(None, "https://example.test")

class BrokerOffsetTest(unittest.TestCase):
    clock = SimpleNamespace(now=lambda: SERVER)

    def test_observed_octa_three_hour_offset_becomes_fresh(self):
        tick = SimpleNamespace(time=SERVER+10800-2, bid=100, ask=101)
        result = bridge.checked_quote(tick, self.clock, 10800)
        self.assertEqual(result["time"], bridge.iso(SERVER-2))

    def test_default_does_not_infer_offset_from_future_tick(self):
        tick = SimpleNamespace(time=SERVER+10800-2, bid=100, ask=101)
        with self.assertRaises(bridge.QuoteUnavailable):
            bridge.checked_quote(tick, self.clock)

    def test_old_quote_stays_old_after_conversion(self):
        tick = SimpleNamespace(time=SERVER+10800-100, bid=100, ask=101)
        with self.assertRaisesRegex(bridge.QuoteUnavailable, "age=100.0s"):
            bridge.checked_quote(tick, self.clock, 10800)

    def test_wrong_offset_still_rejects_future_quote(self):
        tick = SimpleNamespace(time=SERVER+10800, bid=100, ask=101)
        with self.assertRaises(bridge.QuoteUnavailable):
            bridge.checked_quote(tick, self.clock, 7200)

    def test_candles_and_quotes_use_same_conversion_and_keep_prices(self):
        bars = [dict(time=SERVER+10800-120+i*60, open=100, high=102, low=99, close=101) for i in range(3)]
        candles = bridge.closed_candles(bars, 10800)
        self.assertEqual(len(candles), 2)
        self.assertEqual(candles[0]["time"], bridge.iso(SERVER-120))
        self.assertEqual(candles[0]["closedAt"], candles[1]["time"])
        self.assertEqual(candles[1]["closedAt"], bridge.iso(SERVER))
        self.assertEqual(candles[0]["high"], 102)

    def test_candle_conversion_preserves_month_and_weekend_gaps(self):
        bars = [dict(time=SERVER+10800+i*31*86400, open=100, high=102, low=99, close=101) for i in range(2)]
        candle = bridge.closed_candles(bars, 10800)[0]
        self.assertEqual(candle["time"], bridge.iso(SERVER))
        self.assertEqual(candle["closedAt"], bridge.iso(SERVER+31*86400))

    def test_invalid_offset_is_rejected(self):
        for value in ("nan", "inf", "15", "-15"):
            with self.assertRaises(bridge.argparse.ArgumentTypeError):
                bridge.broker_offset_hours(value)
        self.assertEqual(bridge.broker_offset_hours("3"), 3)
        self.assertEqual(bridge.broker_offset_hours("-3.5"), -3.5)

if __name__ == "__main__":
    unittest.main()
