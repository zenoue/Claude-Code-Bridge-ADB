#!/usr/bin/env python3
"""Claude Code ↔ ADB qua Supabase (chạy ở phía Claude Code).

Ghi lệnh đã ký vào bảng mailbox và đọc kết quả/log. Chỉ dùng thư viện chuẩn.

Biến môi trường:
  BRIDGE_SUPABASE_URL   https://xxxx.supabase.co   (bắt buộc)
  BRIDGE_SUPABASE_KEY   API key (service_role hoặc key có quyền bảng) (bắt buộc)
  BRIDGE_PASSWORD       mật khẩu lấy từ app (bắt buộc)

Dùng:
  bridge.py exec "dumpsys battery"     # chạy lệnh adb shell, in output, thoát với exit code của lệnh
  bridge.py ping                       # kiểm tra app còn sống + ADB đã kết nối
  bridge.py logcat ["logcat -v time"]  # bật stream logcat và theo dõi (Ctrl+C)
  bridge.py stop-logcat
  bridge.py watch                      # chỉ theo dõi log stream
  bridge.py cleanup [giờ]              # xoá hàng cũ hơn N giờ (mặc định 24)

Biến BRIDGE_TIMEOUT (giây, mặc định 90) đặt thời gian chờ kết quả.
"""
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.parse
import urllib.error
import urllib.request

URL = os.environ.get("BRIDGE_SUPABASE_URL", "").rstrip("/")
KEY = os.environ.get("BRIDGE_SUPABASE_KEY", "")
PASSWORD = os.environ.get("BRIDGE_PASSWORD", "")


def die(m):
    print(f"Lỗi: {m}", file=sys.stderr)
    sys.exit(1)


def req(method, path, body=None):
    url = f"{URL}/rest/v1/{path}"
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("apikey", KEY)
    r.add_header("Authorization", f"Bearer {KEY}")
    r.add_header("Accept", "application/json")
    if data is not None:
        r.add_header("Content-Type", "application/json")
        r.add_header("Prefer", "return=minimal")
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode()
            return json.loads(txt) if txt.strip() else []
    except urllib.error.HTTPError as e:
        die(f"{e.code} {e.read().decode()[:200]}")
    except Exception as e:
        die(str(e))


def sign(seq, ts, typ, cmd, nonce):
    msg = f"{seq}\n{ts}\n{typ}\n{nonce}\n{cmd}".encode()
    return hmac.new(PASSWORD.encode(), msg, hashlib.sha256).hexdigest()


def send(typ, cmd=""):
    seq = int(time.time() * 1000)
    ts = seq
    nonce = secrets.token_hex(8)
    req("POST", "mailbox", [{
        "dir": "cmd", "seq": seq, "ts": ts, "type": typ, "cmd": cmd,
        "nonce": nonce, "sig": sign(seq, ts, typ, cmd, nonce),
    }])
    return seq


def wait_result(seq, timeout=None):
    timeout = timeout or float(os.environ.get("BRIDGE_TIMEOUT", "90"))
    print(f"Đã gửi #{seq}, chờ kết quả…", file=sys.stderr)
    start = time.time()
    while time.time() - start < timeout:
        rows = req("GET", f"mailbox?dir=eq.res&seq=eq.{seq}&order=part.asc&select=part,parts,exit,output")
        if rows:
            parts = {r["part"]: r for r in rows}
            if len(parts) >= rows[0].get("parts", 1):
                out = "".join(parts[i]["output"] for i in sorted(parts))
                return rows[0].get("exit"), out
        time.sleep(1.5)
    die("hết giờ chờ kết quả (app đang chạy chưa?)")


def last_log_id():
    rows = req("GET", "mailbox?dir=eq.log&order=id.desc&limit=1&select=id")
    return rows[0]["id"] if rows else 0


def follow_logs(since_id):
    try:
        while True:
            rows = req("GET", f"mailbox?dir=eq.log&id=gt.{since_id}&order=id.asc&select=id,output")
            for r in rows:
                sys.stdout.write(r.get("output", ""))
                since_id = max(since_id, r["id"])
            sys.stdout.flush()
            time.sleep(2)
    except KeyboardInterrupt:
        print("\n(ngừng theo dõi)", file=sys.stderr)


def cmd_exec(args):
    if not args:
        die("thiếu lệnh")
    code, out = wait_result(send("exec", " ".join(args)))
    sys.stdout.write(out if out.endswith("\n") or not out else out + "\n")
    if code:
        print(f"[exit {code}]", file=sys.stderr)
    sys.exit(code if isinstance(code, int) and 0 <= code < 256 else 1)


def cmd_ping(_):
    _, out = wait_result(send("ping"), timeout=20)
    print(out)


def cmd_logcat(args):
    since = last_log_id()
    wait_result(send("logcat_start", " ".join(args) if args else ""), timeout=30)
    print("Đang stream logcat (Ctrl+C để dừng). Dừng hẳn: bridge.py stop-logcat", file=sys.stderr)
    follow_logs(since)


def cmd_stop(_):
    _, out = wait_result(send("logcat_stop"))
    print(out)


def cmd_watch(_):
    follow_logs(last_log_id())


def cmd_cleanup(args):
    hours = float(args[0]) if args else 24.0
    cutoff = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - hours * 3600))
    req("DELETE", "mailbox?created_at=lt." + urllib.parse.quote(cutoff))
    print(f"Đã xoá hàng cũ hơn {hours:g} giờ.")


def main():
    if not URL:
        die("thiếu BRIDGE_SUPABASE_URL")
    if not KEY:
        die("thiếu BRIDGE_SUPABASE_KEY")
    if not PASSWORD:
        die("thiếu BRIDGE_PASSWORD")
    if len(sys.argv) < 2:
        print(__doc__)
        return
    {
        "exec": cmd_exec, "ping": cmd_ping, "logcat": cmd_logcat,
        "stop-logcat": cmd_stop, "watch": cmd_watch, "cleanup": cmd_cleanup,
    }.get(sys.argv[1], lambda _: die(f"lệnh không rõ: {sys.argv[1]}"))(sys.argv[2:])


if __name__ == "__main__":
    main()
