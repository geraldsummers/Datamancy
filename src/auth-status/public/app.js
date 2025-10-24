// WebSocket connection
let ws;
let reconnectTimeout;
const WS_URL = `ws://${window.location.host}/ws`;

// State
let currentFilter = 'all';
let servicesData = {};

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    connectWebSocket();
    setupEventListeners();
    fetchInitialStatus();
});

// WebSocket connection
function connectWebSocket() {
    console.log('Connecting to WebSocket...');
    updateConnectionStatus('connecting');

    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        console.log('WebSocket connected');
        updateConnectionStatus('connected');
        if (reconnectTimeout) {
            clearTimeout(reconnectTimeout);
            reconnectTimeout = null;
        }
    };

    ws.onmessage = (event) => {
        const message = JSON.parse(event.data);
        handleWebSocketMessage(message);
    };

    ws.onclose = () => {
        console.log('WebSocket disconnected');
        updateConnectionStatus('disconnected');
        reconnect();
    };

    ws.onerror = (error) => {
        console.error('WebSocket error:', error);
        updateConnectionStatus('error');
    };
}

function reconnect() {
    if (!reconnectTimeout) {
        reconnectTimeout = setTimeout(() => {
            console.log('Reconnecting...');
            connectWebSocket();
        }, 3000);
    }
}

function updateConnectionStatus(status) {
    const statusEl = document.getElementById('wsStatus');
    const statusDot = document.querySelector('.connection-status .status-dot');

    switch(status) {
        case 'connected':
            statusEl.textContent = 'Connected';
            statusDot.style.background = 'var(--success)';
            break;
        case 'connecting':
            statusEl.textContent = 'Connecting...';
            statusDot.style.background = 'var(--warning)';
            break;
        case 'disconnected':
        case 'error':
            statusEl.textContent = 'Disconnected';
            statusDot.style.background = 'var(--error)';
            break;
    }
}

// Handle WebSocket messages
function handleWebSocketMessage(message) {
    if (message.type === 'status') {
        updateAllStatus(message.data);
    } else if (message.type === 'update') {
        updateServiceStatus(message.service, message.data);
    } else if (message.type === 'error') {
        console.error('Server error:', message.message);
    }
}

// Fetch initial status via REST
async function fetchInitialStatus() {
    try {
        const response = await fetch('/api/status');
        const data = await response.json();
        updateAllStatus(data);
    } catch (error) {
        console.error('Failed to fetch initial status:', error);
    }
}

// Update all status
function updateAllStatus(data) {
    // Update Authentik
    updateAuthentikStatus(data.authentik);

    // Update services
    servicesData = data.services;
    renderServices();

    // Update last update time
    if (data.lastUpdate) {
        document.getElementById('lastUpdate').textContent = new Date(data.lastUpdate).toLocaleTimeString();
    }

    // Update filter counts
    updateFilterCounts();
}

// Update Authentik status
function updateAuthentikStatus(authentik) {
    if (!authentik) return;

    const card = document.getElementById('authentikCard');
    card.className = `status-card authentik ${authentik.status}`;

    const statusText = card.querySelector('.status-text');
    statusText.textContent = authentik.status.charAt(0).toUpperCase() + authentik.status.slice(1);

    // Update details
    if (authentik.details) {
        document.getElementById('authentik-web').textContent =
            authentik.details.webInterface?.message || '-';
        document.getElementById('authentik-oidc').textContent =
            authentik.details.oidcDiscovery?.message || '-';
        document.getElementById('authentik-forward').textContent =
            authentik.details.forwardAuth?.message || '-';
    }

    document.getElementById('authentik-duration').textContent =
        authentik.duration ? `${authentik.duration}ms` : '-';
    document.getElementById('authentik-message').textContent = authentik.message || '';
}

// Render services
function renderServices() {
    const grid = document.getElementById('servicesGrid');
    grid.innerHTML = '';

    Object.entries(servicesData).forEach(([name, service]) => {
        const card = createServiceCard(name, service);
        grid.appendChild(card);
    });

    applyFilter(currentFilter);
}

// Create service card
function createServiceCard(name, service) {
    const card = document.createElement('div');
    card.className = `service-card ${service.status}`;
    card.dataset.name = name;
    card.dataset.authType = service.authType;
    card.dataset.status = service.status;

    card.innerHTML = `
        <div class="service-header">
            <span class="service-name">${name}</span>
            <span class="service-auth-type ${service.authType}">${service.authType.replace('_', ' ')}</span>
        </div>
        <div class="service-description">${service.description || service.url}</div>
        <div class="service-status">
            <span class="status-dot"></span>
            <span>${service.status}</span>
        </div>
        <div class="service-duration">${service.duration}ms • ${new Date(service.timestamp).toLocaleTimeString()}</div>
    `;

    card.addEventListener('click', () => showServiceDetails(name, service));

    return card;
}

// Show service details modal
function showServiceDetails(name, service) {
    const modal = document.getElementById('modal');
    const title = document.getElementById('modalTitle');
    const body = document.getElementById('modalBody');

    title.textContent = name;

    body.innerHTML = `
        <p><strong>URL:</strong> ${service.url}</p>
        <p><strong>Auth Type:</strong> ${service.authType.replace('_', ' ')}</p>
        <p><strong>Status:</strong> ${service.status}</p>
        <p><strong>Message:</strong> ${service.message}</p>
        <p><strong>Test Duration:</strong> ${service.duration}ms</p>
        <p><strong>Last Tested:</strong> ${new Date(service.timestamp).toLocaleString()}</p>
        ${service.details ? `
            <p><strong>Details:</strong></p>
            <pre>${JSON.stringify(service.details, null, 2)}</pre>
        ` : ''}
        <button class="btn btn-primary" onclick="testService('${name}')">Test Now</button>
    `;

    modal.classList.add('show');
}

// Test specific service
async function testService(name) {
    try {
        const response = await fetch(`/api/test/${name}`, { method: 'POST' });
        const data = await response.json();
        console.log('Test result:', data);
        // Modal will update via WebSocket
    } catch (error) {
        console.error('Test failed:', error);
    }
}

// Setup event listeners
function setupEventListeners() {
    // Refresh button
    document.getElementById('refreshBtn').addEventListener('click', async () => {
        try {
            await fetch('/api/test', { method: 'POST' });
        } catch (error) {
            console.error('Failed to trigger test:', error);
        }
    });

    // Filter tabs
    document.querySelectorAll('.tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            const filter = tab.dataset.filter;
            currentFilter = filter;
            applyFilter(filter);
        });
    });

    // Modal close
    document.querySelector('.modal-close').addEventListener('click', () => {
        document.getElementById('modal').classList.remove('show');
    });

    document.getElementById('modal').addEventListener('click', (e) => {
        if (e.target.id === 'modal') {
            document.getElementById('modal').classList.remove('show');
        }
    });
}

// Apply filter
function applyFilter(filter) {
    const cards = document.querySelectorAll('.service-card');

    cards.forEach(card => {
        const authType = card.dataset.authType;
        const status = card.dataset.status;

        let show = false;

        switch(filter) {
            case 'all':
                show = true;
                break;
            case 'oidc':
                show = authType === 'oidc';
                break;
            case 'forward_auth':
                show = authType === 'forward_auth';
                break;
            case 'healthy':
                show = status === 'healthy';
                break;
            case 'unhealthy':
                show = status === 'unhealthy';
                break;
        }

        if (show) {
            card.classList.remove('hidden');
        } else {
            card.classList.add('hidden');
        }
    });
}

// Update filter counts
function updateFilterCounts() {
    const services = Object.values(servicesData);
    const total = services.length;
    const oidc = services.filter(s => s.authType === 'oidc').length;
    const forwardAuth = services.filter(s => s.authType === 'forward_auth').length;
    const healthy = services.filter(s => s.status === 'healthy').length;
    const unhealthy = services.filter(s => s.status === 'unhealthy').length;

    document.querySelector('[data-filter="all"]').textContent = `All (${total})`;
    document.querySelector('[data-filter="oidc"]').textContent = `OIDC (${oidc})`;
    document.querySelector('[data-filter="forward_auth"]').textContent = `Forward Auth (${forwardAuth})`;
    document.querySelector('[data-filter="healthy"]').textContent = `✓ Healthy (${healthy})`;
    document.querySelector('[data-filter="unhealthy"]').textContent = `✗ Unhealthy (${unhealthy})`;
}
