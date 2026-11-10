#!/usr/bin/env python3
"""Self-test of the bridge and of ADB capabilities on a real phone (runs on the Claude Code side).

  export BRIDGE_ID='ccb-...'; export BRIDGE_PASSWORD='...'
  python3 tools/selftest.py            # everything
  python3 tools/selftest.py -k unicode # only tests whose name contains "unicode"

Changes nothing on the phone except a temporary file in /data/local/tmp (removed right away).
Needs "Cho phép mọi lệnh" enabled in the app for the full run.
"""
import concurrent.futures as cf
import os
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import bridge  # noqa: E402


def run(*args, timeout=None, env=None):
    """Run bridge.py as a subprocess; returns (exit code, stdout, stderr, seconds)."""
    e = dict(os.environ, **(env or {}))
    t0 = time.time()
    p = subprocess.run([sys.executable, os.path.join(HERE, "bridge.py"), *args],
                       capture_output=True, text=True, env=e, timeout=timeout or 200)
    return p.returncode, p.stdout, p.stderr, time.time() - t0


def ex(cmd, **kw):
    return run("exec", cmd, **kw)


def test(name):
    def deco(fn):
        fn.test_name = name
        return fn
    return deco


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


# ───────── connection ─────────

@test("ping")
def t_ping():
    code, out, err, _ = run("ping")
    check(code == 0, f"exit {code}: {out}{err}")
    check("adb=true" in out, f"ADB not connected: {out}")
    return out.strip()


@test("fixed address resolves")
def t_lookup():
    if not bridge.BRIDGE_ID:
        return "skipped (no BRIDGE_ID)"
    url = bridge.lookup()
    check(url and url.startswith("https://"), f"no signed record under {bridge.BRIDGE_ID}")
    return url


@test("health")
def t_health():
    code, out, err, _ = run("url")
    check(code == 0, err)
    with urllib.request.urlopen(out.strip() + "/health", timeout=20) as r:
        body = r.read().decode()
    check(body.startswith("CC Bridge ADB"), body)


@test("wrong password rejected")
def t_wrong_password():
    code, out, err, _ = ex("echo should-not-run", env={"BRIDGE_PASSWORD": "definitely-wrong-password"})
    check(code == 1 and "should-not-run" not in out and "-2" in err, repr((code, out, err)))


# ───────── exec ─────────

@test("echo")
def t_echo():
    code, out, err, dt = ex("echo hello")
    check(code == 0 and out == "hello\n", repr((code, out, err)))
    return f"{dt:.2f}s"


@test("exit codes")
def t_exit():
    for cmd, want in [("true", 0), ("false", 1), ("exit 3", 3), ("nosuchcmd_xyz", 127), ("ls /nonexistent_dir", 1)]:
        code, out, err, _ = ex(cmd)
        check(code == want, f"{cmd!r}: exit {code} (want {want}) {out!r}{err!r}")


@test("stderr captured")
def t_stderr():
    code, out, _, _ = ex("ls /nonexistent_dir")
    check("No such file" in out, repr(out))


@test("no trailing newline")
def t_printf():
    code, out, err, _ = ex("printf abc")
    check(code == 0 and out.rstrip("\n") == "abc", repr((code, out, err)))


@test("unicode")
def t_unicode():
    s = "Tiếng Việt ✓ 日本 🚀"
    code, out, err, _ = ex(f"echo '{s}'")
    check(code == 0 and out.strip() == s, repr((code, out, err)))


@test("quotes & specials")
def t_quotes():
    code, out, err, _ = ex("""echo "a  b" 'c$d' `echo e` $((1+2)) | tr a-z A-Z; echo 'x"y'""")
    check(code == 0 and out == 'A  B C$D E 3\nx"y\n', repr((code, out, err)))


@test("comment / background / multiline")
def t_misc():
    code, out, _, _ = ex("echo hi # comment")
    check(code == 0 and out == "hi\n", repr(out))
    code, out, _, _ = ex("echo bg & wait")
    check(code == 0 and out == "bg\n", repr(out))
    code, out, _, _ = ex("for i in 1 2 3; do\n echo l$i\ndone")
    check(code == 0 and out == "l1\nl2\nl3\n", repr(out))


@test("large output (~1.3 MB)")
def t_large():
    code, out, err, dt = ex("seq 1 200000")
    lines = out.split("\n")
    check(code == 0 and lines[0] == "1" and lines[-2] == "200000", f"exit {code}, {len(lines)} lines {err[:200]}")
    return f"{len(out) / 1e6:.1f} MB in {dt:.1f}s"


@test("timeout -> 124, ADB still alive")
def t_timeout():
    code, out, err, dt = ex("sleep 60", env={"BRIDGE_TIMEOUT": "12"})
    check(code == 124, f"exit {code} {err!r} {dt:.0f}s")
    check(dt < 30, f"took {dt:.0f}s")
    code, out, _, _ = ex("echo still-alive")
    check(code == 0 and out == "still-alive\n", f"after timeout: {code} {out!r}")
    return f"{dt:.0f}s"


@test("parallel x6")
def t_parallel():
    with cf.ThreadPoolExecutor(6) as pool:
        futs = [pool.submit(ex, f"echo p{i}; sleep 1") for i in range(6)]
        res = [f.result() for f in futs]
    for i, (code, out, err, _) in enumerate(res):
        check(code == 0 and out == f"p{i}\n", f"#{i}: {code} {out!r} {err!r}")


# ───────── ADB capabilities ─────────

@test("adb: device info")
def t_props():
    code, out, _, _ = ex("getprop ro.build.version.release; getprop ro.product.model; id -u")
    check(code == 0 and out.strip().split("\n")[-1] == "2000", repr(out))
    return " | ".join(out.split())


@test("adb: dumpsys / pm / settings / wm / cmd")
def t_services():
    for cmd, needle in [("dumpsys battery", "level"), ("pm list packages android", "package:android"),
                        ("settings get global adb_enabled", "1"), ("wm size", "Physical size"),
                        ("cmd package list packages -3 | head -3", ""), ("dumpsys activity activities | head -5", "")]:
        code, out, err, _ = ex(cmd)
        check(code == 0 and needle in out, f"{cmd!r}: exit {code} {out[:200]!r}{err[:200]!r}")


@test("adb: screencap + file")
def t_screencap():
    code, out, err, _ = ex("screencap -p /data/local/tmp/ccb_test.png && wc -c < /data/local/tmp/ccb_test.png; "
                           "rm -f /data/local/tmp/ccb_test.png")
    check(code == 0 and out.strip().isdigit() and int(out.strip()) > 1000, repr((code, out, err)))
    return f"{int(out.strip()) // 1024} KB"


@test("adb: logcat -d")
def t_logcat_dump():
    code, out, err, _ = ex("logcat -d -t 20")
    check(code == 0 and out.count("\n") >= 5, repr((code, out[:200], err)))


@test("logcat stream")
def t_logcat_stream():
    code, out, nxt = bridge.call("logcat_start", "logcat -v brief -T 1", timeout=30)
    check(code == 0, repr((code, out)))
    after = nxt or 0
    ex("log -t CCBSELFTEST marker-12345")
    got = ""
    for _ in range(10):
        time.sleep(1)
        _, out, nxt = bridge.call("logs", str(after), timeout=20)
        after = nxt if nxt is not None else after
        got += out
        if "marker-12345" in got:
            break
    bridge.call("logcat_stop", timeout=20)
    check("marker-12345" in got, f"log line not seen, got {len(got)} chars")


def main():
    if not bridge.PASSWORD or not (bridge.BRIDGE_ID or bridge.DIRECT_URL):
        sys.exit("BRIDGE_ID and BRIDGE_PASSWORD must be set")
    only = sys.argv[sys.argv.index("-k") + 1] if "-k" in sys.argv else ""
    tests = [v for v in globals().values() if callable(v) and hasattr(v, "test_name")]
    fails = 0
    for fn in tests:
        if only and only not in fn.test_name:
            continue
        t0 = time.time()
        try:
            info = fn()
            print(f"PASS  {fn.test_name}" + (f"  ({info})" if info else ""), flush=True)
        except Exception as e:
            fails += 1
            print(f"FAIL  {fn.test_name}: {e}  [{time.time() - t0:.1f}s]", flush=True)
    print(f"\n{'OK' if not fails else f'{fails} failed'}")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
