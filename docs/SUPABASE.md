# Thiết lập Supabase cho người mới (≈10 phút)

> Kênh **dự phòng**. Cách mặc định là đường hầm (không cần tài khoản) – xem README. Dùng Supabase khi đường hầm
> không chạy hoặc muốn dữ liệu đi qua project của riêng bạn.

Supabase là dịch vụ cơ sở dữ liệu (Postgres) trên mây, có gói **miễn phí**. Ở đây nó chỉ đóng vai
**hộp thư** (bảng `mailbox`): Claude Code bỏ lệnh vào, app trên điện thoại lấy lệnh ra, chạy, rồi bỏ kết quả lại.
Bạn **không cần biết lập trình cơ sở dữ liệu** — chỉ cần làm 4 bước dưới, đều trên trình duyệt điện thoại được.

## Bước 1 – Tạo tài khoản và project

1. Vào <https://supabase.com> → **Start your project** → đăng nhập bằng GitHub (nhanh nhất) hoặc email.
2. Bấm **New project**:
   - **Organization**: để mặc định (gói Free).
   - **Project name**: ví dụ `adb-bridge`.
   - **Database password**: bấm *Generate a password* (mật khẩu này **không dùng** cho app, chỉ cần lưu lại phòng khi cần).
   - **Region**: chọn gần bạn, vd *Southeast Asia (Singapore)* cho nhanh.
3. Bấm **Create new project** và chờ 1–2 phút cho project khởi tạo xong.

> Gói Free: project bị **tạm dừng nếu 7 ngày không có hoạt động**; vào dashboard bấm *Restore* là chạy lại.
> Khi cầu nối đang bật, app gọi API liên tục nên thường không bị coi là "không hoạt động".

## Bước 2 – Tạo bảng `mailbox`

1. Menu trái → **SQL Editor** (biểu tượng `>_`) → **New query**.
2. Mở file [`tools/supabase_setup.sql`](../tools/supabase_setup.sql) trong repo này, **sao chép toàn bộ** và dán vào ô soạn.
3. Bấm **Run** (hoặc Ctrl+Enter). Thấy `Success. No rows returned` là xong.
4. Kiểm tra: menu trái → **Table Editor** → thấy bảng `mailbox` (đang trống).

## Bước 3 – Lấy Project URL và Secret key

1. **Project URL** dạng `https://abcdxyz.supabase.co`:
   bấm nút **Connect** ở thanh trên cùng, hoặc **Project Settings → Data API** (một số bản là *Integrations → Data API*).
2. **Secret key**: **Project Settings → API Keys** → mục **Secret keys** → bấm hiện/sao chép key dạng `sb_secret_…`.
   - Project cũ chưa có key mới thì dùng tab **Legacy API keys** → key **`service_role`** (dạng `eyJ…`). App hỗ trợ cả hai.
   - **Không dùng** *publishable key* / *anon key* nếu muốn an toàn (đó là key công khai).

> ⚠️ Secret key có toàn quyền với project. Chỉ dán vào app và máy chạy Claude Code, **không** đăng lên GitHub/chat công khai.
> Lộ key → vào **API Keys** tạo key mới rồi xoá key cũ.

## Bước 4 – Nhập vào app và Claude Code

**Trên điện thoại** – app → tab **Connect**:
1. Thẻ *Mật khẩu & chế độ*: **Tạo mật khẩu ngẫu nhiên** → **Lưu** (mật khẩu ký lệnh, ≥12 ký tự – khác mật khẩu database ở Bước 1).
2. Thẻ *Supabase (tuỳ chọn)*: bật **Nhận lệnh qua Supabase**, dán **Project URL** + **Secret key** → **Lưu & kiểm tra** → `✓ Supabase OK`.
3. Kết nối ADB (thẻ *Gỡ lỗi không dây*) → **Bắt đầu cầu nối**.
4. **Sao chép cho Claude Code**.

**Phía Claude Code** – dán các dòng vừa sao chép vào terminal (hoặc mục *Environment variables* của môi trường cloud), rồi:

```bash
python3 tools/bridge.py ping      # → pong · ADB=true · <tên máy>
python3 tools/bridge.py exec "getprop ro.product.model"
```

Lệnh sẽ hiện ngay trong tab **CLI** của app với dòng `claude$`.

## Lỗi thường gặp

| Thông báo | Nguyên nhân / cách sửa |
|---|---|
| `Chưa có bảng 'mailbox'` | Chưa chạy SQL ở Bước 2, hoặc chạy nhầm project. |
| `Key sai hoặc thiếu quyền (401/403)` | Dán thiếu ký tự, hoặc dùng nhầm publishable/anon key. |
| `Không kết nối được` | URL sai (phải bắt đầu `https://` và kết thúc `.supabase.co`), mất mạng, hoặc project đang bị tạm dừng. |
| `bridge.py`: hết giờ chờ kết quả | Cầu nối trên app chưa bật, hoặc điện thoại tắt màn hình lâu và hệ thống chặn nền → tắt tối ưu pin cho app. |
| exit `-2` (sai mật khẩu) | `BRIDGE_PASSWORD` khác mật khẩu trong app → sao chép lại biến môi trường. |
| exit `-4` (ADB chưa kết nối) | Cổng Gỡ lỗi không dây đã đổi → tab Connect → Bước 2 nhập cổng mới. |

## Dọn dẹp

Mỗi lệnh/kết quả là một hàng trong bảng. Thỉnh thoảng chạy `python3 tools/bridge.py cleanup 24` để xoá hàng cũ hơn 24 giờ
(gói Free có giới hạn 500 MB, cầu nối dùng rất ít).
