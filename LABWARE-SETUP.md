# Labware Docker Socket Setup

The CI/CD integration tests require a labware Docker daemon with insecure registry configuration.

## Issue

Integration test "Push image to registry" fails with:
```
http: server gave HTTP response to HTTPS client
```

## Root Cause

The labware Docker daemon at `/run/labware-docker.sock` is not configured to allow insecure (HTTP) registry access to `192.168.0.11:5000`.

## Fix Required (Server-Side Configuration)

The labware Docker daemon's `/etc/docker/daemon.json` must include:

```json
{
  "insecure-registries": ["192.168.0.11:5000", "registry:5000"]
}
```

After modifying daemon.json, restart the labware Docker daemon:
```bash
sudo systemctl restart docker-labware  # or equivalent service name
```

## Verification

Test registry push from labware:
```bash
docker -H unix:///run/labware-docker.sock pull alpine:latest
docker -H unix:///run/labware-docker.sock tag alpine:latest 192.168.0.11:5000/test:latest
docker -H unix:///run/labware-docker.sock push 192.168.0.11:5000/test:latest
```

If successful, the integration test should pass.

## Alternative: Use Registry DNS Name

If labware containers can reach the `registry` network, you can use `registry:5000` instead of the host IP. This requires labware containers to be on the same Docker network as the registry.

---

**Note**: This is a deployment/infrastructure configuration, not a code change. The local repo code is correct.
