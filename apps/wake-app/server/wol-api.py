#!/usr/bin/env python3
"""wol-api —— 极小的局域网唤醒接口（v1.2）
  GET /health            免鉴权，探活
  GET /wake?token=       发 WoL 魔术包
  GET /status?token=     目标机在线状态
"""
import http.server, json, os, subprocess, hmac, urllib.parse, socket, time, re

TOKEN  = os.environ.get("WOL_TOKEN", "")
MAC    = os.environ.get("WOL_MAC", "2c:f0:5d:0e:61:c8").lower()
TARGET = os.environ.get("WOL_TARGET_IP", "")
IFACES = [x for x in os.environ.get("WOL_IFACES", "vmbr0").split(",") if x]
PORTS  = [int(x) for x in os.environ.get("WOL_PROBE_PORTS", "3389,445,22,80").split(",") if x.strip().isdigit()]
PORT   = int(os.environ.get("WOL_PORT", "8787"))
BIND   = os.environ.get("WOL_BIND", "0.0.0.0")


def ok_token(t):
    return bool(TOKEN) and bool(t) and hmac.compare_digest(t, TOKEN)


def ip_of_mac():
    """从邻居表按 MAC 反查 IPv4 —— 目标机重新拿 DHCP 地址时也能跟上。"""
    try:
        out = subprocess.run(["ip", "neigh"], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return None
    for line in out.splitlines():
        if MAC in line.lower():
            m = re.match(r"\s*(\d+\.\d+\.\d+\.\d+)\s", line)
            if m:
                return m.group(1)
    return None


def tcp_open(ip, port, timeout=1.5):
    s = socket.socket(); s.settimeout(timeout)
    try:
        return s.connect_ex((ip, port)) == 0
    except Exception:
        return False
    finally:
        s.close()


def ping(ip):
    try:
        return subprocess.run(["ping", "-c", "1", "-W", "1", ip],
                              capture_output=True, timeout=6).returncode == 0
    except Exception:
        return False


def arping_mac(ip, iface):
    """主动 ARP 探测，返回回包者 MAC（大写）；无回复返回 None。"""
    try:
        r = subprocess.run(["arping", "-c", "2", "-w", "3", "-I", iface, ip],
                           capture_output=True, text=True, timeout=15)
        m = re.search(r"\[([0-9A-Fa-f:]{17})\]", r.stdout)
        return m.group(1).upper() if m else None
    except Exception:
        return None


def probe(ip):
    """分层判活：主动 ARP -> TCP 端口 -> ICMP。

    刻意不使用被动邻居表：目标机刚关机时它的 ARP 条目仍是 REACHABLE，
    会造成「已关机却显示在线」的误报（实测踩过这个坑）。
    主动 ARP 是二层探测，既不受 ICMP 被防火墙拦截的影响，又能校验回包 MAC。
    """
    if not ip:
        return ("none", False, None)

    for iface in IFACES:
        got = arping_mac(ip, iface)
        if got:
            if got.lower() == MAC:
                return ("arp", True, got)
            return ("ip-taken-by", False, got)   # 该 IP 被别的设备占了

    for p in PORTS:
        if tcp_open(ip, p):
            return ("tcp:" + str(p), True, None)
    if ping(ip):
        return ("icmp", True, None)
    return ("none", False, None)


class H(http.server.BaseHTTPRequestHandler):
    server_version = "wol-api/1.2"

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        tok = self.headers.get("X-Wake-Token") or (q.get("token") or [""])[0]

        if u.path == "/health":
            return self._send(200, {"ok": True, "service": "wol-api", "version": "1.2"})

        if u.path not in ("/wake", "/status"):
            return self._send(404, {"ok": False, "error": "not found"})

        if not ok_token(tok):
            time.sleep(1)
            return self._send(401, {"ok": False, "error": "bad token"})

        if u.path == "/wake":
            try:
                r = subprocess.run(["/usr/bin/wakeonlan", MAC],
                                   capture_output=True, text=True, timeout=15)
            except Exception as e:
                return self._send(500, {"ok": False, "error": str(e)})
            return self._send(200 if r.returncode == 0 else 500,
                              {"ok": r.returncode == 0, "mac": MAC,
                               "at": time.strftime("%F %T"),
                               "stdout": r.stdout.strip()[:200]})

        ip = ip_of_mac() or TARGET or None
        method, online, seen_mac = probe(ip)
        body = {"ok": True, "mac": MAC, "target_ip": ip,
                "online": online, "method": method,
                "checked_at": time.strftime("%F %T")}
        if seen_mac:
            body["seen_mac"] = seen_mac
        return self._send(200, body)


http.server.ThreadingHTTPServer((BIND, PORT), H).serve_forever()
