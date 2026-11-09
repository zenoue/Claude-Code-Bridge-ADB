-- Thiết lập bảng hộp thư cho cầu nối ADB ↔ Claude.
-- Mở Supabase → SQL Editor → dán và chạy.

create table if not exists mailbox (
  id         bigint generated always as identity primary key,
  dir        text not null,            -- 'cmd' | 'res' | 'log'
  seq        bigint,                   -- số thứ tự lệnh
  type       text,                     -- exec | logcat_start | logcat_stop | ping
  cmd        text,
  nonce      text,
  sig        text,                     -- HMAC-SHA256 của lệnh
  exit       int,
  part       int,
  parts      int,
  output     text,
  ts         bigint,
  created_at timestamptz default now()
);

create index if not exists mailbox_cmd_idx on mailbox (dir, seq);
create index if not exists mailbox_id_idx  on mailbox (dir, id);

-- Bật RLS. Chỉ ai có API key mới truy cập; lệnh vẫn phải ký đúng mật khẩu thì app mới chạy.
alter table mailbox enable row level security;

-- Cho phép đọc/ghi qua key (anon hoặc service). Thắt chặt thêm nếu cần.
drop policy if exists mailbox_all on mailbox;
create policy mailbox_all on mailbox for all using (true) with check (true);

-- (Tuỳ chọn) tự xoá hàng cũ hơn 1 ngày để bảng gọn:
-- delete from mailbox where created_at < now() - interval '1 day';
