# Home Assistant — Spoke

**Status:** 🟢 Functional
**Phase:** 8
**Hostname:** `home.stack.local`
**Dependencies:** Caddy
**Profile:** automation

## Purpose

Home Assistant is an open-source home automation platform that focuses on privacy and local control. It integrates with thousands of IoT devices, services, and protocols to provide centralized smart home management.

## Configuration

**Image:** `ghcr.io/home-assistant/home-assistant:2024.10.4`
**Volumes:**
- `homeassistant_config:/config` (Configuration and database)
- `/etc/localtime:/etc/localtime:ro` (Host timezone sync)

**Networks:** frontend, backend
**Ports:** 8123 (HTTP/WebSocket)

### Key Settings

```yaml
environment:
  TZ: UTC
```

### Security Considerations

- **Runs as root** - Required for device access and network operations
- **Privileged mode** - Needed for IoT device discovery and USB access
- **No capability restrictions** - Full system access required
- **WebSocket support** - Caddy configured for WebSocket passthrough
- **HTTPS via Caddy** - All external access through reverse proxy

### Fingerprint Inputs

- Image: `ghcr.io/home-assistant/home-assistant:2024.10.4`
- Config volume: `homeassistant_config`
- Compose stanza: `services.homeassistant`

## Access

- **URL:** `https://home.stack.local`
- **Auth:** Built-in user authentication and MFA
- **API:** REST API and WebSocket API
- **Mobile apps:** iOS and Android companion apps

## Runbook

### Start/Stop

```bash
# Start Home Assistant
docker compose --profile automation up -d homeassistant

# Stop
docker compose stop homeassistant
```

### Logs

```bash
docker compose logs -f homeassistant

# Live logs from inside container
docker compose exec homeassistant cat /config/home-assistant.log
```

### Initial Setup

1. Access `https://home.stack.local`
2. Complete onboarding wizard:
   - Create admin account with strong password
   - Set location and timezone
   - Enable MFA (highly recommended)
3. Configure integrations (devices, services)
4. Create automations and dashboards

### Configuration Files

Home Assistant uses YAML for configuration:

```bash
# Edit configuration
docker compose exec homeassistant nano /config/configuration.yaml

# Validate configuration
docker compose exec homeassistant hass --script check_config -c /config

# Reload configuration (no restart required)
# Use Developer Tools → YAML → Reload in UI
```

### Common Issues

**Symptom:** "Unable to connect" on startup
**Cause:** Configuration error or database corruption
**Fix:** Check logs and validate config:
```bash
docker compose logs homeassistant | grep -i error
docker compose exec homeassistant hass --script check_config -c /config
```

**Symptom:** Devices not discovered
**Cause:** Network isolation or privileged mode not enabled
**Fix:** Verify `privileged: true` in docker-compose.yml, check network mode

**Symptom:** WebSocket connection failed
**Cause:** Caddy WebSocket configuration missing
**Fix:** Verify Caddyfile has WebSocket upgrade support for home.stack.local

**Symptom:** Integration authentication failing
**Cause:** Incorrect credentials or API tokens
**Fix:** Re-authenticate integration in Settings → Devices & Services

## Device Integration

### USB Devices (Zigbee, Z-Wave)

Add USB device passthrough:

```yaml
devices:
  - /dev/ttyUSB0:/dev/ttyUSB0  # Zigbee/Z-Wave dongle
```

Restart Home Assistant and configure Zigbee/Z-Wave integration.

### Network Discovery

Home Assistant requires privileged mode for mDNS and device discovery:

```yaml
privileged: true
network_mode: host  # Alternative for better discovery
```

**Note:** Using `network_mode: host` bypasses Caddy. Use reverse proxy setup instead.

## Backup & Restore

### Built-in Backup

Home Assistant has built-in backup:

1. Settings → System → Backups
2. Create backup (includes config, automations, add-ons)
3. Download backup file

### Manual Volume Backup

```bash
docker compose stop homeassistant
docker run --rm -v datamancy_homeassistant_config:/data -v $(pwd):/backup alpine \
  tar czf /backup/homeassistant-backup.tar.gz -C /data .
docker compose start homeassistant
```

### Restore

```bash
docker compose stop homeassistant
docker run --rm -v datamancy_homeassistant_config:/data -v $(pwd):/backup alpine \
  tar xzf /backup/homeassistant-backup.tar.gz -C /data
docker compose start homeassistant
```

## Testing

**Smoke test:** Visit `https://home.stack.local`, verify login page loads, authenticate
**Integration tests:** `tests/specs/phase8-extended-apps.spec.ts`
**Last pass:** Check `data/tests/homeassistant/last_pass.json`

## Popular Integrations

### IoT Protocols
- **Zigbee:** Zigbee2MQTT, ZHA (Zigbee Home Automation)
- **Z-Wave:** Z-Wave JS
- **Matter:** Matter protocol support
- **Thread:** Thread network support

### Smart Home Platforms
- **Google Home:** Google Assistant integration
- **Amazon Alexa:** Alexa Smart Home Skill
- **Apple HomeKit:** HomeKit Bridge
- **MQTT:** Lightweight IoT messaging

### Services
- **Weather:** OpenWeatherMap, Met.no
- **Calendar:** Google Calendar, CalDAV
- **Media:** Spotify, Plex, Jellyfin
- **Notifications:** Email, Pushover, Telegram

### Device Brands
- Philips Hue, IKEA Tradfri, TP-Link Kasa, Shelly, Sonoff, Tuya, and 2000+ more

## Automations

### Example: Motion-activated lights

```yaml
automation:
  - alias: "Turn on lights on motion"
    trigger:
      - platform: state
        entity_id: binary_sensor.motion_sensor
        to: "on"
    action:
      - service: light.turn_on
        target:
          entity_id: light.living_room
```

### Example: Energy monitoring alert

```yaml
automation:
  - alias: "High energy usage alert"
    trigger:
      - platform: numeric_state
        entity_id: sensor.power_consumption
        above: 3000
    action:
      - service: notify.telegram
        data:
          message: "Energy usage exceeds 3kW!"
```

## Performance Tuning

### Recorder Settings

Limit database growth by excluding domains:

```yaml
recorder:
  purge_keep_days: 7
  exclude:
    domains:
      - sun
      - automation
    entity_globs:
      - sensor.weather_*
```

### Logger Settings

Reduce log verbosity:

```yaml
logger:
  default: warning
  logs:
    homeassistant.core: info
    homeassistant.components.mqtt: debug
```

## Security Considerations

1. **Strong passwords:** Enable MFA for all accounts
2. **Trusted networks:** Configure trusted networks for auth bypass
3. **API tokens:** Use long-lived access tokens for integrations
4. **Secrets management:** Use `secrets.yaml` for sensitive data
5. **External access:** Use VPN or Cloudflare Tunnel for remote access
6. **Regular updates:** Keep Home Assistant and integrations updated

## Add-ons vs Integrations

- **Integrations:** Connect devices and services (e.g., Philips Hue)
- **Add-ons:** Extend functionality (e.g., Mosquitto MQTT, Node-RED)

**Note:** Home Assistant Container (Docker) does not support Add-ons. Use separate containers instead.

## Features

- **2000+ integrations:** Connect almost any device
- **Automations:** Powerful rule engine with conditions and triggers
- **Dashboards:** Customizable Lovelace UI
- **Voice control:** Alexa, Google Assistant, Siri
- **Energy management:** Monitor and optimize energy usage
- **Presence detection:** Device trackers and geofencing
- **Notifications:** Push, email, SMS, TTS
- **Blueprints:** Reusable automation templates

## Related

- Dependencies: [Caddy](caddy.md)
- Complementary: [Node-RED](https://nodered.org/) (visual automation), [Mosquitto](https://mosquitto.org/) (MQTT broker)
- Upstream docs: https://www.home-assistant.io/docs/

---

**Last updated:** 2025-10-27
**Last change fingerprint:** phase8-initial-implementation
