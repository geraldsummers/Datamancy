# MongoDB — Spoke

**Status:** 🟢 Functional
**Phase:** 4
**Hostname:** `mongodb:27017` (internal only)
**Dependencies:** None

## Purpose

MongoDB provides document-oriented NoSQL database storage for the Datamancy stack, optimized for flexible schemas, nested documents, and rapid iteration.

## Configuration

**Image:** `mongo:7.0`
**Volumes:**
- `mongodb_data:/data/db` (database files)
- `./configs/mongodb:/docker-entrypoint-initdb.d:ro` (initialization scripts)
**Networks:** backend
**Ports:** 27017 (MongoDB protocol) - internal only

### Key Settings

Database:
- Root user: `root`
- Root password: `${MONGODB_ROOT_PASSWORD}` (default: root_password_change_me)
- Database: `datamancy`

Collections:
- `logs`: Application logs with schema validation
- `documents`: Generic document storage with validation

### Fingerprint Inputs

- Image digest: `mongo:7.0`
- Environment variables (passwords, database name)
- Initialization JS: configs/mongodb/init.js
- Compose stanza: mongodb service block

## Access

- **Internal URL:** `mongodb://mongodb:27017`
- **Auth:** Username/password authentication
- **Query:** `mongosh mongodb://root:password@mongodb:27017/datamancy`

## Schema

### logs collection
```javascript
{
    timestamp: Date,
    level: 'debug' | 'info' | 'warn' | 'error' | 'fatal',
    message: String,
    source: String,
    labels: Object
}
```

Indexes:
- `{ timestamp: -1 }`
- `{ level: 1, timestamp: -1 }`
- `{ source: 1, timestamp: -1 }`

### documents collection
```javascript
{
    created_at: Date,
    updated_at: Date,
    doc_type: String,
    content: Object
}
```

Indexes:
- `{ created_at: -1 }`
- `{ doc_type: 1, created_at: -1 }`

## Runbook

### Start/Stop

```bash
docker compose --profile datastores up -d mongodb
docker compose stop mongodb
```

### Logs

```bash
docker compose logs -f mongodb
```

### Connect

```bash
# From host
docker exec -it mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin

# Switch to datamancy database
use datamancy
```

### Query Data

```bash
# Count logs
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.logs.countDocuments();"

# Find recent logs
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.logs.find().sort({timestamp: -1}).limit(10);"

# Find error logs
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.logs.find({level: 'error'});"

# Aggregate by level
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.logs.aggregate([{$group: {_id: '$level', count: {$sum: 1}}}]);"
```

### Insert Data

```bash
# Insert log entry
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.logs.insertOne({timestamp: new Date(), level: 'info', message: 'Test log', source: 'manual'});"

# Insert document
docker exec mongodb mongosh -u root -p root_password_change_me --authenticationDatabase admin \
  --eval "use datamancy; db.documents.insertOne({created_at: new Date(), doc_type: 'test', content: {key: 'value'}});"
```

### Backup

```bash
# Dump database
docker exec mongodb mongodump --username root --password root_password_change_me \
  --authenticationDatabase admin --db datamancy --out /tmp/backup

# Restore database
docker exec mongodb mongorestore --username root --password root_password_change_me \
  --authenticationDatabase admin --db datamancy /tmp/backup/datamancy
```

### Common Issues

**Symptom:** "Connection refused"
**Cause:** MongoDB not started or wrong hostname
**Fix:** Verify service is running: `docker ps | grep mongodb`

**Symptom:** "Authentication failed"
**Cause:** Wrong credentials or auth database
**Fix:** Ensure using `--authenticationDatabase admin`

**Symptom:** "Collection not found"
**Cause:** Database/collection not initialized
**Fix:** Restart container to run init scripts or create collection manually

**Symptom:** Slow queries
**Cause:** Missing indexes
**Fix:** Create indexes: `db.collection.createIndex({field: 1});`

**Symptom:** Document validation errors
**Cause:** Data doesn't match schema
**Fix:** Check validation rules: `db.getCollectionInfos({name: 'collection_name'})`

## Related

- Upstream docs: https://www.mongodb.com/docs/

---

**Last updated:** 2025-10-26
**Last change fingerprint:** TBD

**Update 2025-10-26:** Added automated test coverage. Service verified functional via integration tests.

