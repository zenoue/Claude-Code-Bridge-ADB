# Claude Code Bridge ADB

> App Android làm **cầu nối giữa ADB và Claude Code**: app tự mở **đường hầm** ra một địa chỉ `https://…`,
> Claude Code gửi lệnh `adb shell` thẳng vào đó; điện thoại chạy bằng ADB nội bộ (Gỡ lỗi không dây,
> không root, không PC) và trả kết quả về. Không cần tài khoản, không cần server riêng.

```
[Claude Code] ──HTTPS, lệnh ký HMAC──▶ https://xxxx.lhr.life ══SSH -R══▶ [Điện thoại: app] ──▶ ADB nội bộ → adbd
 tools/bridge.py                         (localhost.run)                  127.0.0.1:8765
```

Phần ADB không dây (ghép nối + kết nối TLS bằng [libadb-android](https://github.com/MuntashirAkon/libadb-android))
lấy từ [zenoue/Debug_unroot](https://github.com/zenoue/Debug_unroot).

## Hai tab

| Tab | Chức năng |
|---|---|
| **CLI** | Gõ lệnh `adb shell` và xem kết quả (kèm exit code). Lệnh Claude Code gửi đến cũng hiện ở đây với tiền tố `claude$`. Có lệnh nhanh, gọi lại lệnh trước, sao chép/xoá nhật ký. |
| **Connect** | Bật/tắt cầu nối, xem địa chỉ đường hầm · Ghép nối & kết nối **Gỡ lỗi không dây** · Mật khẩu, nhà cung cấp đường hầm, chế độ chỉ-đọc · **Sao chép cho Claude Code**. |

## Thiết lập (3 phút, chỉ trên điện thoại)

1. Vào [**Releases**](../../releases/latest) → tải `CCBridgeADB-v1.0.N.apk` → cài (cài đè được bản cũ).
2. Bật **Gỡ lỗi không dây**: Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+, cần đang nối Wi‑Fi).
3. App → tab **Connect**:
   1. **Gỡ lỗi không dây** – Bước 1 ghép nối (cổng + mã 6 số, dùng chia đôi màn hình), Bước 2 kết nối (cổng ở dòng
      "Địa chỉ IP và cổng", hoặc *Tự dò cổng*).
   2. **Mật khẩu & chế độ** – *Tạo mật khẩu ngẫu nhiên* → *Lưu* (nhà cung cấp mặc định `localhost.run`).
   3. **Bắt đầu cầu nối** → vài giây sau hiện địa chỉ `https://xxxx.lhr.life`.
   4. **Sao chép cho Claude Code** → dán sang Claude Code.

## Phía Claude Code

```bash
export BRIDGE_URL='https://xxxx.lhr.life'      # địa chỉ trong app
export BRIDGE_PASSWORD='...'                    # mật khẩu trong app

python3 tools/bridge.py ping                    # app còn sống? ADB đã kết nối?
python3 tools/bridge.py exec "dumpsys battery"  # in output, thoát với exit code của lệnh
python3 tools/bridge.py logcat "logcat -v time" # stream logcat (Ctrl+C)
python3 tools/bridge.py stop-logcat
```

Chỉ cần Python 3 (thư viện chuẩn). Xem thêm [`CLAUDE.md`](CLAUDE.md).

- **Claude Code trên máy tính**: dùng được ngay.
- **Claude Code trên web (phiên cloud)**: mạng của phiên bị giới hạn theo danh sách tên miền. Vào cài đặt
  environment (menu environment trên thanh tiêu đề phiên → Edit → Network access) và thêm `*.lhr.life`
  (hoặc `*.serveousercontent.com` nếu dùng serveo), hoặc chọn mức truy cập rộng hơn.
  [Hướng dẫn](https://code.claude.com/docs/en/cloud-environments#network-access).

## Giao thức đường hầm

`POST {BRIDGE_URL}/cmd` với JSON `{seq, ts, type, cmd, nonce, sig, timeout}` → `{seq, exit, output, next}`.
`type`: `exec` · `ping` · `logcat_start` · `logcat_stop` · `logs` (cmd = id dòng cuối đã đọc).
`sig = HMAC_SHA256(mật_khẩu, "seq\nts\ntype\nnonce\ncmd")`. App từ chối nếu chữ ký sai, `ts` lệch quá 5 phút,
hoặc `nonce` đã dùng. `GET /` trả `CC Bridge ADB` để kiểm tra đường hầm.

## Vì sao localhost.run

| Đường hầm | Chạy trong app | Tài khoản | Nhận xét |
|---|---|---|---|
| **localhost.run** (mặc định) | ✅ SSH (JSch nhúng sẵn) | Không | Không giới hạn thời gian phiên; URL ngẫu nhiên, có thể đổi khi nối lại |
| **serveo.net** (dự phòng) | ✅ SSH | Không | Hay chập chờn hơn |
| Pinggy | ✅ SSH | Không | Phiên free tối đa 60 phút, URL đổi mỗi phiên |
| Cloudflare Quick Tunnel | ❌ cần file `cloudflared` | Không | URL đổi mỗi lần chạy |
| ngrok / Tailscale Funnel | ❌ cần agent/app riêng | Có | URL cố định nhưng thiết lập phức tạp |

## Bảo mật

- Mật khẩu (≥12 ký tự) **không bao giờ đi qua mạng** – chỉ dùng để ký HMAC từng lệnh. Ai biết địa chỉ đường hầm mà
  không có mật khẩu thì chỉ thấy `CC Bridge ADB`.
- Nhà cung cấp đường hầm (localhost.run/serveo) giải mã TLS nên **có thể thấy nội dung lệnh và kết quả**
  (không thể giả lệnh vì không có mật khẩu). Đừng chạy lệnh in ra dữ liệu nhạy cảm.
- Server trong app chỉ nghe `127.0.0.1`, không lộ ra mạng LAN.
- Mặc định **chỉ-đọc** (cả lệnh tay lẫn lệnh từ xa); bật *Cho phép mọi lệnh* nếu cần.
- Lệnh chạy ở quyền *shell* (uid 2000, như `adb shell`) – không phải root.

## Giới hạn

- Địa chỉ đường hầm miễn phí có thể đổi khi nối lại (mất mạng, đổi Wi‑Fi/4G) → bấm *Sao chép cho Claude Code* lại.
- Gỡ lỗi không dây cần đang nối Wi‑Fi (mạng không có internet cũng được; internet đi qua 4G).
- Cổng kết nối ADB đổi sau mỗi lần tắt/bật Gỡ lỗi không dây hoặc khởi động lại → nhập lại Bước 2.
- Mỗi lệnh tối đa ~10 phút (`BRIDGE_TIMEOUT`); lệnh không tự kết thúc dùng `bridge.py logcat`.

## Build

```bash
./gradlew testDebugUnitTest   # test HMAC, bộ lọc lệnh, exit code, console, giao thức HTTP, URL đường hầm
./gradlew assembleDebug       # cần Android SDK (compileSdk 35), JDK 17
```

Mỗi lần push lên `main`, GitHub Actions build APK và tạo release `v1.0.N`; ghi chú release liệt kê
vắn tắt các commit kể từ bản trước.
