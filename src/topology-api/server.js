const express = require('express');
const http = require('http');
const WebSocket = require('ws');
const axios = require('axios');
const cors = require('cors');

const app = express();
const server = http.createServer(app);
const wss = new WebSocket.Server({ server });

app.use(cors());
app.use(express.json());

const DOCKER_SOCKET_PROXY = process.env.DOCKER_HOST || 'http://docker-socket-proxy:2375';

// Store for container data
let currentTopology = {
    containers: [],
    networks: [],
    lastUpdate: null
};

// Helper function to determine connection type
function getConnectionType(containerName) {
    if (containerName.includes('db') || containerName.includes('postgres') ||
        containerName.includes('mysql') || containerName.includes('mariadb') ||
        containerName.includes('mongo') || containerName.includes('redis') ||
        containerName.includes('clickhouse')) {
        return 'database';
    }
    if (containerName.includes('caddy') || containerName.includes('proxy')) {
        return 'proxy';
    }
    if (containerName.includes('data') || containerName.includes('config')) {
        return 'storage';
    }
    return 'network';
}

// Helper function to determine node type
function getNodeType(containerName, labels) {
    if (containerName.includes('db') || containerName.includes('postgres') ||
        containerName.includes('mysql') || containerName.includes('mariadb') ||
        containerName.includes('mongo') || containerName.includes('redis') ||
        containerName.includes('clickhouse')) {
        return 'database';
    }
    if (containerName.includes('prometheus') || containerName.includes('grafana') ||
        containerName.includes('loki') || containerName.includes('alertmanager') ||
        containerName.includes('promtail')) {
        return 'monitoring';
    }
    if (containerName.includes('caddy') || containerName.includes('authelia') ||
        containerName.includes('proxy')) {
        return 'proxy';
    }
    if (containerName.includes('watchtower') || containerName.includes('socket-proxy')) {
        return 'infrastructure';
    }
    return 'application';
}

// Fetch container data from Docker API
async function fetchContainerData() {
    try {
        console.log('Fetching container data from Docker API...');

        // Fetch all containers
        const containersResponse = await axios.get(`${DOCKER_SOCKET_PROXY}/containers/json?all=true`);
        const containers = containersResponse.data;

        // Fetch networks
        const networksResponse = await axios.get(`${DOCKER_SOCKET_PROXY}/networks`);
        const networks = networksResponse.data;

        console.log(`Found ${containers.length} containers and ${networks.length} networks`);

        // Process container data
        const processedContainers = containers.map(container => {
            const name = container.Names[0].replace('/', '');
            const state = container.State.toLowerCase();
            const status = state === 'running' ? 'running' :
                          state === 'paused' ? 'paused' :
                          state === 'restarting' ? 'restarting' : 'stopped';

            // Get network connections
            const networkConnections = Object.keys(container.NetworkSettings.Networks || {});

            // Find connected containers via shared networks
            const connections = [];
            networkConnections.forEach(network => {
                const networkData = networks.find(n => n.Name === network);
                if (networkData && networkData.Containers) {
                    Object.values(networkData.Containers).forEach(conn => {
                        const connectedName = conn.Name;
                        if (connectedName !== name && !connections.includes(connectedName)) {
                            connections.push(connectedName);
                        }
                    });
                }
            });

            // Add volume connections
            if (container.Mounts) {
                container.Mounts.forEach(mount => {
                    if (mount.Type === 'volume' && mount.Name) {
                        connections.push(mount.Name);
                    }
                });
            }

            return {
                id: container.Id.substring(0, 12),
                name: name,
                status: status,
                state: state,
                image: container.Image,
                type: getNodeType(name, container.Labels),
                connections: connections,
                networks: networkConnections,
                created: container.Created,
                labels: container.Labels || {}
            };
        });

        // Add volume nodes
        const volumeNames = new Set();
        containers.forEach(container => {
            if (container.Mounts) {
                container.Mounts.forEach(mount => {
                    if (mount.Type === 'volume' && mount.Name) {
                        volumeNames.add(mount.Name);
                    }
                });
            }
        });

        const volumeNodes = Array.from(volumeNames).map(name => ({
            id: `volume-${name}`,
            name: name,
            status: 'running',
            state: 'running',
            type: 'storage',
            connections: [],
            networks: [],
            isVolume: true
        }));

        const allNodes = [...processedContainers, ...volumeNodes];

        currentTopology = {
            containers: allNodes,
            networks: networks.map(n => ({
                id: n.Id.substring(0, 12),
                name: n.Name,
                driver: n.Driver,
                scope: n.Scope
            })),
            lastUpdate: new Date().toISOString()
        };

        console.log('Topology data updated successfully');
        return currentTopology;

    } catch (error) {
        console.error('Error fetching container data:', error.message);
        return null;
    }
}

// Broadcast to all connected WebSocket clients
function broadcast(data) {
    const message = JSON.stringify(data);
    wss.clients.forEach(client => {
        if (client.readyState === WebSocket.OPEN) {
            client.send(message);
        }
    });
}

// Listen to Docker events for real-time updates
async function listenToDockerEvents() {
    try {
        console.log('Starting Docker event stream...');

        const response = await axios.get(`${DOCKER_SOCKET_PROXY}/events`, {
            responseType: 'stream',
            timeout: 0
        });

        response.data.on('data', async (chunk) => {
            try {
                const events = chunk.toString().split('\n').filter(e => e.trim());

                for (const eventStr of events) {
                    const event = JSON.parse(eventStr);

                    // React to container lifecycle events
                    if (event.Type === 'container' &&
                        ['start', 'stop', 'die', 'pause', 'unpause', 'restart', 'create', 'destroy'].includes(event.Action)) {

                        console.log(`Container event: ${event.Action} - ${event.Actor.Attributes.name}`);

                        // Refresh topology data
                        const newTopology = await fetchContainerData();

                        if (newTopology) {
                            // Broadcast to all connected clients
                            broadcast({
                                type: 'topology_update',
                                data: newTopology,
                                event: {
                                    action: event.Action,
                                    container: event.Actor.Attributes.name,
                                    timestamp: new Date(event.time * 1000).toISOString()
                                }
                            });
                        }
                    }
                }
            } catch (parseError) {
                console.error('Error parsing event:', parseError.message);
            }
        });

        response.data.on('error', (error) => {
            console.error('Event stream error:', error.message);
            // Reconnect after 5 seconds
            setTimeout(listenToDockerEvents, 5000);
        });

    } catch (error) {
        console.error('Error connecting to Docker events:', error.message);
        // Retry after 5 seconds
        setTimeout(listenToDockerEvents, 5000);
    }
}

// WebSocket connection handler
wss.on('connection', async (ws) => {
    console.log('New WebSocket client connected');

    // Send current topology immediately on connection
    const topology = currentTopology.lastUpdate ?
        currentTopology :
        await fetchContainerData();

    if (topology) {
        ws.send(JSON.stringify({
            type: 'topology_initial',
            data: topology
        }));
    }

    ws.on('message', async (message) => {
        try {
            const data = JSON.parse(message);

            if (data.type === 'refresh') {
                const newTopology = await fetchContainerData();
                if (newTopology) {
                    ws.send(JSON.stringify({
                        type: 'topology_update',
                        data: newTopology
                    }));
                }
            }
        } catch (error) {
            console.error('Error handling WebSocket message:', error.message);
        }
    });

    ws.on('close', () => {
        console.log('WebSocket client disconnected');
    });

    ws.on('error', (error) => {
        console.error('WebSocket error:', error.message);
    });
});

// REST API endpoint for HTTP fallback
app.get('/api/topology', async (req, res) => {
    try {
        const topology = await fetchContainerData();
        if (topology) {
            res.json(topology);
        } else {
            res.status(500).json({ error: 'Failed to fetch topology data' });
        }
    } catch (error) {
        res.status(500).json({ error: error.message });
    }
});

// Health check endpoint
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        lastUpdate: currentTopology.lastUpdate,
        containers: currentTopology.containers.length,
        connectedClients: wss.clients.size
    });
});

// Initialize
async function start() {
    console.log('Starting Topology API server...');

    // Fetch initial data
    await fetchContainerData();

    // Start listening to Docker events
    listenToDockerEvents();

    // Start server
    const PORT = process.env.PORT || 3100;
    server.listen(PORT, '0.0.0.0', () => {
        console.log(`Topology API server listening on port ${PORT}`);
        console.log(`WebSocket endpoint: ws://localhost:${PORT}`);
        console.log(`HTTP endpoint: http://localhost:${PORT}/api/topology`);
    });
}

start();
