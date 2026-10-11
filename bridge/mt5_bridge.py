"""Read-only MT5 candle/quote bridge. Runs on Windows alongside logged-in MT5."""
import argparse
import getpass
import json
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

def run(args):
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
    session.headers["User-Agent"] = "KMR-MT5-Bridge/1.1"
    if not (mt5.initialize(args.terminal) if args.terminal else mt5.initialize()):
        raise RuntimeError(f"MT5 initialization failed: {mt5.last_error()}. Open and log into your desktop MT5 terminal.")
    if mt5.account_info() is None:
        raise RuntimeError("MT5 has no logged-in account. Log in inside MT5 first.")
    sent = {}
    symbols = []
    refreshed = 0.0
    last_quotes = 0.0
    try:
        print("KMR feed connected to MT5. Read-only: no orders are submitted.")
        print("Scanning all 21 MT5 timeframes. Keep this terminal and MT5 open.")
        while True:
            now = time.time()
            try:
                if now - refreshed >= 60:
                    symbols = request(session, base, "GET", "/api/feed/symbols")
                    refreshed = now
                quotes = []
                for symbol in symbols:
                    try:
                        bs = resolve(symbol, mapping)
                        tick = mt5.symbol_info_tick(bs)
                        if tick is None or now - tick.time > 90 or tick.bid <= 0 or tick.ask < tick.bid:
                            continue  # market closed or no fresh broker quote
                        quote = {"time": iso(tick.time), "bid": float(tick.bid), "ask": float(tick.ask)}
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
                            candles = []
                            for i in range(len(rates) - 1):
                                bar = rates[i]
                                candles.append({"time": iso(bar["time"]), "closedAt": iso(rates[i+1]["time"]),
                                                **{p: float(bar[p]) for p in ("open", "high", "low", "close")}})
                            # Read again after history downloads so we never send an aged quote.
                            tick = mt5.symbol_info_tick(bs)
                            if tick is None or time.time() - tick.time > 90:
                                continue
                            fresh = {"time": iso(tick.time), "bid": float(tick.bid), "ask": float(tick.ask)}
                            response = request(session, base, "POST", "/api/feed/candles",
                                               {"symbol": symbol, "timeframe": tf, "quote": fresh, "candles": candles})
                            sent[key] = closed_at
                            if response.get("status") == "SCANNED":
                                print(f"{symbol} {tf}: {response.get('reason')}")
                    except PermissionError:
                        raise
                    except (RuntimeError, ValueError, requests.RequestException) as exc:
                        print(f"{symbol}: {exc}", file=sys.stderr)
                if quotes and now - last_quotes >= args.interval:
                    # Tick times may age during initial 21-timeframe uploads: refresh before posting.
                    fresh_quotes = []
                    for symbol in symbols:
                        tick = mt5.symbol_info_tick(mapping.get(symbol, symbol))
                        if tick and 0 <= time.time() - tick.time <= 90 and tick.bid > 0 and tick.ask >= tick.bid:
                            fresh_quotes.append({"symbol": symbol, "quote": {"time": iso(tick.time), "bid": float(tick.bid), "ask": float(tick.ask)}})
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
    try:
        run(parser.parse_args())
    except KeyboardInterrupt:
        print("Bridge stopped.")
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
