"""Mock localhost.run SSH server for testing Tunnel.kt locally (needs: pip install paramiko).

REPLY=0       drop channel-request replies (localhost.run behaviour that caused "channel request: timeout")
SILENT_EXEC=1 print nothing for exec (forces the shell fallback)
KEY=<key>     URL key used for the forwarded GET /<key>/health

  python3 tools/mock_lhr.py 2222 &
  MOCK_SSH_PORT=2222 KEY=testkeytestkeytestkey123 ./gradlew testDebugUnitTest --tests '*TunnelLiveTest' -i
"""
import os, socket, sys, threading, time, paramiko

PORT = int(sys.argv[1]); REPLY = os.environ.get("REPLY", "1") == "1"
KEY = os.environ.get("KEY", "")
host_key = paramiko.RSAKey.generate(2048)
fwd = {}

def log(*a): print("[mock]", *a, flush=True)

class Srv(paramiko.ServerInterface):
    def __init__(self, t): self.t = t
    def get_allowed_auths(self, u): return "none,publickey"
    def check_auth_none(self, u): log("auth none", u); return paramiko.AUTH_SUCCESSFUL
    def check_auth_publickey(self, u, k): log("auth publickey", u); return paramiko.AUTH_SUCCESSFUL
    def check_channel_request(self, kind, cid): return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_port_forward_request(self, addr, port):
        log("tcpip-forward", addr, port); fwd["a"] = (addr, port); return port
    def check_channel_exec_request(self, ch, cmd):
        log("exec", cmd)
        if os.environ.get("SILENT_EXEC") != "1": threading.Thread(target=self.emit, args=(ch, cmd == b"--output json"), daemon=True).start()
        return True
    def check_channel_shell_request(self, ch):
        log("shell"); threading.Thread(target=self.emit, args=(ch, False), daemon=True).start(); return True
    def emit(self, ch, js):
        time.sleep(0.5)
        if js:
            ch.send(b'{"event":"authn","message":"authenticated as anonymous user"}\n')
            ch.send(b'{"event":"tcpip-forward","address":"abc123.lhr.life","listen_host":"localhost","listen_port":80}\n')
        else:
            ch.send(b"Welcome to localhost.run!\r\nhttps://admin.localhost.run/\r\nabc123.lhr.life tunneled with tls termination, https://abc123.lhr.life\r\n")
        time.sleep(0.5)
        try:
            fc = self.t.open_forwarded_tcpip_channel(("1.2.3.4", 5555), fwd["a"])
            fc.sendall(f"GET /{KEY}/health HTTP/1.1\r\nHost: abc123.lhr.life\r\n\r\n".encode())
            data = b""
            while True:
                d = fc.recv(4096)
                if not d: break
                data += d
            log("forwarded response:", data.decode(errors="replace").replace("\r\n", " | "))
        except Exception as e:
            log("forward error", e)

ls = socket.socket(); ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); ls.bind(("127.0.0.1", PORT)); ls.listen(5)
log("listening", PORT, "REPLY", REPLY)
while True:
    c, _ = ls.accept()
    t = paramiko.Transport(c); t.add_server_key(host_key)
    if not REPLY:
        orig = t._send_user_message
        def drop(m, orig=orig):
            b = m.asbytes()
            if b and b[0] in (99, 100):  # MSG_CHANNEL_SUCCESS / FAILURE
                log("DROPPED channel request reply"); return
            orig(m)
        t._send_user_message = drop
    t.start_server(server=Srv(t))
