#!/usr/bin/env bash
# =============================================================================
# scripts/make-test-ca.sh —— 生成自签 CA + 服务端证书（供本地 HTTPS 测试源使用）
#
# 产物（默认目录 /tmp/tls）：ca.crt / ca.key / srv.crt / srv.key / srv_chain.crt
#
# ⚠️ 本机踩过的坑：
#  1) 必须 -sha256：LibreSSL/openssl 默认可能签出弱摘要，Python 3.13 的 ssl
#     会直接拒收（CA_MD_TOO_WEAK）。
#  2) 叶子证书必须带 authorityKeyIdentifier，否则 OpenSSL 3.x 客户端报
#     "Missing Authority Key Identifier"。
#  3) SAN 要同时给 IP:10.0.2.2（模拟器访问宿主的地址）和 DNS:hls.test。
#  4) 服务端要 set_ciphers("ALL:@SECLEVEL=0")，见 https-hls-server.py 的注释
#     （否则 API 19 的 OpenSSL 1.0.1 与 OpenSSL 3.x 默认 SECLEVEL=2 无交集）。
# =============================================================================
set -uo pipefail

DIR="${1:-/tmp/tls}"
mkdir -p "$DIR"
cd "$DIR"

cat > san.cnf <<'EOF'
[req]
distinguished_name=dn
[dn]
[ext]
subjectAltName=IP:10.0.2.2,DNS:hls.test
basicConstraints=CA:FALSE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid,issuer
keyUsage=digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
[ca_ext]
basicConstraints=critical,CA:TRUE
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid:always,issuer
keyUsage=critical,keyCertSign,cRLSign
EOF

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -keyout ca.key -out ca.crt -days 3650 \
  -subj "/CN=MyTVLegacyTestCA" -extensions ca_ext -config san.cnf 2>&1 | tail -1

openssl req -newkey rsa:2048 -nodes -keyout srv.key -out srv.csr \
  -subj "/CN=10.0.2.2" -config san.cnf 2>&1 | tail -1

openssl x509 -req -in srv.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out srv.crt -days 3650 -sha256 -extfile san.cnf -extensions ext 2>&1 | tail -1

cat srv.crt ca.crt > srv_chain.crt

echo "生成完毕（$DIR）："
ls -la ca.crt ca.key srv.crt srv.key srv_chain.crt | sed 's/^/  /'
openssl x509 -in srv.crt -noout -text | grep -A1 "Subject Alternative Name" | sed 's/^/  /'
