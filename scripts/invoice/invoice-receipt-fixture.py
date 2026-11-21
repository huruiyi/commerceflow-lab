import hashlib
import hmac
import json
import os
import socket
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = os.environ["INVOICE_CALLBACK_SECRET"].encode()
events = {}
paused = False


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/state":
            self.reply(200, events)
        else:
            self.reply(404)

    def do_POST(self):
        global paused
        if self.path in ("/mode/pause", "/mode/resume"):
            paused = self.path == "/mode/pause"
            self.reply(204)
            return
        if self.path != "/receipts":
            self.reply(404)
            return
        timestamp = self.headers.get("X-Invoice-Timestamp", "")
        signature = self.headers.get("X-Invoice-Signature", "")
        body = self.rfile.read(int(self.headers["Content-Length"]))
        canonical = f"{timestamp}\nPOST\n/receipts\n".encode() + body
        expected = hmac.new(SECRET, canonical, hashlib.sha256).hexdigest()
        if not timestamp.isascii() or not timestamp.isdecimal() or abs(time.time() - int(timestamp)) > 300:
            self.reply(401)
            return
        if not hmac.compare_digest(expected, signature):
            self.reply(401)
            return

        event = json.loads(body)
        event_id = event["eventId"]
        observed = events.setdefault(event_id, {
            "requestId": event["result"]["requestId"],
            "body": body.decode(),
            "deliveries": 0,
            "applied": 1,
            "acknowledged": False,
        })
        if observed["body"] != body.decode():
            self.reply(409)
            return
        observed["deliveries"] += 1
        if observed["deliveries"] == 1:
            # Commit the consumer result and close the connection without an acknowledgement.
            self.connection.shutdown(socket.SHUT_RDWR)
            self.close_connection = True
            return
        if paused:
            self.reply(503)
            return
        observed["acknowledged"] = True
        self.reply(204)

    def reply(self, status, data=None):
        body = b"" if data is None else json.dumps(data).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


HTTPServer(("0.0.0.0", 8080), Fixture).serve_forever()
