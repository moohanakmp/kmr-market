"""Read-only MT5 candle/quote bridge. Runs on Windows alongside logged-in MT5."""
import argparse
import getpass
import json
import math
import os
import sys
import time
from datetime import datetime, timezone
from urllib.parse import urlparse
import MetaTrader5 as mt5
import requests

TIMEFRAMES = "M1 M2 M3 M4 M5 M6 M10 M12 M15 M20 M30 H1 H2 H3 H4 H6 H8 H12 D1 W1 MN1".split()

def iso(epoch):
    return datetime.fromtimestamp(int(epoch), timezone.utc).isoformat().replace("+00:00", "Z")

def broker_offset_hours(value):
    hours = float(value)
    if not math.isfinite(hours) or abs(hours) > 14:
        raise argparse.ArgumentTypeError("Broker UTC offset must be a finite number between -14 and 14 hours.")
    return hours

def closed_candles(rates, offset_seconds=0):
    candles = []
    for i in range(len(rates) - 1):
        bar = rates[i]
        candles.append({"time": iso(float(bar["time"]) - offset_seconds),
                        "closedAt": iso(float(rates[i+1]["time"]) - offset_seconds),
                        **{p: float(bar[p]) for p in ("open", "high", "low", "close")}})
    return candles

def resolve(symbol, mapping):
    broker_symbol = mapping.get(symbol, symbol)
    # Never guess broker suffixes: an explicit map prevents reading the wrong contract.
    if not mt5.symbol_select(broker_symbol, True):
        raise ValueError(f"MT5 symbol {broker_symbol} unavailable; add its exact broker name to --symbol-map")
    return broker_symbol

def request(session, base, method, path, payload=None):
    res = session.request(method, base + path, json=payload, timeout=(10, 90), allow_redirects=False)
    if res.status_code in (401, 403):
        raise PermissionError("Feed login rejected. Check Render MT5_FEED_PASSWORD and bridge password.")
    if not res.ok:
        message = ""
        try:
            message = res.json().get("message", "")
        except ValueError:
            pass
        raise RuntimeError(f"API status {res.status_code}: {message[:200]}")
    return res.json()

class ServerClock:
    """Estimate server UTC from an authenticated response and monotonic elapsed time."""
    def __init__(self):
        self.anchor = None
        self.epoch = None

    def sync(self, session, base):
        # A free-tier cold start can delay the first response. Resample once awake.
        for _ in range(3):
            start = time.monotonic()
            data = request(session, base, "GET", "/api/feed/time")
            end = time.monotonic()
            stamp = datetime.fromisoformat(data["serverTime"].replace("Z", "+00:00"))
            if stamp.utcoffset() is None:
                raise RuntimeError("KMR server time must include a UTC offset.")
            if end - start <= 10:
                self.epoch = stamp.timestamp() + (end - start) / 2
                self.anchor = end
                return
        raise RuntimeError("Server time requests are too slow to safely check quote freshness. Retry after Render is awake.")

    def now(self):
        if self.anchor is None:
            raise RuntimeError("Server clock has not been synchronized.")
        return self.epoch + time.monotonic() - self.anchor

class QuoteUnavailable(RuntimeError):
    pass

def checked_quote(tick, clock, offset_seconds=0):
    if tick is None:
        raise QuoteUnavailable("No broker quote yet; open the symbol in MT5 Market Watch.")
    stamp = float(tick.time) - offset_seconds
    if not math.isfinite(stamp) or stamp <= 0:
        raise QuoteUnavailable("Broker quote has an invalid timestamp.")
    now = clock.now()
    age = now - stamp
    # Leave 15 seconds for request transit before the server's 90-second limit.
    if age > 75 or age < -25:
        kind = "stale / market closed" if age > 75 else "dated in the future"
        raise QuoteUnavailable(f"Quote {kind}: converted quote UTC={iso(stamp)}, KMR UTC={iso(now)}, age={age:.1f}s, configured broker offset={offset_seconds / 3600:+g}h. Waiting for a fresh UTC broker quote.")
    if not math.isfinite(tick.bid) or not math.isfinite(tick.ask) or tick.bid <= 0 or tick.ask < tick.bid:
        raise QuoteUnavailable("Broker quote has invalid bid/ask prices.")
    # A configured time-zone conversion preserves quote age; never stamp it with 'now'.
    return {"time": iso(stamp), "bid": float(tick.bid), "ask": float(tick.ask)}

def run(args):
    offset_seconds = broker_offset_hours(args.broker_utc_offset_hours) * 3600
    base = args.url.rstrip("/")
    parts = urlparse(base)
    if parts.scheme != "https" and not (parts.scheme == "http" and parts.hostname in ("localhost", "127.0.0.1")):
        raise ValueError("Use an HTTPS application URL (localhost HTTP is allowed).")
    if parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("Use the plain app URL without credentials or query parameters.")
    password = os.environ.get("MT5_FEED_PASSWORD") or getpass.getpass("KMR MT5 feed password: ")
    if len(password) < 7:
        raise ValueError("KMR feed password must have at least 7 characters. Use MT5_FEED_PASSWORD from Render, not your broker login password.")
    mapping = json.load(open(args.symbol_map, encoding="utf-8")) if args.symbol_map else {}
    session = requests.Session()
    session.auth = ("mt5_feed", password)
    session.headers["User-Agent"] = "KMR-MT5-Bridge/1.3"
    if not (mt5.initialize(args.terminal) if args.terminal else mt5.initialize()):
        raise RuntimeError(f"MT5 initialization failed: {mt5.last_error()}. Open and log into your desktop MT5 terminal.")
    if mt5.account_info() is None:
        raise RuntimeError("MT5 has no logged-in account. Log in inside MT5 first.")
    sent = {}
    symbols = []
    refreshed = 0.0
    last_quotes = 0.0
    clock = ServerClock()
    clock_synced = 0.0
    warned = {}

    def report_wait(symbol, exc):
        current = time.monotonic()
        if symbol not in warned or current - warned[symbol] >= 60:
            print(f"{symbol}: {exc}", file=sys.stderr)
            warned[symbol] = current
    try:
        clock.sync(session, base)
        clock_synced = time.monotonic()
        print(f"KMR server clock synchronized. Windows clock difference: {time.time() - clock.now():+.1f}s.")
        print(f"Broker timestamp setting: UTC{offset_seconds / 3600:+g}h; subtracting {offset_seconds:g}s from quotes and candles.")
        print("KMR feed authenticated and connected to MT5. Read-only: no orders are submitted.")
        print("Scanning all 21 MT5 timeframes. Keep this terminal and MT5 open.")
        while True:
            now = time.monotonic()
            try:
                if now - clock_synced >= 60:
                    clock.sync(session, base)
                    clock_synced = time.monotonic()
                if now - refreshed >= 60:
                    symbols = request(session, base, "GET", "/api/feed/symbols")
                    refreshed = now
                quotes = []
                for symbol in symbols:
                    try:
                        bs = resolve(symbol, mapping)
                        tick = mt5.symbol_info_tick(bs)
                        quote = checked_quote(tick, clock, offset_seconds)
                        quotes.append({"symbol": symbol, "quote": quote})
                        # One 260-bar upload per new closed candle; quote batches are small.
                        for tf in TIMEFRAMES:
                            rates = mt5.copy_rates_from_pos(bs, getattr(mt5, "TIMEFRAME_" + tf), 0, 261)
                            if rates is None or len(rates) < 221:
                                if (symbol, tf, "warn") not in sent:
                                    print(f"{symbol} {tf}: need at least 220 closed bars; load broker history in MT5.")
                                    sent[(symbol, tf, "warn")] = True
                                continue
                            key = (symbol, tf)
                            closed_at = int(rates[-1]["time"])
                            if sent.get(key) == closed_at:
                                continue
                            candles = closed_candles(rates, offset_seconds)
                            # Read again after history downloads so we never send an aged quote.
                            tick = mt5.symbol_info_tick(bs)
                            fresh = checked_quote(tick, clock, offset_seconds)
                            response = request(session, base, "POST", "/api/feed/candles",
                                               {"symbol": symbol, "timeframe": tf, "quote": fresh, "candles": candles})
                            sent[key] = closed_at
                            if response.get("status") == "SCANNED":
                                print(f"{symbol} {tf}: {response.get('reason')}")
                    except PermissionError:
                        raise
                    except QuoteUnavailable as exc:
                        report_wait(symbol, exc)
                    except (RuntimeError, ValueError, requests.RequestException) as exc:
                        print(f"{symbol}: {exc}", file=sys.stderr)
                if quotes and now - last_quotes >= args.interval:
                    # Tick times may age during initial 21-timeframe uploads: refresh before posting.
                    fresh_quotes = []
                    for symbol in symbols:
                        tick = mt5.symbol_info_tick(mapping.get(symbol, symbol))
                        try:
                            fresh_quotes.append({"symbol": symbol, "quote": checked_quote(tick, clock, offset_seconds)})
                        except QuoteUnavailable as exc:
                            report_wait(symbol, exc)
                    if fresh_quotes:
                        request(session, base, "POST", "/api/feed/quotes", {"quotes": fresh_quotes})
                        last_quotes = now
            except PermissionError:
                raise
            except (RuntimeError, requests.RequestException) as exc:
                print(f"Connection issue: {exc}. Retrying next cycle.", file=sys.stderr)
            time.sleep(max(5, args.interval))
    finally:
        session.close()
        mt5.shutdown()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="Public KMR HTTPS URL")
    parser.add_argument("--terminal", help="Optional path to broker's terminal64.exe")
    parser.add_argument("--symbol-map", help="JSON map: app symbols to exact broker symbols")
    parser.add_argument("--interval", type=int, default=15, help="Quote scan interval in seconds (minimum 5)")
    parser.add_argument("--broker-utc-offset-hours", type=broker_offset_hours, default=0,
                        help="Explicit broker timestamp UTC offset. Default 0; use 3 only for observed UTC+3 quote/bar timestamps.")
    try:
        run(parser.parse_args())
    except KeyboardInterrupt:
        print("Bridge stopped.")
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        if "/api/feed/time" in str(exc) or "API status 404" in str(exc):
            print("Deploy the latest KMR backend before running this bridge version.", file=sys.stderr)
        sys.exit(1)
