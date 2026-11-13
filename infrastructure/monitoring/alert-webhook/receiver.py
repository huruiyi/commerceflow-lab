import json
import os
from collections import deque
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


MAX_EVENTS = int(os.getenv("ALERT_WEBHOOK_MAX_EVENTS", "100"))
EVENTS = deque(maxlen=MAX_EVENTS)


class AlertWebhookHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/healthz":
            self.respond(200, {"status": "UP"})
            return
        if self.path == "/api/alerts":
            self.respond(200, {"count": len(EVENTS), "events": list(EVENTS)})
            return
        self.respond(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/api/alerts":
            self.respond(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError):
            self.respond(400, {"error": "invalid JSON"})
            return
        EVENTS.append({
            "receivedAt": datetime.now(timezone.utc).isoformat(),
            "payload": payload,
        })
        self.respond(202, {"accepted": True})

    def log_message(self, message, *args):
        print("alert-webhook - " + message % args, flush=True)

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), AlertWebhookHandler).serve_forever()
