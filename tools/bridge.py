#!/usr/bin/env python3
"""Claude Code <-> ADB: send adb shell commands to the phone (runs on the Claude Code side).

Environment (from the "Sao chép cho Claude Code" button in the app):
  BRIDGE_ID        fixed address, e.g. ccb-xxxxxxxx. Never changes; the app publishes its current
                   tunnel URL under it, and this tool looks the URL up whenever needed.
  BRIDGE_PASSWORD  shared password. Never sent anywhere; it only signs each command (HMAC-SHA256).
  BRIDGE_URL       optional: current tunnel URL (https://xxxx.lhr.life), used if the lookup fails.

Usage:
  bridge.py ping                       # app alive? ADB connected?
  bridge.py exec "dumpsys battery"     # run a command, print output, exit with its exit code
  bridge.py logcat ["logcat -v time"]  # start a logcat stream and follow it (Ctrl+C)
  bridge.py stop-logcat
  bridge.py url                        # print the current tunnel URL

BRIDGE_TIMEOUT (seconds, default 90) = max time per command.
Python 3 standard library only.
"""
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request

BRIDGE_ID = os.environ.get("BRIDGE_ID", "").strip()
PASSWORD = os.environ.get("BRIDGE_PASSWORD", "")
DIRECT_URL = os.environ.get("BRIDGE_URL", "").strip().rstrip("/")
RENDEZVOUS = os.environ.get("BRIDGE_RENDEZVOUS", "https://ntfy.sh").rstrip("/")
TIMEOUT = float(os.environ.get("BRIDGE_TIMEOUT", "90"))
UA = "cc-bridge-adb/3.0"
CACHE_DIR = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "cc-bridge-adb")

# Exit codes reported by the app (negative = the command did not run).
APP_ERRORS = {-2: "wrong password", -3: "blocked (read-only mode)", -4: "ADB not connected on the phone",
              -5: "clock skew > 5 min", -6: "duplicate request"}


class BridgeError(Exception):
    pass


class Unreachable(BridgeError):
    """The tunnel URL did not answer; the request did not reach the app."""


def die(m, code=1):
    print(f"Error: {m}", file=sys.stderr)
    sys.exit(code)


# ───────── fixed address -> current tunnel URL ─────────

def _sign_pointer(ts, url):
    return hmac.new(PASSWORD.encode(), f"ptr\n{ts}\n{url}".encode(), hashlib.sha256).hexdigest()


def _cache_file():
    return os.path.join(CACHE_DIR, f"{BRIDGE_ID}.url")


def _cached_url():
    try:
        with open(_cache_file()) as f:
            return f.read().strip() or None
    except OSError:
        return None


def _store_url(url):
    try:
        os.makedirs(CACHE_DIR, exist_ok=True)
        with open(_cache_file(), "w") as f:
            f.write(url)
    except OSError:
        pass


def lookup():
    """Newest correctly signed tunnel URL published under BRIDGE_ID, or None."""
    req = urllib.request.Request(f"{RENDEZVOUS}/{BRIDGE_ID}/json?poll=1&since=all", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as resp:
        text = resp.read().decode(errors="replace")
    best_ts, best = -1, None
    for line in text.splitlines():
        try:
            ev = json.loads(line)
            if ev.get("event") != "message":
                continue
            rec = json.loads(ev.get("message", ""))
            url, ts, sig = rec["url"], int(rec["ts"]), rec["sig"]
        except (ValueError, KeyError, TypeError):
            continue
        if hmac.compare_digest(_sign_pointer(ts, url), str(sig)) and ts > best_ts:
            best_ts, best = ts, url.rstrip("/")
    return best


def candidates():
    """Tunnel URLs to try, in order; the lookup only happens when the earlier ones fail."""
    seen = []

    def emit(url):
        if url and url not in seen:
            seen.append(url)
            return True
        return False

    if BRIDGE_ID:
        cached = _cached_url()
        if emit(cached):
            yield cached
        try:
            found = lookup()
        except Exception as e:  # rendezvous unreachable: fall through to BRIDGE_URL
            found = None
            print(f"(fixed address lookup failed: {e})", file=sys.stderr)
        if emit(found):
            yield found
    if emit(DIRECT_URL):
        yield DIRECT_URL


# ───────── protocol ─────────

def _signed(typ, cmd, timeout):
    seq = int(time.time() * 1000)
    nonce = secrets.token_hex(12)
    msg = f"{seq}\n{seq}\n{typ}\n{nonce}\n{cmd}".encode()
    sig = hmac.new(PASSWORD.encode(), msg, hashlib.sha256).hexdigest()
    # the app stops the command before the HTTP request times out
    return {"seq": seq, "ts": seq, "type": typ, "cmd": cmd, "nonce": nonce, "sig": sig,
            "timeout": int(max(1, timeout - 5) * 1000)}


def _post(base, data, timeout):
    """POST one signed request. Raises Unreachable when it certainly did not reach the app."""
    req = urllib.request.Request(f"{base}/cmd", data=data, method="POST",
                                 headers={"Content-Type": "application/json", "User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout + 10) as resp:
            d = json.loads(resp.read().decode())
        if not isinstance(d, dict) or "exit" not in d:
            raise ValueError("not a bridge reply")
        return d
    except urllib.error.HTTPError as e:
        txt = e.read().decode(errors="replace")
        try:
            d = json.loads(txt)
        except ValueError:
            d = None
        if isinstance(d, dict) and "exit" in d:
            return d  # the app answered (bad signature, replay, ...)
        # anything else comes from the tunnel provider: no such tunnel / app offline
        raise Unreachable(f"HTTP {e.code} from {base}: {txt[:120].strip()}")
    except ValueError as e:
        # some other server answers on this (stale) URL
        raise Unreachable(f"{base}: {e}")
    except (urllib.error.URLError, OSError) as e:
        if isinstance(e, TimeoutError) or isinstance(getattr(e, "reason", None), TimeoutError):
            # do not fail over: the command may still be running on the phone
            raise BridgeError(f"no response from {base} within {int(timeout + 10)}s")
        raise Unreachable(f"{base}: {e}")


def call(typ, cmd="", timeout=None):
    """Send a command; returns (exit, output, next). Raises BridgeError."""
    timeout = timeout or TIMEOUT
    # One signed body for all attempts: if an attempt did reach the app, the app rejects the
    # repeated nonce instead of running the command twice.
    data = json.dumps(_signed(typ, cmd, timeout)).encode()
    errors = []
    for base in candidates():
        try:
            d = _post(base, data, timeout)
        except Unreachable as e:
            errors.append(str(e))
            continue
        if BRIDGE_ID:
            _store_url(base)
        return d.get("exit"), d.get("output", ""), d.get("next")
    if errors:
        raise BridgeError("; ".join(errors[-2:]) + " - no reachable tunnel; is the bridge running in the app?")
    if BRIDGE_ID:
        raise BridgeError(f"no tunnel address published under {BRIDGE_ID} yet (the app has not posted to "
                          f"{RENDEZVOUS}); check the 'Cầu nối' card in the app, or set BRIDGE_URL")
    raise BridgeError("set BRIDGE_ID (or BRIDGE_URL)")


def send(typ, cmd="", timeout=None):
    try:
        return call(typ, cmd, timeout)
    except BridgeError as e:
        die(str(e))


# ───────── commands ─────────

def cmd_exec(args):
    if not args:
        die("missing command")
    code, out, _ = send("exec", " ".join(args))
    sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
    if code:
        note = f" ({APP_ERRORS[code]})" if code in APP_ERRORS else ""
        print(f"[exit {code}{note}]", file=sys.stderr)
    sys.exit(code if isinstance(code, int) and 0 <= code < 256 else 1)


def cmd_ping(_):
    code, out, _ = send("ping", timeout=20)
    print(out)
    sys.exit(0 if code == 0 else 1)


def cmd_url(_):
    for base in candidates():
        print(base)
        return
    die(f"no tunnel URL known (nothing published under {BRIDGE_ID or '-'} yet)")


def follow(after):
    while True:
        code, out, nxt = send("logs", str(after), timeout=20)
        if out:
            sys.stdout.write(out)
            sys.stdout.flush()
        after = nxt if nxt is not None else after
        if code != 0 and not out:
            print("(stream stopped)", file=sys.stderr)
            return
        time.sleep(1.5)


def cmd_logcat(args):
    code, out, nxt = send("logcat_start", " ".join(args), timeout=30)
    print(out, file=sys.stderr)
    if code:
        sys.exit(1)
    print("Streaming (Ctrl+C to stop watching). Stop the stream: bridge.py stop-logcat", file=sys.stderr)
    try:
        follow(nxt or 0)
    except KeyboardInterrupt:
        print("\n(stopped watching)", file=sys.stderr)


def cmd_stop(_):
    print(send("logcat_stop")[1])


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print(__doc__)
        return
    if not PASSWORD:
        die("BRIDGE_PASSWORD is not set")
    if not BRIDGE_ID and not DIRECT_URL:
        die("BRIDGE_ID is not set (use the \"Sao chép cho Claude Code\" button in the app)")
    {
        "exec": cmd_exec, "ping": cmd_ping, "logcat": cmd_logcat,
        "stop-logcat": cmd_stop, "url": cmd_url,
    }.get(sys.argv[1], lambda _: die(f"unknown command: {sys.argv[1]}"))(sys.argv[2:])


if __name__ == "__main__":
    main()
