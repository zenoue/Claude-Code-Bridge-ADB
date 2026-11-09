# Claude Code Bridge ADB

> App Android làm **cầu nối giữa ADB và Claude Code qua Supabase** — Claude Code gửi lệnh `adb shell`,
> điện thoại chạy bằng ADB nội bộ (Gỡ lỗi không dây, không root, không PC) và trả kết quả về.

```
[Claude Code]  ──ký HMAC──▶  Supabase (bảng mailbox)  ◀──hỏi vòng──  [Điện thoại: app]
 tools/bridge.py   cmd ▶          cmd / res / log           ◀ res/log      ADB trong app → adbd
```

Phần ADB không dây (ghép nối + kết nối TLS bằng [libadb-android](https://github.com/MuntashirAkon/libadb-android))
lấy từ [zenoue/Debug_unroot](https://github.com/zenoue/Debug_unroot).

## Hai tab

| Tab | Chức năng |
|---|---|
| **CLI** | Gõ lệnh `adb shell` và xem kết quả (kèm exit code). Lệnh Claude Code gửi đến cũng hiện ở đây với tiền tố `claude$`. Có lệnh nhanh, gọi lại lệnh trước, sao chép/xoá nhật ký. |
| **Connect** | Bật/tắt cầu nối · Ghép nối & kết nối **Gỡ lỗi không dây** · Cấu hình **Supabase** · Sao chép biến môi trường cho Claude Code. |

## 1. Chuẩn bị Supabase (một lần)

1. Tạo project tại [supabase.com](https://supabase.com) (miễn phí).
2. **SQL Editor** → chạy [`tools/supabase_setup.sql`](tools/supabase_setup.sql) (tạo bảng `mailbox`).
3. **Project Settings → API**: lấy **Project URL** và **API key** (service_role, hoặc key có quyền bảng `mailbox`).

## 2. Cài app

1. Vào [**Releases**](../../releases/latest) → tải `CCBridgeADB-v1.0.N.apk` → cài (các bản ký cùng keystore nên cài đè được).
2. Bật **Gỡ lỗi không dây**: Cài đặt → Tuỳ chọn nhà phát triển → Gỡ lỗi không dây (Android 11+).
3. Mở app → tab **Connect**:
   - **Bước 1 – Ghép nối**: "Ghép nối thiết bị bằng mã ghép nối" → nhập cổng + mã 6 số (dùng chia đôi màn hình).
   - **Bước 2 – Kết nối**: nhập cổng ở dòng "Địa chỉ IP và cổng", hoặc bấm *Tự dò cổng (mDNS)*.
   - **Supabase**: dán URL + key, tạo mật khẩu → *Lưu & kiểm tra*.
   - **Bắt đầu cầu nối**.
4. Bấm *Sao chép biến môi trường* để mang sang máy chạy Claude Code.

## 3. Phía Claude Code

```bash
export BRIDGE_SUPABASE_URL='https://xxxx.supabase.co'
export BRIDGE_SUPABASE_KEY='eyJ...'
export BRIDGE_PASSWORD='...'                    # mật khẩu trong app

python3 tools/bridge.py ping                    # app còn sống? ADB đã kết nối?
python3 tools/bridge.py exec "dumpsys battery"  # in output, thoát với exit code của lệnh
python3 tools/bridge.py logcat "logcat -v time" # stream logcat (Ctrl+C)
python3 tools/bridge.py stop-logcat
python3 tools/bridge.py cleanup 24              # xoá hàng cũ hơn 24 giờ
```

Chỉ cần Python 3 (thư viện chuẩn). Xem thêm [`CLAUDE.md`](CLAUDE.md).

## Giao thức (bảng `mailbox`)

| dir | ai ghi | cột chính |
|---|---|---|
| `cmd` | Claude Code | seq, ts, type (`exec`/`ping`/`logcat_start`/`logcat_stop`), cmd, nonce, sig |
| `res` | App | seq, part, parts, exit, output (output dài chia phần 60 KB) |
| `log` | App | output (dòng logcat) |

`sig = HMAC_SHA256(mật_khẩu, "seq\nts\ntype\nnonce\ncmd")`. App chỉ chạy khi chữ ký khớp, `seq` mới hơn lệnh trước,
và lệnh không cũ quá 10 phút.

## Bảo mật

- URL/key/mật khẩu **không nằm trong APK** – nhập lúc chạy, lưu nội bộ máy.
- Lệnh từ xa phải ký đúng mật khẩu; sai → từ chối (exit `-2`).
- Mặc định **chỉ-đọc** (cả lệnh tay lẫn lệnh từ xa); bật *Cho phép mọi lệnh* nếu cần.
- Lệnh chạy ở quyền *shell* (uid 2000, như `adb shell`) – không phải root.
- Bảng bật RLS; giữ project riêng tư và giữ key cẩn thận.

## Giới hạn

- Hỏi vòng ~1,5 giây (gần realtime).
- Cổng kết nối ADB đổi sau mỗi lần tắt/bật Gỡ lỗi không dây hoặc khởi động lại → nhập lại Bước 2 (không cần ghép nối lại).
- Gỡ lỗi không dây cần Wi‑Fi đang bật.

## Build

```bash
./gradlew testDebugUnitTest   # test HMAC, bộ lọc lệnh, exit code, console
./gradlew assembleDebug       # cần Android SDK (compileSdk 35), JDK 17
```

Mỗi lần push lên `main`, GitHub Actions build APK và tạo release `v1.0.N`; ghi chú release liệt kê
vắn tắt các commit kể từ bản trước.
