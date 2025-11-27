// Simple placeholder init for Dockge. This avoids compose errors if the file is missing.
// It can be extended to call Dockge's HTTP API to bootstrap an admin if desired.

const host = process.env.DOCKGE_HOST || 'dockge';
const port = process.env.DOCKGE_PORT || '5001';
const adminUser = process.env.DOCKGE_ADMIN_USER || 'admin';
const adminPass = process.env.DOCKGE_ADMIN_PASSWORD || 'admin';

console.log(`[init-dockge] Host: ${host}:${port}`);
console.log(`[init-dockge] Admin user placeholder: ${adminUser}`);
console.log(`[init-dockge] No-op initialization complete.`);
process.exit(0);
