#!/usr/bin/env python3
"""本地 **HTTPS** HLS 服务（仅用于验证 API 19 播放链路能否协商到 TLS 1.2）

关键点：
  1. `minimum_version = TLSv1_2` —— 服务端**只收 TLS 1.2**。
     这样"客户端没打 TLS 1.2 补丁"就一定握手失败，测试才有分辨力
     （若服务端允许 TLS 1.0，补丁打没打都会通过，那是假阳性）。
  2. 证书是自签 CA（/tmp/tls/ca.crt）签的，SAN 含 IP:10.0.2.2 与 DNS:hls.test；
     该 CA 需手工装进模拟器的系统信任库（见 scripts/install-test-ca.sh）。
  3. 只绑 127.0.0.1 —— 模拟器访问宿主走 10.0.2.2，映射到宿主回环。

用法： python3 scripts/https-hls-server.py [端口，默认 8443]
"""
import http.server
import os
import ssl
import sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8443
DOC_ROOT = "/tmp/hls"
CERT = "/tmp/tls/srv_chain.crt"
KEY = "/tmp/tls/srv.key"


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=DOC_ROOT, **kw)

    def log_message(self, fmt, *args):
        # 记录访问与 TLS 版本：排错时靠它区分「连不上 / 握手失败 / 已取到数据」
        try:
            ver = getattr(self.connection, "version", lambda: "?")()
        except Exception:
            ver = "?"
        with open("/tmp/https_srv.log", "a") as f:
            f.write(f"{self.log_date_time_string()} ver={ver} req={fmt % args}\n")


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    httpd = Server(("127.0.0.1", PORT), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    # 默认**只收 TLS 1.2**（这样"客户端没打补丁"必然失败，测试才有分辨力）；
    # 排错时可用 MIN_TLS=TLSv1 放开，用于确认客户端实际能协商到哪一版。
    ctx.minimum_version = getattr(
        ssl.TLSVersion, os.environ.get("MIN_TLS", "TLSv1_2")
    )
    print(f"minimum_version = {ctx.minimum_version}")
    # ★ 必须放开老 cipher：API 19 的 OpenSSL 1.0.1 只启用了一小撮套件，
    #   而 OpenSSL 3.x 默认 SECLEVEL=2 会禁掉 SHA1/CBC 等 —— 两边没有交集时
    #   服务端直接发 handshake_failure（alert 40），看起来像"TLS 版本不对"，其实是密码套件。
    ciphers = os.environ.get("CIPHERS", "ALL:@SECLEVEL=0")
    ctx.set_ciphers(ciphers)
    print(f"ciphers = {ciphers}")
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"HTTPS HLS 服务已启动 https://127.0.0.1:{PORT}/  (仅 TLS 1.2)  doc={DOC_ROOT}")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
