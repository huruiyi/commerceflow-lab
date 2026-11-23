"""Isolated relay. Pass the original signed request bytes to fixed Compose services."""

import http.client
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


PAYMENT = "/api/payment-notifications/callback"
MESSAGE = "/api/provider/v1/deliveries"
ISSUE = "/api/invoice-requests/"
INVOICE_RECEIPT = "/internal/v1/invoice-receipts"
LOCK = threading.RLock()


def initial_state():
    return {
        "payment": {"mode": "off", "notificationId": None, "results": []},
        "messaging": {"mode": "off", "requests": {}},
        "invoice": {"mode": "off", "requests": {}, "receipts": {}},
    }


STATE = initial_state()
PAYMENT_ENVELOPE = None


def relay(host, method, path, headers, body):
    connection = http.client.HTTPConnection(host, 8080, timeout=5)
    excluded = {"host", "connection", "content-length", "transfer-encoding", "accept-encoding"}
    forward_headers = {k: v for k, v in headers.items() if k.lower() not in excluded}
    try:
        connection.request(method, path, body if body else None, forward_headers)
        response = connection.getresponse()
        return response.status, response.getheader("Content-Type", "application/json"), response.read()
    finally:
        connection.close()


class Relay(BaseHTTPRequestHandler):
    def do_GET(self):
        self.handle_request()

    def do_POST(self):
        self.handle_request()

    def handle_request(self):
        path = urlsplit(self.path).path
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 0 or size > 1024 * 1024:
                self.send_result(413)
                return
            body = self.rfile.read(size)
            if self.command == "GET" and path == "/healthz":
                self.send_result(200)
            elif self.command == "GET" and path == "/control/state":
                with LOCK:
                    snapshot = json.dumps(STATE).encode()
                self.send_result(200, payload=snapshot)
            elif self.command == "POST" and path.startswith("/control/"):
                self.control(path)
            elif self.command == "POST" and path == PAYMENT:
                self.payment(body)
            elif path == MESSAGE or path.startswith(MESSAGE + "/"):
                self.messaging(path, body)
            elif path.startswith(ISSUE) or path.startswith("/api/red-invoice-requests/"):
                self.invoice(path, body)
            elif self.command == "POST" and path == INVOICE_RECEIPT:
                self.invoice_receipt(body)
            else:
                self.send_result(404)
        except (ValueError, TypeError, KeyError):
            self.send_result(400)
        except (OSError, http.client.HTTPException):
            self.send_result(503)

    def control(self, path):
        global PAYMENT_ENVELOPE
        _, _, name, action = path.split("/")
        with LOCK:
            if name not in STATE or action not in ("arm", "release", "reset"):
                self.send_result(404)
                return
            if action in ("arm", "reset"):
                STATE[name] = initial_state()[name]
                if name == "payment":
                    PAYMENT_ENVELOPE = None
                if action == "arm":
                    STATE[name]["mode"] = "hold"
            else:
                if name == "payment" and PAYMENT_ENVELOPE is None:
                    self.send_result(409)
                    return
                STATE[name]["mode"] = "released"
            envelope = PAYMENT_ENVELOPE if name == "payment" and action == "release" else None
        if envelope is not None:
            headers, body = envelope
            results = [
                relay("payment-orchestration-service", "POST", PAYMENT, headers, body)
                for _ in range(2)
            ]
            with LOCK:
                STATE["payment"]["results"] = [
                    {"status": status, "result": json.loads(payload).get("result")}
                    for status, _, payload in results
                ]
        self.send_result(204)

    def payment(self, body):
        global PAYMENT_ENVELOPE
        with LOCK:
            if STATE["payment"]["mode"] == "hold":
                STATE["payment"]["notificationId"] = json.loads(body)["notificationId"]
                PAYMENT_ENVELOPE = (dict(self.headers), body)
                self.send_result(204)
                return
        self.send_result(*relay("payment-orchestration-service", self.command, self.path, self.headers, body))

    def messaging(self, path, body):
        with LOCK:
            state = STATE["messaging"]
            active = state["mode"] != "off"
            command = json.loads(body) if self.command == "POST" else None
            request_id = command["requestId"] if command else path.rsplit("/", 1)[-1]
            record = state["requests"].get(request_id)
            if self.command == "GET" and record:
                record["queries"] += 1
                if state["mode"] == "hold":
                    self.send_result(503)
                    return
        upstream = relay("messaging-simulator-service", self.command, self.path, self.headers, body)
        if active and command and upstream[0] in (200, 202):
            with LOCK:
                record = state["requests"].setdefault(
                    request_id, {"channel": command["channel"], "reference": command["reference"],
                                 "submissions": 0, "queries": 0, "replyLost": False},
                )
                record["submissions"] += 1
                if state["mode"] == "hold" and not record["replyLost"]:
                    record["replyLost"] = True
                    self.send_result(504)
                    return
        self.send_result(*upstream)

    def invoice(self, path, body):
        request_id = path.rsplit("/", 1)[-1]
        with LOCK:
            state = STATE["invoice"]
            if self.command == "GET" and request_id in state["requests"]:
                confirmed = any(r["requestId"] == request_id and r["forwarded"]
                                for r in state["receipts"].values())
                if not confirmed:
                    self.send_result(503)
                    return
        upstream = relay("invoice-simulator-service", self.command, self.path, self.headers, body)
        if self.command == "POST" and upstream[0] in (200, 202):
            with LOCK:
                if state["mode"] == "hold" and request_id not in state["requests"]:
                    state["requests"][request_id] = {"acceptedStatus": upstream[0], "replyLost": True}
                    self.send_result(504)
                    return
        self.send_result(*upstream)

    def invoice_receipt(self, body):
        event = json.loads(body)
        with LOCK:
            state = STATE["invoice"]
            record = None
            if state["mode"] != "off":
                record = state["receipts"].setdefault(
                    event["eventId"], {"requestId": event["result"]["requestId"], "arrivals": 0, "forwarded": 0},
                )
                record["arrivals"] += 1
                if state["mode"] == "hold":
                    self.send_result(503)
                    return
        upstream = relay("commerce-service", self.command, self.path, self.headers, body)
        if record is not None and 200 <= upstream[0] < 300:
            with LOCK:
                record["forwarded"] += 1
                if record["forwarded"] == 1:
                    self.send_result(503)
                    return
        self.send_result(*upstream)

    def send_result(self, status, content_type="application/json", payload=b""):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if payload:
            self.wfile.write(payload)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), Relay).serve_forever()
