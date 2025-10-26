// MongoDB Initialization Script
// Provenance: Phase 4 - Datastores
// Purpose: Create initial collections and indexes for Datamancy stack

// Switch to datamancy database
db = db.getSiblingDB('datamancy');

// Create collections with schema validation
db.createCollection('logs', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['timestamp', 'level', 'message'],
            properties: {
                timestamp: { bsonType: 'date' },
                level: { enum: ['debug', 'info', 'warn', 'error', 'fatal'] },
                message: { bsonType: 'string' },
                source: { bsonType: 'string' },
                labels: { bsonType: 'object' }
            }
        }
    }
});

db.createCollection('documents', {
    validator: {
        $jsonSchema: {
            bsonType: 'object',
            required: ['created_at', 'doc_type'],
            properties: {
                created_at: { bsonType: 'date' },
                updated_at: { bsonType: 'date' },
                doc_type: { bsonType: 'string' },
                content: { bsonType: 'object' }
            }
        }
    }
});

// Create indexes
db.logs.createIndex({ timestamp: -1 });
db.logs.createIndex({ level: 1, timestamp: -1 });
db.logs.createIndex({ source: 1, timestamp: -1 });

db.documents.createIndex({ created_at: -1 });
db.documents.createIndex({ doc_type: 1, created_at: -1 });

// Insert test data
db.logs.insertMany([
    {
        timestamp: new Date(),
        level: 'info',
        message: 'MongoDB initialized successfully',
        source: 'datamancy-init',
        labels: { environment: 'development', version: '7.0' }
    },
    {
        timestamp: new Date(),
        level: 'info',
        message: 'Collections created',
        source: 'datamancy-init',
        labels: { collections: ['logs', 'documents'] }
    }
]);

db.documents.insertMany([
    {
        created_at: new Date(),
        doc_type: 'config',
        content: {
            name: 'datamancy-stack',
            phase: 4,
            description: 'Datastores & Pipelines'
        }
    }
]);

print('MongoDB initialization complete');
