// Tab Navigation
function showTab(tabName) {
    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });

    // Remove active from all buttons
    document.querySelectorAll('.tab-button').forEach(btn => {
        btn.classList.remove('active');
    });

    // Show selected tab
    document.getElementById(tabName).classList.add('active');
    event.target.classList.add('active');

    // Load tab data
    loadTabData(tabName);
}

// Load data for specific tab
function loadTabData(tabName) {
    switch(tabName) {
        case 'dashboard-tab':
            loadDashboardData();
            break;
        case 'config-tab':
            loadConfigData();
            break;
        case 'fetcher-tab':
            loadFetcherData();
            break;
        case 'indexer-tab':
            loadIndexerData();
            break;
        case 'storage-tab':
            loadStorageData();
            break;
        case 'logs-tab':
            loadLogsData();
            break;
    }
}

// Dashboard Tab
async function loadDashboardData() {
    try {
        const [sources, events] = await Promise.all([
            fetch('/api/fetcher/status').then(r => r.json()),
            fetch('/api/logs/recent?limit=10').then(r => r.json())
        ]);

        updateDashboardStats(sources);
        updateRecentEvents(events);
    } catch (error) {
        console.error('Failed to load dashboard data:', error);
    }
}

function updateDashboardStats(sources) {
    const activeSources = sources.filter(s => s.enabled).length;
    const recentlyFetched = sources.filter(s => {
        if (!s.lastFetch) return false;
        const lastFetch = new Date(s.lastFetch);
        const hourAgo = new Date(Date.now() - 3600000);
        return lastFetch > hourAgo;
    }).length;

    document.getElementById('active-sources').textContent = `${activeSources}/${sources.length}`;
    document.getElementById('recent-fetches').textContent = recentlyFetched;
}

function updateRecentEvents(events) {
    const container = document.getElementById('recent-events');
    if (!events || events.length === 0) {
        container.innerHTML = '<p style="color: #64748b; text-align: center;">No recent events</p>';
        return;
    }

    container.innerHTML = events.map(event => `
        <div class="log-entry">
            <span class="log-timestamp">${formatTimestamp(event.occurredAt)}</span>
            <span class="log-level ${event.eventType}">${event.eventType}</span>
            <span class="log-service">[${event.serviceName}]</span>
            <span class="log-message">${event.message}</span>
        </div>
    `).join('');
}

// Config Tab
async function loadConfigData() {
    try {
        const sources = await fetch('/api/config/sources').then(r => r.json());
        renderSourceConfigs(sources);
    } catch (error) {
        console.error('Failed to load config data:', error);
    }
}

function renderSourceConfigs(sources) {
    const container = document.getElementById('source-configs');
    container.innerHTML = sources.map(source => `
        <div class="source-item ${!source.enabled ? 'disabled' : ''}">
            <div>
                <div class="source-name">${source.name}</div>
                <div class="source-schedule">Schedule: ${source.scheduleInterval}</div>
                ${source.lastFetch ? `<div class="source-schedule">Last: ${formatTimestamp(source.lastFetch)}</div>` : ''}
            </div>
            <div class="source-stats">
                <span class="stat-badge success">${source.itemsNew} new</span>
                <span class="stat-badge warning">${source.itemsUpdated} updated</span>
                ${source.itemsFailed > 0 ? `<span class="stat-badge error">${source.itemsFailed} failed</span>` : ''}
            </div>
            <div>
                <label class="toggle-switch">
                    <input type="checkbox" ${source.enabled ? 'checked' : ''}
                           onchange="toggleSource('${source.name}', this.checked)">
                    <span class="toggle-slider"></span>
                </label>
            </div>
            <div>
                <select class="form-select" onchange="updateSchedule('${source.name}', this.value)"
                        style="width: 120px;">
                    <option value="30m" ${source.scheduleInterval === '30m' ? 'selected' : ''}>30 min</option>
                    <option value="1h" ${source.scheduleInterval === '1h' ? 'selected' : ''}>1 hour</option>
                    <option value="2h" ${source.scheduleInterval === '2h' ? 'selected' : ''}>2 hours</option>
                    <option value="6h" ${source.scheduleInterval === '6h' ? 'selected' : ''}>6 hours</option>
                    <option value="12h" ${source.scheduleInterval === '12h' ? 'selected' : ''}>12 hours</option>
                    <option value="24h" ${source.scheduleInterval === '24h' ? 'selected' : ''}>24 hours</option>
                </select>
            </div>
            <div>
                <button class="btn btn-primary btn-small" onclick="triggerFetch('${source.name}')">
                    Fetch Now
                </button>
            </div>
        </div>
    `).join('');
}

async function toggleSource(sourceName, enabled) {
    try {
        await fetch(`/api/config/sources/${sourceName}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ enabled })
        });
        showNotification(`${sourceName} ${enabled ? 'enabled' : 'disabled'}`, 'success');
        loadConfigData();
    } catch (error) {
        showNotification(`Failed to toggle ${sourceName}`, 'error');
    }
}

async function updateSchedule(sourceName, interval) {
    try {
        await fetch('/api/config/schedules', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ source: sourceName, scheduleInterval: interval })
        });
        showNotification(`Updated schedule for ${sourceName}`, 'success');
        loadConfigData();
    } catch (error) {
        showNotification(`Failed to update schedule`, 'error');
    }
}

// Fetcher Tab
async function loadFetcherData() {
    try {
        const sources = await fetch('/api/fetcher/status').then(r => r.json());
        renderFetcherStatus(sources);
    } catch (error) {
        console.error('Failed to load fetcher data:', error);
    }
}

function renderFetcherStatus(sources) {
    const container = document.getElementById('fetcher-status');
    container.innerHTML = sources.map(source => `
        <div class="source-item">
            <div>
                <div class="source-name">${source.name}</div>
                <div class="source-schedule">
                    ${source.lastFetch ? `Last fetch: ${formatTimestamp(source.lastFetch)}` : 'Never fetched'}
                </div>
            </div>
            <div>
                <span class="status-badge ${source.enabled ? 'running' : 'idle'}">
                    <span class="status-dot"></span>
                    ${source.enabled ? 'Enabled' : 'Disabled'}
                </span>
            </div>
            <div class="source-stats">
                <span class="stat-badge success">${source.itemsNew} new</span>
                <span class="stat-badge warning">${source.itemsUpdated} updated</span>
                ${source.itemsFailed > 0 ? `<span class="stat-badge error">${source.itemsFailed} failed</span>` : ''}
            </div>
            <div>
                <button class="btn btn-primary btn-small" onclick="triggerFetch('${source.name}')">
                    Trigger Fetch
                </button>
            </div>
        </div>
    `).join('');
}

async function triggerFetch(sourceName) {
    try {
        await fetch(`/api/fetcher/trigger/${sourceName}`, { method: 'POST' });
        showNotification(`Fetch triggered for ${sourceName}`, 'success');
    } catch (error) {
        showNotification(`Failed to trigger fetch for ${sourceName}`, 'error');
    }
}

// Indexer Tab
async function loadIndexerData() {
    try {
        const jobs = await fetch('/api/indexer/status').then(r => r.json());
        renderIndexerJobs(jobs);
    } catch (error) {
        console.error('Failed to load indexer data:', error);
        document.getElementById('indexer-jobs').innerHTML =
            '<p style="color: #64748b; text-align: center;">No active jobs</p>';
    }
}

function renderIndexerJobs(jobs) {
    const container = document.getElementById('indexer-jobs');
    if (!jobs || jobs.length === 0) {
        container.innerHTML = '<p style="color: #64748b; text-align: center;">No indexing jobs</p>';
        return;
    }

    container.innerHTML = jobs.map(job => {
        const progress = job.totalPages > 0 ? (job.indexedPages / job.totalPages * 100).toFixed(1) : 0;
        return `
            <div class="card">
                <div class="card-header">
                    <div>
                        <div class="card-title">${job.collectionName}</div>
                        <span class="status-badge ${job.status}">${job.status}</span>
                    </div>
                </div>
                <div class="progress-bar">
                    <div class="progress-fill" style="width: ${progress}%">${progress}%</div>
                </div>
                <div style="margin-top: 1rem; color: #94a3b8; font-size: 0.875rem;">
                    Progress: ${job.indexedPages}/${job.totalPages} pages
                    ${job.startedAt ? `| Started: ${formatTimestamp(job.startedAt)}` : ''}
                    ${job.errorMessage ? `<br><span style="color: #ef4444;">Error: ${job.errorMessage}</span>` : ''}
                </div>
            </div>
        `;
    }).join('');
}

// Storage Tab
async function loadStorageData() {
    try {
        const stats = await fetch('/api/storage/overview').then(r => r.json());
        renderStorageStats(stats);
    } catch (error) {
        console.error('Failed to load storage data:', error);
    }
}

function renderStorageStats(stats) {
    const container = document.getElementById('storage-stats');

    const totalSize = stats.postgres.sizeGB + stats.clickhouse.sizeGB + stats.qdrant.sizeGB;

    container.innerHTML = `
        <div class="stats-grid">
            <div class="stat-card">
                <div class="stat-value">${totalSize.toFixed(2)} GB</div>
                <div class="stat-label">Total Storage</div>
            </div>
            <div class="stat-card">
                <div class="stat-value">${stats.postgres.sizeGB.toFixed(2)} GB</div>
                <div class="stat-label">PostgreSQL</div>
            </div>
            <div class="stat-card">
                <div class="stat-value">${stats.clickhouse.sizeGB.toFixed(2)} GB</div>
                <div class="stat-label">ClickHouse</div>
            </div>
            <div class="stat-card">
                <div class="stat-value">${stats.qdrant.sizeGB.toFixed(2)} GB</div>
                <div class="stat-label">QDrant Vectors</div>
            </div>
        </div>

        <div class="card">
            <div class="card-title">PostgreSQL Tables</div>
            <table class="data-table">
                <thead>
                    <tr>
                        <th>Table</th>
                        <th>Rows</th>
                        <th>Size</th>
                    </tr>
                </thead>
                <tbody>
                    ${Object.entries(stats.postgres.tables).map(([name, table]) => `
                        <tr>
                            <td>${name}</td>
                            <td>${table.rows.toLocaleString()}</td>
                            <td>${table.sizeGB.toFixed(3)} GB</td>
                        </tr>
                    `).join('')}
                </tbody>
            </table>
        </div>
    `;
}

// Logs Tab
async function loadLogsData() {
    try {
        const events = await fetch('/api/logs/search?limit=100').then(r => r.json());
        renderLogs(events);
    } catch (error) {
        console.error('Failed to load logs:', error);
    }
}

function renderLogs(events) {
    const container = document.getElementById('logs-container');
    if (!events || events.length === 0) {
        container.innerHTML = '<p style="color: #64748b; text-align: center;">No logs available</p>';
        return;
    }

    container.innerHTML = events.map(event => `
        <div class="log-entry">
            <span class="log-timestamp">${formatTimestamp(event.occurredAt)}</span>
            <span class="log-level ${event.eventType.toLowerCase()}">${event.eventType}</span>
            <span class="log-service">[${event.serviceName}]</span>
            <span class="log-message">${event.message}</span>
        </div>
    `).join('');

    // Auto-scroll to bottom
    container.scrollTop = container.scrollHeight;
}

// Utility Functions
function formatTimestamp(timestamp) {
    if (!timestamp) return 'N/A';
    const date = new Date(timestamp);
    return date.toLocaleString();
}

function showNotification(message, type = 'info') {
    // Simple notification (could be enhanced with a proper notification library)
    const notification = document.createElement('div');
    notification.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        padding: 1rem 1.5rem;
        background: ${type === 'success' ? '#22c55e' : '#ef4444'};
        color: white;
        border-radius: 8px;
        box-shadow: 0 4px 6px rgba(0,0,0,0.3);
        z-index: 1000;
        animation: slideIn 0.3s ease;
    `;
    notification.textContent = message;
    document.body.appendChild(notification);

    setTimeout(() => {
        notification.style.animation = 'slideOut 0.3s ease';
        setTimeout(() => notification.remove(), 300);
    }, 3000);
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    // Load dashboard by default
    loadDashboardData();

    // Set up auto-refresh every 5 seconds
    setInterval(() => {
        const activeTab = document.querySelector('.tab-content.active');
        if (activeTab) {
            loadTabData(activeTab.id);
        }
    }, 5000);
});

// Add CSS animations
const style = document.createElement('style');
style.textContent = `
    @keyframes slideIn {
        from { transform: translateX(100%); opacity: 0; }
        to { transform: translateX(0); opacity: 1; }
    }
    @keyframes slideOut {
        from { transform: translateX(0); opacity: 1; }
        to { transform: translateX(100%); opacity: 0; }
    }
`;
document.head.appendChild(style);
