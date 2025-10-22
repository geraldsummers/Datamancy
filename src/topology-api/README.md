# Topology API - Real-time Container Monitoring

A Node.js WebSocket server that provides real-time updates about Docker container status and topology.

## Features

- **Real-time Updates**: WebSocket connection with instant push notifications
- **Docker Events Stream**: Listens to Docker events for immediate status changes
- **Auto-discovery**: Automatically detects containers, networks, and volumes
- **Connection Mapping**: Intelligently maps container relationships
- **Type Classification**: Categorizes containers (database, monitoring, application, etc.)
- **HTTP Fallback**: REST API endpoint for polling-based clients

## Endpoints

### WebSocket
- **URL**: `wss://topology-api.lab.localhost`
- **Protocol**: WebSocket
- **Messages**:
  - `topology_initial`: Sent on connection with current state
  - `topology_update`: Sent when containers change
  - Client can send `{"type": "refresh"}` to force update

### HTTP REST API
- **GET /api/topology**: Get current topology data
- **GET /health**: Health check and statistics

## Architecture

```
Docker Daemon
     ↓
Docker Socket Proxy (read-only)
     ↓
Topology API (this service)
     ↓
WebSocket → Frontend (topology.html)
```

## Data Structure

```json
{
  "containers": [
    {
      "id": "abc123",
      "name": "container-name",
      "status": "running",
      "state": "running",
      "image": "nginx:latest",
      "type": "application",
      "connections": ["other-container", "volume-name"],
      "networks": ["app_net"],
      "created": 1234567890,
      "labels": {}
    }
  ],
  "networks": [
    {
      "id": "def456",
      "name": "app_net",
      "driver": "bridge",
      "scope": "local"
    }
  ],
  "lastUpdate": "2025-10-21T..."
}
```

## Container Type Detection

The service automatically categorizes containers:

- **database**: postgres, mysql, mariadb, mongo, redis, clickhouse
- **monitoring**: prometheus, grafana, loki, alertmanager
- **proxy**: caddy, authentik, traefik
- **infrastructure**: watchtower, socket-proxy
- **application**: everything else
- **storage**: volumes

## Connection Detection

Connections are discovered through:
1. Shared Docker networks
2. Volume mounts
3. Inter-container communication

## Real-time Events

The service monitors Docker events and pushes updates for:
- Container start/stop
- Container create/destroy
- Container pause/unpause
- Container restart

## Development

```bash
# Install dependencies
npm install

# Start server
npm start

# Or with custom port
PORT=3100 npm start
```

## Environment Variables

- `DOCKER_HOST`: Docker socket proxy URL (default: `http://docker-socket-proxy:2375`)
- `PORT`: Server port (default: `3100`)

## Usage Example

```javascript
// Connect to WebSocket
const ws = new WebSocket('wss://topology-api.lab.localhost');

ws.onmessage = (event) => {
  const message = JSON.parse(event.data);

  if (message.type === 'topology_initial') {
    console.log('Initial data:', message.data);
  }

  if (message.type === 'topology_update') {
    console.log('Update:', message.event);
    console.log('New data:', message.data);
  }
};

// Request manual refresh
ws.send(JSON.stringify({ type: 'refresh' }));
```

## Monitoring

Check service health:
```bash
curl https://topology-api.lab.localhost/health
```

Response:
```json
{
  "status": "ok",
  "lastUpdate": "2025-10-21T...",
  "containers": 25,
  "connectedClients": 3
}
```
