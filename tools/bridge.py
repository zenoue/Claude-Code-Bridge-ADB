#!/usr/bin/env python3
"""Claude Code ↔ ADB: gửi lệnh adb shell tới điện thoại (chạy ở phía Claude Code).

Biến môi trường (lấy bằng nút "Sao chép cho Claude Code" trong app):
  BRIDGE_URL   https://xxxx.lhr.life/<khoá>   (địa chỉ đường hầm, đã kèm khoá – không cần mật khẩu)

Dùng:
  bridge.py ping                       # kiểm tra app còn sống + ADB đã kết nối
  bridge.py exec "dumpsys battery"     # chạy lệnh, in output, thoát với exit code của lệnh
  bridge.py logcat ["logcat -v time"]  # bật stream logcat và theo dõi (Ctrl+C)
  bridge.py stop-logcat

BRIDGE_TIMEOUT (giây, mặc định 90) = thời gian chờ tối đa mỗi lệnh.
Chỉ dùng thư viện chuẩn Python 3.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("BRIDGE_URL", "").strip().rstrip("/")
TIMEOUT = float(os.environ.get("BRIDGE_TIMEOUT", "90"))
UA = "cc-bridge-adb/2.0"
RETRIES = 2  # lỗi mạng tạm thời của đường hầm (502/503/504, mất kết nối) -> thử lại


def die(m, code=1):
    print(f"Lỗi: {m}", file=sys.stderr)
    sys.exit(code)


def send(typ, cmd="", timeout=None):
    timeout = timeout or TIMEOUT
    body = {"type": typ, "cmd": cmd, "timeout": int(max(1, timeout - 5) * 1000)}  # app dừng lệnh trước khi HTTP hết giờ
    data = json.dumps(body).encode()
    last = None
    for attempt in range(RETRIES + 1):
        r = urllib.request.Request(f"{BASE}/cmd", data=data, method="POST",
                                   headers={"Content-Type": "application/json", "User-Agent": UA})
        try:
            with urllib.request.urlopen(r, timeout=timeout + 10) as resp:
                d = json.loads(resp.read().decode())
            return d.get("exit"), d.get("output", ""), d.get("next")
        except urllib.error.HTTPError as e:
            txt = e.read().decode(errors="replace")
            if e.code == 404:
                die("địa chỉ sai hoặc khoá đã đổi – sao chép lại BRIDGE_URL từ app")
            if e.code in (502, 503, 504) and typ in ("ping", "logs"):
                last = f"HTTP {e.code}: {txt[:200]}"
            else:
                die(f"HTTP {e.code} từ đường hầm (app tắt hoặc địa chỉ đã đổi?): {txt[:200]}")
        except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
            # lệnh exec có thể đã chạy -> không gửi lại để tránh chạy hai lần
            if typ not in ("ping", "logs"):
                die(f"không gọi được {BASE}: {e}")
            last = str(e)
        if attempt < RETRIES:
            time.sleep(2 * (attempt + 1))
    die(f"không gọi được {BASE}: {last} – app tắt hoặc địa chỉ đã đổi?")


# ───────── lệnh ─────────

def cmd_exec(args):
    if not args:
        die("thiếu lệnh")
    code, out, _ = send("exec", " ".join(args))
    sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
    if code:
        print(f"[exit {code}]", file=sys.stderr)
    sys.exit(code if isinstance(code, int) and 0 <= code < 256 else 1)


def cmd_ping(_):
    code, out, _ = send("ping", timeout=20)
    print(out)
    sys.exit(0 if code == 0 else 1)


def follow(after):
    while True:
        code, out, nxt = send("logs", str(after), timeout=20)
        if out:
            sys.stdout.write(out)
            sys.stdout.flush()
        after = nxt if nxt is not None else after
        if code != 0 and not out:
            print("(stream đã dừng)", file=sys.stderr)
            return
        time.sleep(1.5)


def cmd_logcat(args):
    code, out, nxt = send("logcat_start", " ".join(args), timeout=30)
    print(out, file=sys.stderr)
    if code:
        sys.exit(1)
    print("Đang stream (Ctrl+C để ngừng xem). Tắt hẳn: bridge.py stop-logcat", file=sys.stderr)
    try:
        follow(nxt or 0)
    except KeyboardInterrupt:
        print("\n(ngừng theo dõi)", file=sys.stderr)


def cmd_stop(_):
    print(send("logcat_stop")[1])


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print(__doc__)
        return
    if not BASE:
        die("thiếu BRIDGE_URL (nút \"Sao chép cho Claude Code\" trong app)")
    if not BASE.startswith("https://") or BASE.count("/") < 3:
        die("BRIDGE_URL phải có dạng https://xxxx.lhr.life/<khoá> – sao chép lại từ app")
    {
        "exec": cmd_exec, "ping": cmd_ping, "logcat": cmd_logcat,
        "stop-logcat": cmd_stop,
    }.get(sys.argv[1], lambda _: die(f"lệnh không rõ: {sys.argv[1]}"))(sys.argv[2:])


if __name__ == "__main__":
    main()
