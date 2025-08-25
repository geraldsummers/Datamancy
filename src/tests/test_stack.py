import os
import socket
import unittest
from pathlib import Path

import requests
import yaml


def is_port_open(host: str, port: int, timeout: float = 1.0) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        try:
            sock.connect((host, port))
            return True
        except OSError:
            return False


def http_get(url: str, timeout: float = 2.0):
    try:
        return requests.get(url, timeout=timeout)
    except requests.RequestException:
        return None


class TestStackHealth(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Host/ports can be overridden via env for CI or remote Docker hosts
        cls.host = os.getenv("STACK_HOST", "localhost")

        cls.clickhouse_port = int(os.getenv("CLICKHOUSE_HTTP_PORT", "8123"))
        cls.grafana_port = int(os.getenv("GRAFANA_PORT", "3000"))
        cls.prometheus_port = int(os.getenv("PROMETHEUS_PORT", "9090"))
        cls.loki_port = int(os.getenv("LOKI_PORT", "3100"))
        cls.ragserve_port = int(os.getenv("RAGSERVE_PORT", "8000"))

        # Paths
        repo_root = Path(__file__).resolve().parents[2]
        cls.benthos_yaml = repo_root / "src" / "benthos" / "rss_to_clickhouse.yml"
        cls.clickhouse_sql = repo_root / "src" / "clickhouse" / "create_rss_table.sql"

    # ---------- ClickHouse ----------
    def test_clickhouse_port_or_http_ping(self):
        # Prefer HTTP /ping, fall back to TCP check to avoid false negatives during startup
        http_url = f"http://{self.host}:{self.clickhouse_port}/ping"
        resp = http_get(http_url, timeout=1.5)
        if resp is None:
            self.assertTrue(
                is_port_open(self.host, self.clickhouse_port),
                f"ClickHouse not reachable at {self.host}:{self.clickhouse_port}",
            )
        else:
            self.assertEqual(resp.status_code, 200, f"ClickHouse /ping HTTP {resp.status_code}")
            # Some ClickHouse builds return 'Ok.' or 'OK'
            self.assertIn(resp.text.strip().lower(), {"ok.", "ok"})

    def test_clickhouse_schema_file_present(self):
        self.assertTrue(self.clickhouse_sql.exists(), f"Missing SQL: {self.clickhouse_sql}")
        sql = self.clickhouse_sql.read_text(encoding="utf-8")
        self.assertIn("create table", sql.lower(), "Expected a CREATE TABLE statement in SQL DDL")

    # ---------- Grafana ----------
    def test_grafana_health(self):
        if not is_port_open(self.host, self.grafana_port):
            self.skipTest("Grafana port not open; skipping")
        resp = http_get(f"http://{self.host}:{self.grafana_port}/api/health")
        self.assertIsNotNone(resp, "Grafana /api/health not reachable")
        self.assertEqual(resp.status_code, 200, f"Grafana health HTTP {resp.status_code}")

    # ---------- Prometheus ----------
    def test_prometheus_health(self):
        if not is_port_open(self.host, self.prometheus_port):
            self.skipTest("Prometheus port not open; skipping")
        resp = http_get(f"http://{self.host}:{self.prometheus_port}/-/healthy")
        self.assertIsNotNone(resp, "Prometheus /-/healthy not reachable")
        self.assertEqual(resp.status_code, 200, f"Prometheus health HTTP {resp.status_code}")

    # ---------- Loki ----------
    def test_loki_ready(self):
        if not is_port_open(self.host, self.loki_port):
            self.skipTest("Loki port not open; skipping")
        # Loki readiness/health endpoints vary; /ready is widely available
        resp = http_get(f"http://{self.host}:{self.loki_port}/ready")
        self.assertIsNotNone(resp, "Loki /ready not reachable")
        self.assertEqual(resp.status_code, 200, f"Loki ready HTTP {resp.status_code}")

    # ---------- Ragserve ----------
    def test_ragserve_health_or_port(self):
        if not is_port_open(self.host, self.ragserve_port):
            self.skipTest("Ragserve port not open; skipping")
        # Prefer an explicit /health endpoint if present; otherwise accept any 2xx on /
        resp = http_get(f"http://{self.host}:{self.ragserve_port}/health")
        if resp is None or resp.status_code >= 400:
            resp = http_get(f"http://{self.host}:{self.ragserve_port}/")
        self.assertIsNotNone(resp, "Ragserve not reachable over HTTP")
        self.assertLess(resp.status_code, 400, f"Ragserve HTTP {resp.status_code}")

    # ---------- Benthos config ----------
    def test_benthos_yaml_parses(self):
        self.assertTrue(self.benthos_yaml.exists(), f"Missing Benthos config: {self.benthos_yaml}")
        with self.benthos_yaml.open("r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        # Minimal sanity checks that catch common config mistakes
        self.assertIsInstance(data, dict, "Benthos YAML must be a mapping")
        self.assertTrue(any(k in data for k in ("input", "pipeline", "output")), "Benthos config missing core sections")


if __name__ == "__main__":
    unittest.main()