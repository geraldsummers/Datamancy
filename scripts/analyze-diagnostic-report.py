#!/usr/bin/env python3
import json
import os
import glob
import sys
from datetime import datetime

PROOFS_DIR = os.environ.get("PROOFS_DIR", os.path.join(os.getcwd(), "volumes/proofs"))


def find_latest_report():
    pattern = os.path.join(PROOFS_DIR, "stack_diagnostics_*.json")
    files = glob.glob(pattern)
    if not files:
        return None
    return max(files, key=os.path.getmtime)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_latest_report()
    if not path or not os.path.exists(path):
        print("No diagnostic report found. Run diagnostics first.")
        sys.exit(1)

    with open(path, "r") as f:
        data = json.load(f)

    summary = data.get("summary") or {}
    services = data.get("services") or []

    total = summary.get("total", len(services))
    healthy = summary.get("healthy", 0)
    degraded = summary.get("degraded", 0)
    failed = summary.get("failed", 0)

    print("STACK HEALTH REPORT")
    print("===================")
    print(f"Generated: {datetime.now().isoformat(timespec='seconds')}")
    print(f"Report file: {path}")
    print("")
    pct = (healthy / total * 100.0) if total else 0.0
    print(f"Status: {healthy}/{total} services healthy ({pct:.1f}%)")
    print("")

    criticals = []
    warnings = []
    for svc in services:
        name = svc.get("name")
        status = svc.get("overall_status")
        best_reason = svc.get("best_reason") or ""
        best_screenshot = svc.get("best_screenshot")
        if status == "failed":
            criticals.append((name, best_reason, best_screenshot))
        elif status == "degraded":
            warnings.append((name, best_reason, best_screenshot))

    print(f"CRITICAL ISSUES ({len(criticals)}):")
    if not criticals:
        print("- none")
    for i, (name, reason, shot) in enumerate(criticals, start=1):
        print(f"{i}. {name}: {reason or 'no reason'}")
        if shot:
            print(f"   Evidence: {shot}")

    print("")
    print(f"WARNINGS ({len(warnings)}):")
    if not warnings:
        print("- none")
    for i, (name, reason, shot) in enumerate(warnings, start=1):
        print(f"- {name}: {reason or 'no reason'}")
        if shot:
            print(f"  Evidence: {shot}")

    print("")
    print("Recommended actions:")
    print("- Inspect failing containers: docker ps --filter 'status=restarting' --filter 'status=exited'")
    print("- Check logs: docker logs <service> --tail=200")
    print("- Validate profiles and ports in docker-compose.yml")


if __name__ == "__main__":
    main()
