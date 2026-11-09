#!/usr/bin/env python3
"""Tự kiểm tra cầu nối + khả năng ADB trên điện thoại thật (chạy ở phía Claude Code).

  export BRIDGE_URL='https://xxxx.lhr.life/<khoá>'
  python3 tools/selftest.py            # tất cả
  python3 tools/selftest.py -k unicode # chỉ test có tên chứa "unicode"

Không thay đổi gì trên máy ngoài file tạm trong /data/local/tmp (xoá ngay sau đó).
"""
import concurrent.futures as cf
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = os.environ.get("BRIDGE_URL", "").rstrip("/")
results = []


def bridge(*args, timeout=None, env=None):
    e = dict(os.environ, **(env or {}))
    t0 = time.time()
    p = subprocess.run([sys.executable, os.path.join(HERE, "bridge.py"), *args],
                       capture_output=True, text=True, env=e, timeout=timeout or 200)
    return p.returncode, p.stdout, p.stderr, time.time() - t0


def post(typ, cmd="", timeout=60):
    body = json.dumps({"type": typ, "cmd": cmd, "timeout": int((timeout - 5) * 1000)}).encode()
    r = urllib.request.Request(BASE + "/cmd", data=body, method="POST",
                               headers={"Content-Type": "application/json", "User-Agent": "cc-bridge-selftest"})
    with urllib.request.urlopen(r, timeout=timeout + 10) as resp:
        return json.loads(resp.read().decode())


def test(name):
    def deco(fn):
        fn.test_name = name
        return fn
    return deco


def check(cond, msg):
    if not cond:
        raise AssertionError(msg)


def ex(cmd, **kw):
    return bridge("exec", cmd, **kw)


# ───────── kết nối ─────────

@test("ping")
def t_ping():
    code, out, err, _ = bridge("ping")
    check(code == 0, f"exit {code}: {out}{err}")
    check("ADB=true" in out, f"ADB chưa kết nối: {out}")
    return out.strip()


@test("health")
def t_health():
    with urllib.request.urlopen(BASE + "/health", timeout=20) as r:
        body = r.read().decode()
    check(body.startswith("ok"), body)


@test("wrong key -> 404")
def t_wrong_key():
    root = BASE.rsplit("/", 1)[0]
    try:
        urllib.request.urlopen(urllib.request.Request(root + "/wrongkeywrongkeywrongkey/health"), timeout=20)
        raise AssertionError("không bị từ chối")
    except urllib.error.HTTPError as e:
        check(e.code == 404, f"HTTP {e.code}")


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
        check(code == want, f"{cmd!r}: exit {code} (muốn {want}) {out!r}{err!r}")


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
    check(code == 0 and lines[0] == "1" and lines[-2] == "200000", f"exit {code}, {len(lines)} dòng {err[:200]}")
    return f"{len(out) / 1e6:.1f} MB trong {dt:.1f}s"


@test("timeout -> 124, ADB vẫn sống")
def t_timeout():
    code, out, err, dt = ex("sleep 60", env={"BRIDGE_TIMEOUT": "12"})
    check(code == 124, f"exit {code} {err!r} {dt:.0f}s")
    check(dt < 30, f"mất {dt:.0f}s")
    code, out, _, _ = ex("echo still-alive")
    check(code == 0 and out == "still-alive\n", f"sau timeout: {code} {out!r}")
    return f"{dt:.0f}s"


@test("song song x6")
def t_parallel():
    with cf.ThreadPoolExecutor(6) as pool:
        futs = [pool.submit(ex, f"echo p{i}; sleep 1") for i in range(6)]
        res = [f.result() for f in futs]
    for i, (code, out, err, _) in enumerate(res):
        check(code == 0 and out == f"p{i}\n", f"#{i}: {code} {out!r} {err!r}")


# ───────── khả năng ADB ─────────

@test("adb: thông tin máy")
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
    r = post("logcat_start", "logcat -v brief -T 1", timeout=30)
    check(r.get("exit") == 0, repr(r))
    after = r.get("next") or 0
    ex("log -t CCBSELFTEST marker-12345")
    got = ""
    for _ in range(10):
        time.sleep(1)
        r = post("logs", str(after), timeout=20)
        after = r.get("next", after)
        got += r.get("output", "")
        if "marker-12345" in got:
            break
    post("logcat_stop", timeout=20)
    check("marker-12345" in got, f"không thấy dòng log, nhận {len(got)} ký tự")


def main():
    if not BASE:
        sys.exit("thiếu BRIDGE_URL")
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
    print(f"\n{'OK' if not fails else f'{fails} lỗi'}")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
