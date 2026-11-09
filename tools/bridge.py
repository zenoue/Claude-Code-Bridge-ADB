#!/usr/bin/env python3
"""Claude Code ↔ ADB: gửi lệnh adb shell tới điện thoại (chạy ở phía Claude Code).

Hai kênh (tự chọn theo biến môi trường, ưu tiên đường hầm):
  Đường hầm  BRIDGE_URL=https://xxxx.lhr.life   (địa chỉ hiện trong app, tab Connect)
  Supabase   BRIDGE_SUPABASE_URL + BRIDGE_SUPABASE_KEY
Luôn cần   BRIDGE_PASSWORD  (mật khẩu ký lệnh trong app; không bao giờ gửi đi, chỉ dùng để ký HMAC)

Dùng:
  bridge.py ping                       # kiểm tra app còn sống + ADB đã kết nối
  bridge.py exec "dumpsys battery"     # chạy lệnh, in output, thoát với exit code của lệnh
  bridge.py logcat ["logcat -v time"]  # bật stream logcat và theo dõi (Ctrl+C)
  bridge.py stop-logcat
  bridge.py cleanup [giờ]              # (Supabase) xoá hàng cũ hơn N giờ, mặc định 24

BRIDGE_TIMEOUT (giây, mặc định 90) = thời gian chờ tối đa mỗi lệnh.
Chỉ dùng thư viện chuẩn Python 3.
"""
import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TUNNEL = os.environ.get("BRIDGE_URL", "").rstrip("/")
SUPA_URL = os.environ.get("BRIDGE_SUPABASE_URL", "").rstrip("/")
SUPA_KEY = os.environ.get("BRIDGE_SUPABASE_KEY", "")
PASSWORD = os.environ.get("BRIDGE_PASSWORD", "")
TIMEOUT = float(os.environ.get("BRIDGE_TIMEOUT", "90"))
UA = "cc-bridge-adb/1.0"


def die(m, code=1):
    print(f"Lỗi: {m}", file=sys.stderr)
    sys.exit(code)


def signed(typ, cmd=""):
    seq = int(time.time() * 1000)
    nonce = secrets.token_hex(12)
    msg = f"{seq}\n{seq}\n{typ}\n{nonce}\n{cmd}".encode()
    sig = hmac.new(PASSWORD.encode(), msg, hashlib.sha256).hexdigest()
    return {"seq": seq, "ts": seq, "type": typ, "cmd": cmd, "nonce": nonce, "sig": sig}


# ───────── kênh đường hầm (HTTP trực tiếp tới app) ─────────

def tunnel_send(typ, cmd="", timeout=None):
    timeout = timeout or TIMEOUT
    body = signed(typ, cmd)
    body["timeout"] = int(max(1, timeout - 5) * 1000)  # app dừng lệnh trước khi HTTP hết giờ
    r = urllib.request.Request(f"{TUNNEL}/cmd", data=json.dumps(body).encode(), method="POST",
                               headers={"Content-Type": "application/json", "User-Agent": UA})
    try:
        with urllib.request.urlopen(r, timeout=timeout + 10) as resp:
            d = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        txt = e.read().decode(errors="replace")
        try:
            d = json.loads(txt)
        except ValueError:
            die(f"HTTP {e.code} từ đường hầm (app tắt hoặc địa chỉ đã đổi?): {txt[:200]}")
        if "exit" not in d:
            die(f"{d.get('error', txt[:200])} – kiểm tra BRIDGE_URL")
    except Exception as e:
        die(f"không gọi được {TUNNEL}: {e}")
    return d.get("exit"), d.get("output", ""), d.get("next")


# ───────── kênh Supabase (hộp thư) ─────────

def supa_req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(f"{SUPA_URL}/rest/v1/{path}", data=data, method=method)
    r.add_header("apikey", SUPA_KEY)
    if SUPA_KEY.startswith("eyJ"):  # key cũ dạng JWT; key mới sb_... chỉ gửi qua apikey
        r.add_header("Authorization", f"Bearer {SUPA_KEY}")
    r.add_header("Accept", "application/json")
    r.add_header("User-Agent", UA)
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


def supa_send(typ, cmd="", timeout=None):
    timeout = timeout or TIMEOUT
    row = signed(typ, cmd)
    row["dir"] = "cmd"
    supa_req("POST", "mailbox", [row])
    seq = row["seq"]
    start = time.time()
    while time.time() - start < timeout:
        rows = supa_req("GET", f"mailbox?dir=eq.res&seq=eq.{seq}&order=part.asc&select=part,parts,exit,output")
        if rows:
            parts = {r["part"]: r for r in rows}
            if len(parts) >= rows[0].get("parts", 1):
                return rows[0].get("exit"), "".join(parts[i]["output"] for i in sorted(parts)), None
        time.sleep(1.5)
    die("hết giờ chờ kết quả (cầu nối trong app đang bật chưa?)")


def send(typ, cmd="", timeout=None):
    return (tunnel_send if TUNNEL else supa_send)(typ, cmd, timeout)


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


def follow_tunnel(after):
    while True:
        code, out, nxt = tunnel_send("logs", str(after), timeout=20)
        if out:
            sys.stdout.write(out)
            sys.stdout.flush()
        after = nxt if nxt is not None else after
        if code != 0 and not out:
            print("(stream đã dừng)", file=sys.stderr)
            return
        time.sleep(1.5)


def follow_supabase(since_id):
    while True:
        for r in supa_req("GET", f"mailbox?dir=eq.log&id=gt.{since_id}&order=id.asc&select=id,output"):
            sys.stdout.write(r.get("output", ""))
            since_id = max(since_id, r["id"])
        sys.stdout.flush()
        time.sleep(2)


def cmd_logcat(args):
    since = 0
    if not TUNNEL:
        rows = supa_req("GET", "mailbox?dir=eq.log&order=id.desc&limit=1&select=id")
        since = rows[0]["id"] if rows else 0
    code, out, nxt = send("logcat_start", " ".join(args), timeout=30)
    print(out, file=sys.stderr)
    if code:
        sys.exit(1)
    print("Đang stream (Ctrl+C để ngừng xem). Tắt hẳn: bridge.py stop-logcat", file=sys.stderr)
    try:
        follow_tunnel(nxt or 0) if TUNNEL else follow_supabase(since)
    except KeyboardInterrupt:
        print("\n(ngừng theo dõi)", file=sys.stderr)


def cmd_stop(_):
    print(send("logcat_stop")[1])


def cmd_cleanup(args):
    if not SUPA_URL:
        die("cleanup chỉ dùng cho kênh Supabase")
    hours = float(args[0]) if args else 24.0
    cutoff = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() - hours * 3600))
    supa_req("DELETE", "mailbox?created_at=lt." + urllib.parse.quote(cutoff))
    print(f"Đã xoá hàng cũ hơn {hours:g} giờ.")


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print(__doc__)
        return
    if not PASSWORD:
        die("thiếu BRIDGE_PASSWORD")
    if not TUNNEL and not (SUPA_URL and SUPA_KEY):
        die("thiếu BRIDGE_URL (đường hầm) hoặc BRIDGE_SUPABASE_URL + BRIDGE_SUPABASE_KEY")
    {
        "exec": cmd_exec, "ping": cmd_ping, "logcat": cmd_logcat,
        "stop-logcat": cmd_stop, "cleanup": cmd_cleanup,
    }.get(sys.argv[1], lambda _: die(f"lệnh không rõ: {sys.argv[1]}"))(sys.argv[2:])


if __name__ == "__main__":
    main()
