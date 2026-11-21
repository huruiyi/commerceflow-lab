import hashlib
import hmac
import json
import os
import socket
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ["INVOICE_REQUEST_SECRET"].encode()
issue_attempts = 0
failures_remaining = 0
state = {}


class Proxy(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/fixture/state":
            self.reply(200, json.dumps(state).encode())
            return
        self.forward("GET")

    def do_POST(self):
        global failures_remaining
        if self.path in ("/fixture/fail-next-issue", "/fixture/fail-three-issues"):
            failures_remaining = 3 if self.path.endswith("three-issues") else 1
            self.reply(204)
            return
        self.forward("POST")

    def forward(self, method):
        global issue_attempts, failures_remaining
        request_id = self.path.rsplit("/", 1)[-1]
        observed = state.setdefault(request_id, {"get": 0, "post": 0, "dropped": False})
        observed[method.lower()] += 1
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        headers = {name: self.headers[name] for name in ("X-Invoice-Timestamp", "X-Invoice-Signature")}
        is_issue = method == "POST" and self.path.startswith("/api/invoice-requests/")
        if is_issue:
            issue_attempts += 1
            if failures_remaining:
                failures_remaining -= 1
                value = json.loads(body)
                value["simulation"] = "FAIL"
                body = json.dumps(value).encode()
                canonical = f"{headers['X-Invoice-Timestamp']}\nPOST\n{self.path}\n".encode() + body
                headers["X-Invoice-Signature"] = hmac.new(SECRET, canonical, hashlib.sha256).hexdigest()
        if body:
            headers["Content-Type"] = "application/json"
        channel = HTTPConnection("invoice", 8080, timeout=5)
        try:
            channel.request(method, self.path, body=body or None, headers=headers)
            response = channel.getresponse()
            payload = response.read()
            if is_issue and issue_attempts == 1 and response.status == 202:
                observed["dropped"] = True
                self.connection.shutdown(socket.SHUT_RDWR)
                self.close_connection = True
                return
            self.reply(response.status, payload)
        finally:
            channel.close()

    def reply(self, status, body=b""):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


HTTPServer(("0.0.0.0", 8080), Proxy).serve_forever()
