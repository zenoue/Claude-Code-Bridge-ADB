#!/usr/bin/env python3
"""Claude Code <-> ADB: send adb shell commands to the phone (runs on the Claude Code side).

Environment (from the "Copy for Claude Code" / "Sao chép cho Claude Code" button in the app):
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
  bridge.py console                    # the app's own log (CLI tab): tunnel / ADB events

Chrome DevTools on the phone (Chrome or any Chromium app / WebView with a DevTools socket):
  bridge.py chrome tabs                          # open tabs: number, id, title, URL
  bridge.py chrome open https://example.com      # new tab, prints its id
  bridge.py chrome eval 1 "document.title"       # run JavaScript in tab 1, print the result
  bridge.py chrome call 1 Page.reload            # any DevTools Protocol method, optional JSON params
  bridge.py chrome screenshot 1 shot.jpg         # .jpg or .png; the tab must be visible on screen
  bridge.py chrome activate 1 | close 1          # bring a tab to the front | close it
  bridge.py chrome sockets                       # DevTools sockets on the phone
  A tab is given by its number in `chrome tabs`, its id (or the start of it), or `browser` (call only).
  The browser must be on screen: Android freezes it in the background (Chrome is opened automatically).
  BRIDGE_CHROME_SOCKET picks another socket from `chrome sockets` (default chrome_devtools_remote).

BRIDGE_TIMEOUT (seconds, default 90) = max time per command.
Python 3 standard library only.
"""
import base64
import hashlib
import http.client
import hmac
import json
import os
import gzip
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
                                 headers={"Content-Type": "application/json", "User-Agent": UA,
                                          "Accept-Encoding": "gzip"})
    try:
        with urllib.request.urlopen(req, timeout=timeout + 10) as resp:
            d = json.loads(_body(resp).decode())
        if not isinstance(d, dict) or "exit" not in d:
            raise ValueError("not a bridge reply")
        return d
    except urllib.error.HTTPError as e:
        txt = _body(e).decode(errors="replace")
        try:
            d = json.loads(txt)
        except ValueError:
            d = None
        if isinstance(d, dict) and "exit" in d:
            return d  # the app answered (bad signature, replay, ...)
        # anything else comes from the tunnel provider: no such tunnel / app offline
        raise Unreachable(f"HTTP {e.code} from {base}: {txt[:120].strip()}")
    except http.client.IncompleteRead as e:
        # the command ran; only the reply was cut by the tunnel -> do not resend
        raise BridgeError(f"reply cut off by the tunnel after {len(e.partial)} bytes; "
                          "use less output (e.g. | head, | tail, > file) and retry")
    except ValueError as e:
        # some other server answers on this (stale) URL
        raise Unreachable(f"{base}: {e}")
    except (urllib.error.URLError, OSError) as e:
        if isinstance(e, TimeoutError) or isinstance(getattr(e, "reason", None), TimeoutError):
            # do not fail over: the command may still be running on the phone
            raise BridgeError(f"no response from {base} within {int(timeout + 10)}s")
        raise Unreachable(f"{base}: {e}")


def _body(resp):
    raw = resp.read()
    if resp.headers.get("Content-Encoding", "").lower() == "gzip":
        raw = gzip.decompress(raw)
    return raw


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
                          f"{RENDEZVOUS}); check the 'Bridge' ('Cầu nối') card in the app, or set BRIDGE_URL")
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


def cmd_console(_):
    print(send("console", timeout=20)[1])


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


# ───────── Chrome DevTools ─────────

CHROME_SOCKET = os.environ.get("BRIDGE_CHROME_SOCKET", "").strip()


CHROME_START = "am start -n com.android.chrome/com.google.android.apps.chrome.Main"


def cdp(req, timeout=None):
    """One DevTools request through the app; large replies arrive in parts and are joined here."""
    if CHROME_SOCKET:
        req = dict(req, socket=CHROME_SOCKET)
    # Android freezes a browser in the background: its DevTools socket accepts but never answers.
    # Probe with a short timeout first; for Chrome, bring it to the front once and try again.
    code, out, nxt = send("cdp", json.dumps(req), 15 if req.get("op") == "http" else timeout)
    if code == 1 and "no answer from the browser" in out and not CHROME_SOCKET:
        print("(Chrome is in the background - opening it on the phone)", file=sys.stderr)
        if send("exec", CHROME_START, 20)[0] == 0:
            time.sleep(1.5)
            code, out, nxt = send("cdp", json.dumps(req), timeout)
    while code == 0 and nxt is not None:
        code, more, nxt = send("cdp", json.dumps({"op": "read", "offset": nxt}), 30)
        out += more if code == 0 else ""
    if code != 0:
        note = f" ({APP_ERRORS[code]})" if code in APP_ERRORS else ""
        die(f"{out.strip() or 'chrome request failed'} [exit {code}{note}]")
    return out


def chrome_tabs():
    return [t for t in json.loads(cdp({"op": "http", "path": "/json/list"}, 30)) if t.get("type") == "page"]


def chrome_target(ref):
    """WebSocket path of a tab given by number, id or id prefix; `browser` is the browser itself."""
    if not ref:
        die("missing tab; run: bridge.py chrome tabs")
    if ref == "browser":
        url = json.loads(cdp({"op": "http", "path": "/json/version"}, 30)).get("webSocketDebuggerUrl", "")
    else:
        tabs = chrome_tabs()
        if ref.isdigit() and 1 <= int(ref) <= len(tabs):
            hit = [tabs[int(ref) - 1]]
        else:
            hit = [t for t in tabs if t.get("id", "").lower().startswith(ref.lower())]
        if len(hit) != 1:
            die(f"tab '{ref}' not found; run: bridge.py chrome tabs" if not hit else f"'{ref}' matches {len(hit)} tabs")
        url = hit[0].get("webSocketDebuggerUrl", "")
    i = url.find("/devtools/")
    if i < 0:
        die("this target has no DevTools address (another DevTools client may be attached)")
    return url[i:]


def chrome_call(ref, method, params=None, timeout=None):
    msg = json.loads(cdp({"op": "call", "path": chrome_target(ref), "cdp": method, "params": params or {}}, timeout))
    if "error" in msg:
        die(f"{method}: {msg['error'].get('message', msg['error'])}")
    return msg.get("result", {})


def cmd_chrome(args):
    sub, rest = (args[0] if args else ""), args[1:]

    def need(n, usage):
        if len(rest) < n:
            die(f"usage: bridge.py chrome {usage}")

    if sub == "sockets":
        print(cdp({"op": "sockets"}, 30))
    elif sub == "tabs":
        for n, t in enumerate(chrome_tabs(), 1):
            print(f"{n}\t{t.get('id', '')}\t{t.get('title', '')}\t{t.get('url', '')}")
    elif sub == "open":
        need(1, "open URL")
        # Chrome on Android has no /json/new; a new tab is created through the browser target
        print(chrome_call("browser", "Target.createTarget", {"url": rest[0]}).get("targetId", ""))
    elif sub in ("close", "activate"):
        need(1, f"{sub} TAB")
        print(cdp({"op": "http", "path": f"/json/{sub}/" + chrome_target(rest[0]).rsplit("/", 1)[1]}, 30))
    elif sub == "eval":
        need(2, 'eval TAB "javascript"')
        r = chrome_call(rest[0], "Runtime.evaluate", {"expression": " ".join(rest[1:]), "returnByValue": True,
                                                       "awaitPromise": True, "userGesture": True})
        if "exceptionDetails" in r:
            e = r["exceptionDetails"]
            die(e.get("exception", {}).get("description") or e.get("text", "JavaScript error"))
        res = r.get("result", {})
        val = res.get("value", res.get("description", res.get("type", "")))
        print(val if isinstance(val, str) else json.dumps(val, ensure_ascii=False, indent=1))
    elif sub == "call":
        need(2, "call TAB Domain.method ['{\"json\": \"params\"}']")
        try:
            params = json.loads(rest[2]) if len(rest) > 2 else {}
        except ValueError as e:
            die(f"params are not valid JSON: {e}")
        print(json.dumps(chrome_call(rest[0], rest[1], params), ensure_ascii=False, indent=1))
    elif sub == "screenshot":
        need(2, "screenshot TAB file.jpg|file.png")
        png = rest[1].lower().endswith(".png")
        r = chrome_call(rest[0], "Page.captureScreenshot", {"format": "png"} if png else {"format": "jpeg", "quality": 60})
        with open(rest[1], "wb") as f:
            f.write(base64.b64decode(r.get("data", "")))
        print(f"saved {rest[1]} ({os.path.getsize(rest[1])} bytes)")
    else:
        die("usage: bridge.py chrome tabs|open|eval|call|screenshot|activate|close|sockets (see bridge.py --help)")


def cmd_stop(_):
    print(send("logcat_stop")[1])


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print(__doc__)
        return
    if not PASSWORD:
        die("BRIDGE_PASSWORD is not set")
    if not BRIDGE_ID and not DIRECT_URL:
        die("BRIDGE_ID is not set (use the \"Copy for Claude Code\" button in the app)")
    {
        "exec": cmd_exec, "ping": cmd_ping, "logcat": cmd_logcat,
        "stop-logcat": cmd_stop, "url": cmd_url, "console": cmd_console,
        "chrome": cmd_chrome,
    }.get(sys.argv[1], lambda _: die(f"unknown command: {sys.argv[1]}"))(sys.argv[2:])


if __name__ == "__main__":
    main()
