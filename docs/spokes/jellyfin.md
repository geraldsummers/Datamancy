# Jellyfin — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** `jellyfin.stack.local`
**Dependencies:** Caddy
**Profile:** media

## Purpose

Jellyfin is a free, open-source media server for managing and streaming personal media libraries (movies, TV shows, music, photos). It provides transcoding, remote access, and cross-platform client apps.

## Configuration

**Image:** `jellyfin/jellyfin:10.10.3`
**Volumes:**
- `jellyfin_config:/config` (Jellyfin configuration and database)
- `jellyfin_cache:/cache` (Transcoding cache)
- `jellyfin_media:/media` (Internal media storage)
- `./data/jellyfin/media:/media/library:ro` (Host media directory, read-only)

**Networks:** frontend
**Ports:** 8096 (HTTP), 8920 (HTTPS), 7359 (discovery), 1900 (DLNA)

### Key Settings

```yaml
environment:
  JELLYFIN_PublishedServerUrl: https://jellyfin.stack.local
```

### Security Considerations

- **Runs as root** - Required for hardware transcoding and device access
- **No capability restrictions** - Media transcoding requires broad permissions
- **Read-only media** - Host media directory mounted read-only for safety
- **HTTPS via Caddy** - All external access through reverse proxy

### Fingerprint Inputs

- Image: `jellyfin/jellyfin:10.10.3`
- Config volume: `jellyfin_config`
- Compose stanza: `services.jellyfin`

## Access

- **URL:** `https://jellyfin.stack.local`
- **Auth:** Built-in user authentication
- **Setup wizard:** First run requires admin account creation

## Runbook

### Start/Stop

```bash
# Start Jellyfin
docker compose --profile media up -d jellyfin

# Stop
docker compose stop jellyfin
```

### Logs

```bash
docker compose logs -f jellyfin
```

### Initial Setup

1. Access `https://jellyfin.stack.local`
2. Complete setup wizard:
   - Set language and admin account
   - Add media libraries (point to `/media/library/`)
   - Configure metadata providers (TMDb, TheTVDB, etc.)
   - Enable hardware transcoding if available
3. Scan media libraries

### Adding Media

Place media files in `data/jellyfin/media/` on the host:

```bash
mkdir -p data/jellyfin/media/{movies,tv,music,photos}
# Add your media files
docker compose exec jellyfin jellyfin library refresh
```

### Common Issues

**Symptom:** Transcoding fails or stutters
**Cause:** No hardware acceleration or insufficient CPU
**Fix:** Enable hardware transcoding in Dashboard → Playback settings

**Symptom:** Media library not scanning
**Cause:** Permissions or missing metadata
**Fix:** Check file permissions and enable debug logging:
```bash
docker compose exec jellyfin ls -la /media/library/
```

**Symptom:** 502 Bad Gateway
**Cause:** Jellyfin not started or crashed
**Fix:** Check logs: `docker compose logs jellyfin | tail -50`

## Hardware Transcoding

### Intel QuickSync (recommended)

Add to docker-compose.yml:

```yaml
devices:
  - /dev/dri:/dev/dri
```

Enable in Dashboard → Playback → Hardware acceleration → Intel QuickSync

### NVIDIA GPU

Requires nvidia-docker runtime:

```yaml
runtime: nvidia
environment:
  NVIDIA_VISIBLE_DEVICES: all
```

Enable in Dashboard → Playback → Hardware acceleration → NVIDIA NVENC

### VA-API (AMD/Intel)

```yaml
devices:
  - /dev/dri/renderD128:/dev/dri/renderD128
```

Enable in Dashboard → Playback → Hardware acceleration → VA-API

## Backup & Restore

### Backup Configuration

```bash
docker compose stop jellyfin
docker run --rm -v datamancy_jellyfin_config:/data -v $(pwd):/backup alpine \
  tar czf /backup/jellyfin-config-backup.tar.gz -C /data .
docker compose start jellyfin
```

### Restore Configuration

```bash
docker compose stop jellyfin
docker run --rm -v datamancy_jellyfin_config:/data -v $(pwd):/backup alpine \
  tar xzf /backup/jellyfin-config-backup.tar.gz -C /data
docker compose start jellyfin
```

## Testing

**Smoke test:** Visit `https://jellyfin.stack.local`, verify login page loads, authenticate
**Integration tests:** `tests/specs/phase8-extended-apps.spec.ts`
**Last pass:** Check `data/tests/jellyfin/last_pass.json`

## Client Applications

- **Web:** `https://jellyfin.stack.local`
- **Android/iOS:** Jellyfin mobile apps
- **Desktop:** Jellyfin Media Player (Windows/Mac/Linux)
- **TV:** Android TV, Roku, Apple TV, etc.
- **Kodi:** Jellyfin for Kodi plugin

## Performance Tuning

### Transcoding Settings

1. **Hardware acceleration:** Enable for your GPU
2. **Transcoding threads:** Set to CPU core count
3. **Throttle transcoding:** Enable to reduce CPU usage
4. **Allow subtitle extraction:** May increase CPU load

### Network Settings

1. **LAN bandwidth:** Set to 100+ Mbps for local playback
2. **WAN bandwidth:** Adjust based on upload speed
3. **Enable automatic port mapping:** For external access

### Library Settings

1. **Real-time monitoring:** Enable for instant updates
2. **Scheduled tasks:** Run library scans at off-peak hours
3. **Chapter image extraction:** Disable if storage is limited

## Security Considerations

1. **User management:** Create separate user accounts, avoid sharing admin
2. **Parental controls:** Configure content ratings and user access
3. **API keys:** Secure API keys for third-party integrations
4. **External access:** Use VPN or Authelia proxy for remote access
5. **HTTPS enforcement:** Always access via Caddy reverse proxy

## Features

- **Media streaming:** Movies, TV, music, photos, books
- **Transcoding:** On-the-fly format conversion
- **Metadata:** Automatic poster, fanart, and info fetching
- **Subtitles:** OpenSubtitles integration
- **Live TV & DVR:** IPTV and antenna support (with tuner)
- **Plugins:** Extend functionality (intro skipper, etc.)
- **Multi-user:** Separate profiles and watch history

## Related

- Dependencies: [Caddy](caddy.md)
- Alternative: [Plex](https://www.plex.tv/) (proprietary), [Emby](https://emby.media/)
- Upstream docs: https://jellyfin.org/docs/

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
