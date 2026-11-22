import hmac
import json
import os
import re
import sqlite3
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from hashlib import sha256
from threading import Thread


ACK_PATH = re.compile(r"/api/on-call/incidents/([0-9a-f]{64})/ack")


def timestamp(seconds):
    return None if seconds is None else datetime.fromtimestamp(seconds, timezone.utc).isoformat()


def parse_occurrences(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get("alerts"), list):
        raise ValueError("missing Alertmanager alerts")
    occurrences = []
    for alert in payload["alerts"]:
        if not isinstance(alert, dict) or not isinstance(alert.get("labels"), dict):
            raise ValueError("invalid Alertmanager alert")
        name = alert["labels"].get("alertname")
        if not isinstance(name, str):
            raise ValueError("missing alertname")
        if not name.startswith("CommerceFlowNotification"):
            continue
        status, fingerprint, started = (alert.get(key) for key in ("status", "fingerprint", "startsAt"))
        if status not in ("firing", "resolved") or not isinstance(fingerprint, str) or not fingerprint:
            raise ValueError("invalid alert status or fingerprint")
        if not isinstance(started, str):
            raise ValueError("missing alert start")
        start = datetime.fromisoformat(started)
        if start.tzinfo is None:
            raise ValueError("alert start requires a timezone")
        key = fingerprint + "\0" + start.astimezone(timezone.utc).isoformat()
        occurrences.append((sha256(key.encode()).hexdigest(), status, json.dumps(alert)))
    return occurrences


class OnCallStore:
    def __init__(self, path, escalation_seconds):
        self.path = path
        self.escalation_seconds = escalation_seconds
        with self.transaction() as db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS incidents (
                    id TEXT PRIMARY KEY, alert TEXT NOT NULL, opened_at REAL NOT NULL,
                    acknowledged_at REAL, acknowledged_by TEXT, resolved_at REAL
                )
            """)
            db.execute("""
                CREATE TABLE IF NOT EXISTS deliveries (
                    incident_id TEXT NOT NULL REFERENCES incidents(id), recipient TEXT NOT NULL,
                    kind TEXT NOT NULL, delivered_at REAL NOT NULL,
                    PRIMARY KEY (incident_id, recipient, kind)
                )
            """)

    @contextmanager
    def transaction(self):
        db = sqlite3.connect(self.path)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        try:
            with db:
                db.execute("BEGIN IMMEDIATE")
                yield db
        finally:
            db.close()

    def accept(self, occurrences):
        with self.transaction() as db:
            now = time.time()
            self.escalate_due(db, now)
            for incident_id, status, alert in occurrences:
                if status == "firing":
                    created = db.execute(
                        "INSERT OR IGNORE INTO incidents(id, alert, opened_at) VALUES (?, ?, ?)",
                        (incident_id, alert, now),
                    ).rowcount
                    if created:
                        db.execute("INSERT INTO deliveries VALUES (?, 'primary', 'firing', ?)", (incident_id, now))
                else:
                    db.execute("""
                        INSERT INTO incidents(id, alert, opened_at, resolved_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET resolved_at = excluded.resolved_at
                        WHERE incidents.resolved_at IS NULL
                    """, (incident_id, alert, now, now))
                    db.execute("""
                        INSERT OR IGNORE INTO deliveries
                        SELECT incident_id, recipient, 'resolved', ?
                        FROM deliveries WHERE incident_id = ? AND kind IN ('firing', 'escalated')
                    """, (now, incident_id))

    def escalate_due(self, db, now):
        db.execute("""
            INSERT OR IGNORE INTO deliveries
            SELECT id, 'escalation', 'escalated', ? FROM incidents
            WHERE acknowledged_at IS NULL AND resolved_at IS NULL AND opened_at <= ?
        """, (now, now - self.escalation_seconds))

    def run_escalation(self):
        while True:
            try:
                with self.transaction() as db:
                    self.escalate_due(db, time.time())
            except sqlite3.Error as error:
                print(f"alert-webhook escalation: {error}", flush=True)
            time.sleep(1)

    def acknowledge(self, incident_id, recipient):
        with self.transaction() as db:
            now = time.time()
            self.escalate_due(db, now)
            row = db.execute("""
                SELECT i.acknowledged_at, i.acknowledged_by, i.resolved_at
                FROM incidents i JOIN deliveries d ON i.id = d.incident_id
                WHERE i.id = ? AND d.recipient = ? AND d.kind IN ('firing', 'escalated')
            """, (incident_id, recipient)).fetchone()
            if row is None:
                return 404, {"error": "incident not delivered to this recipient"}
            if row["acknowledged_at"] is not None:
                return 200, {
                    "incidentId": incident_id, "acknowledgedAt": timestamp(row["acknowledged_at"]),
                    "acknowledgedBy": row["acknowledged_by"],
                }
            if row["resolved_at"] is not None:
                return 409, {"error": "incident already resolved"}
            db.execute(
                "UPDATE incidents SET acknowledged_at = ?, acknowledged_by = ? WHERE id = ?",
                (now, recipient, incident_id),
            )
            return 200, {"incidentId": incident_id, "acknowledgedAt": timestamp(now), "acknowledgedBy": recipient}

    def inbox(self, recipient):
        with self.transaction() as db:
            rows = db.execute("""
                SELECT i.id, i.alert, i.acknowledged_at, i.acknowledged_by, i.resolved_at,
                       d.kind, d.delivered_at
                FROM deliveries d JOIN incidents i ON d.incident_id = i.id
                WHERE d.recipient = ? ORDER BY d.delivered_at, i.id, d.kind
            """, (recipient,)).fetchall()
        return [{
            "incidentId": row["id"], "kind": row["kind"],
            "deliveredAt": timestamp(row["delivered_at"]), "alert": json.loads(row["alert"]),
            "acknowledgedAt": timestamp(row["acknowledged_at"]), "acknowledgedBy": row["acknowledged_by"],
            "resolvedAt": timestamp(row["resolved_at"]),
        } for row in rows]


def start_workflow(base_handler):
    tokens = {
        "ingest": os.getenv("ALERT_INGEST_TOKEN"),
        "primary": os.getenv("ALERT_PRIMARY_TOKEN"),
        "escalation": os.getenv("ALERT_ESCALATION_TOKEN"),
    }
    seconds = int(os.getenv("ALERT_ESCALATION_SECONDS", "300"))
    if seconds <= 0 or not all(tokens.values()) or len(set(tokens.values())) != len(tokens):
        raise ValueError("on-call workflow requires distinct tokens and a positive escalation delay")
    store = OnCallStore(os.environ["ALERT_WORKFLOW_DB"], seconds)
    Thread(target=store.run_escalation, daemon=True).start()

    class OnCallHandler(base_handler):
        def authenticated_role(self):
            header = self.headers.get("Authorization", "").encode()
            for role, token in tokens.items():
                if hmac.compare_digest(header, ("Bearer " + token).encode()):
                    return role
            return None

        def do_GET(self):
            if self.path == "/healthz":
                return super().do_GET()
            role = self.authenticated_role()
            if role not in ("primary", "escalation"):
                self.respond(401, {"error": "on-call authentication required"})
            elif self.path == "/api/on-call/inbox":
                self.respond(200, {"recipient": role, "deliveries": store.inbox(role)})
            else:
                self.respond(404, {"error": "not found"})

        def do_POST(self):
            role = self.authenticated_role()
            if self.path == "/api/alerts":
                if role != "ingest":
                    self.respond(401, {"error": "Alertmanager authentication required"})
                    return
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if size < 1 or size > 1024 * 1024:
                        raise ValueError("invalid payload size")
                    occurrences = parse_occurrences(json.loads(self.rfile.read(size)))
                except ValueError:
                    self.respond(400, {"error": "invalid Alertmanager payload"})
                    return
                store.accept(occurrences)
                self.respond(202, {"accepted": True})
            elif match := ACK_PATH.fullmatch(self.path):
                if role not in ("primary", "escalation"):
                    self.respond(401, {"error": "on-call authentication required"})
                    return
                self.respond(*store.acknowledge(match[1], role))
            else:
                self.respond(404, {"error": "not found"})

    return OnCallHandler
