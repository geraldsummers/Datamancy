-- Job state management
CREATE TABLE IF NOT EXISTS indexing_jobs (
    job_id UUID PRIMARY KEY,
    collection_name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    total_pages INT DEFAULT 0,
    indexed_pages INT DEFAULT 0,
    failed_pages INT DEFAULT 0,
    current_page_id INT,
    error_message TEXT,
    metadata JSONB
);

CREATE INDEX IF NOT EXISTS idx_jobs_status ON indexing_jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_collection ON indexing_jobs(collection_name);

-- Indexed pages tracking
CREATE TABLE IF NOT EXISTS indexed_pages (
    page_id INT PRIMARY KEY,
    collection_name VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    indexed_at TIMESTAMP NOT NULL,
    embedding_version VARCHAR(20) NOT NULL,
    vector_stored BOOLEAN DEFAULT FALSE,
    fulltext_stored BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_pages_collection_hash ON indexed_pages(collection_name, content_hash);
CREATE INDEX IF NOT EXISTS idx_pages_indexed_at ON indexed_pages(indexed_at);

-- Error tracking
CREATE TABLE IF NOT EXISTS indexing_errors (
    error_id SERIAL PRIMARY KEY,
    job_id UUID REFERENCES indexing_jobs(job_id),
    page_id INT,
    page_name TEXT,
    error_message TEXT,
    stack_trace TEXT,
    occurred_at TIMESTAMP DEFAULT NOW(),
    retry_count INT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_errors_job ON indexing_errors(job_id);
CREATE INDEX IF NOT EXISTS idx_errors_page ON indexing_errors(page_id);
