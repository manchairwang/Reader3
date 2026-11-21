"""本地 CORS 代理（标准库实现，无依赖）
用法: 双击 start-proxy.bat 或  python cors-proxy.py
h5 页面会自动探测 http://127.0.0.1:8727 并优先使用。
"""
import urllib.request
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8727
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def _target(self):
        q = urllib.parse.urlsplit(self.path)
        # 两种写法: /?url=<encoded>  或  /https://site.com/path
        if q.query:
            params = urllib.parse.parse_qs(q.query)
            if 'url' in params:
                return params['url'][0]
        path = q.path.lstrip('/')
        if path.startswith('http'):
            return path
        return None

    def _proxy(self, method):
        target = self._target()
        if not target:
            self.send_response(400)
            self._cors()
            self.end_headers()
            self.wfile.write(b'missing url')
            return
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(target, data=body, method=method)
        req.add_header('User-Agent', UA)
        for h in ('Content-Type', 'Authorization', 'Referer', 'Accept'):
            v = self.headers.get(h)
            if v:
                req.add_header(h, v)
        try:
            resp = urllib.request.urlopen(req, timeout=30)
            data = resp.read()
            self.send_response(resp.status)
            self._cors()
            ct = resp.headers.get('Content-Type')
            if ct:
                self.send_header('Content-Type', ct)
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except Exception as e:
            msg = str(e).encode('utf-8', 'replace')
            self.send_response(502)
            self._cors()
            self.send_header('Content-Length', str(len(msg)))
            self.end_headers()
            self.wfile.write(msg)

    def do_GET(self):
        self._proxy('GET')

    def do_POST(self):
        self._proxy('POST')

    def log_message(self, fmt, *args):
        pass  # 静默


if __name__ == '__main__':
    print(f'CORS proxy on http://127.0.0.1:{PORT}  (保持本窗口开着)')
    try:
        ThreadingHTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        pass
