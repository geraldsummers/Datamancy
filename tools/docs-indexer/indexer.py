#!/usr/bin/env python3
"""
Docs Indexer — Fingerprint tracker + status.json generator
Provenance: Phase 0.5 freshness automation

Computes CONFIG_FINGERPRINT for each service and tracks:
- last_change (when fingerprint changed)
- last_test_pass (from test artifacts)
- spoke_updated (git commit time of Spoke doc)
- functional (test fresh relative to change)
- documented (Spoke fresh relative to change)
"""
import hashlib
import json
import os
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

import git
import yaml


def sha256_hex(data: str) -> str:
    """Compute SHA256 hex digest of string."""
    return hashlib.sha256(data.encode()).hexdigest()


def hash_directory(path: Path) -> str:
    """Hash all files in directory (recursive, sorted)."""
    if not path.exists():
        return sha256_hex("")

    hashes = []
    for file_path in sorted(path.rglob("*")):
        if file_path.is_file():
            with open(file_path, "rb") as f:
                file_hash = hashlib.sha256(f.read()).hexdigest()
                hashes.append(f"{file_path.relative_to(path)}:{file_hash}")

    return sha256_hex("\n".join(hashes))


def get_image_digest(image_name: str) -> str:
    """Get image digest via docker inspect subprocess."""
    try:
        result = subprocess.run(
            ["docker", "inspect", image_name, "--format={{.RepoDigests}}"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0 and result.stdout.strip():
            # Parse [digest1 digest2] format
            digests_str = result.stdout.strip().strip("[]")
            if digests_str:
                return digests_str.split()[0]  # Take first digest

        # Fallback: try image ID
        result = subprocess.run(
            ["docker", "inspect", image_name, "--format={{.Id}}"],
            capture_output=True,
            text=True,
            timeout=10
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    except Exception as e:
        print(f"Warning: Could not get digest for {image_name}: {e}")

    return sha256_hex(image_name)


def compute_service_fingerprint(
    service_name: str,
    service_config: dict,
    base_path: Path
) -> str:
    """Compute CONFIG_FINGERPRINT for a service."""
    components = []

    # 1. Image digest
    image = service_config.get("image", "")
    if image:
        components.append(f"image:{get_image_digest(image)}")

    # 2. Config directory hash (if exists)
    config_dir = base_path / "configs" / service_name
    if config_dir.exists():
        components.append(f"config_dir:{hash_directory(config_dir)}")

    # 3. Environment variables (sorted)
    env_vars = service_config.get("environment", {})
    if isinstance(env_vars, dict):
        env_str = "\n".join(f"{k}={v}" for k, v in sorted(env_vars.items()))
        components.append(f"env:{sha256_hex(env_str)}")
    elif isinstance(env_vars, list):
        env_str = "\n".join(sorted(env_vars))
        components.append(f"env:{sha256_hex(env_str)}")

    # 4. Compose service stanza (excluding 'profiles' to avoid noise)
    filtered_config = {k: v for k, v in service_config.items() if k != "profiles"}
    compose_stanza = yaml.dump(filtered_config, sort_keys=True)
    components.append(f"compose:{sha256_hex(compose_stanza)}")

    # Combine all components
    return sha256_hex("\n".join(components))


def get_last_test_pass(service_name: str, base_path: Path) -> Optional[str]:
    """Read last passing test timestamp from test artifacts."""
    test_dir = base_path / "data" / "tests" / service_name
    if not test_dir.exists():
        return None

    # Look for most recent last_pass.json
    last_pass_files = sorted(test_dir.glob("*/last_pass.json"), reverse=True)
    if not last_pass_files:
        return None

    try:
        with open(last_pass_files[0]) as f:
            data = json.load(f)
            return data.get("timestamp")
    except Exception as e:
        print(f"Warning: Could not read test timestamp for {service_name}: {e}")
        return None


def get_spoke_updated(service_name: str, repo: git.Repo) -> Optional[str]:
    """Get last commit time for service Spoke doc."""
    spoke_path = f"docs/spokes/{service_name}.md"

    try:
        # Get last commit that touched this file
        commits = list(repo.iter_commits(paths=spoke_path, max_count=1))
        if commits:
            return commits[0].committed_datetime.isoformat()
        return None
    except Exception as e:
        print(f"Warning: Could not get Spoke commit time for {service_name}: {e}")
        return None


def determine_status(
    last_change: Optional[str],
    last_test_pass: Optional[str],
    spoke_updated: Optional[str]
) -> tuple[str, bool, bool]:
    """
    Determine service status.

    Returns: (status, functional, documented)
    - status: "functional" | "stale" | "untested" | "undocumented"
    - functional: last_test_pass > last_change
    - documented: spoke_updated >= last_change
    """
    functional = False
    documented = False

    # Parse timestamps
    def parse_ts(ts):
        if not ts:
            return None
        try:
            return datetime.fromisoformat(ts.replace("Z", "+00:00"))
        except:
            return None

    change_dt = parse_ts(last_change)
    test_dt = parse_ts(last_test_pass)
    spoke_dt = parse_ts(spoke_updated)

    # Functional check
    if test_dt and change_dt and test_dt > change_dt:
        functional = True

    # Documented check
    if spoke_dt and change_dt and spoke_dt >= change_dt:
        documented = True

    # Determine overall status
    if not last_test_pass:
        status = "untested"
    elif not spoke_updated:
        status = "undocumented"
    elif functional and documented:
        status = "functional"
    else:
        status = "stale"

    return status, functional, documented


def main():
    base_path = Path("/app")
    compose_file = base_path / "docker-compose.yml"
    output_file = base_path / "docs" / "_data" / "status.json"

    # Ensure output directory exists
    output_file.parent.mkdir(parents=True, exist_ok=True)

    # Load docker-compose.yml
    with open(compose_file) as f:
        compose_data = yaml.safe_load(f)

    services = compose_data.get("services", {})

    # Initialize Git repo
    try:
        repo = git.Repo(base_path)
    except Exception as e:
        print(f"Warning: Could not initialize git repo: {e}")
        repo = None

    # Track fingerprints (in real impl, would load previous state)
    # For Phase 0.5, we'll just compute current fingerprints
    status_data = {}

    for service_name, service_config in services.items():
        print(f"Processing {service_name}...")

        # Compute fingerprint
        fingerprint = compute_service_fingerprint(
            service_name, service_config, base_path
        )

        # For Phase 0.5, last_change = now (no history yet)
        last_change = datetime.now().astimezone().isoformat()

        # Get test and spoke timestamps
        last_test_pass = get_last_test_pass(service_name, base_path)
        spoke_updated = get_spoke_updated(service_name, repo) if repo else None

        # Determine status
        status, functional, documented = determine_status(
            last_change, last_test_pass, spoke_updated
        )

        status_data[service_name] = {
            "status": status,
            "fingerprint": fingerprint[:16],  # Truncate for readability
            "last_change": last_change,
            "last_test_pass": last_test_pass,
            "spoke_updated": spoke_updated,
            "functional": functional,
            "documented": documented,
        }

    # Write status.json
    with open(output_file, "w") as f:
        json.dump(status_data, f, indent=2, sort_keys=True)

    print(f"\n✓ Generated {output_file}")


if __name__ == "__main__":
    main()
