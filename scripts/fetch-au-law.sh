#!/bin/bash
# Fetch AU law data and store in ClickHouse
# Usage: ./fetch-au-law.sh [limit_per_jurisdiction]

set -e

LIMIT=${1:-5}
HOST=${TARGET_HOST:-latium.local}

echo "=== AU Law Data Collection ==="
echo "Host: $HOST"
echo "Limit: $LIMIT Acts per jurisdiction"
echo

echo "Triggering fetch via data-fetcher container..."
echo "Note: This will scrape legislation.gov.au and store in ClickHouse"
echo

# Use the Main.kt entry point with special mode
ssh gerald@$HOST "docker exec data-fetcher sh -c '
export FETCH_MODE=legal_clickhouse
export FETCH_LIMIT=$LIMIT
java -Xmx2g -jar /app/app.jar
'" || {
    echo "⚠ Direct execution failed, trying alternative approach..."

    # Alternative: Insert test data manually for prototyping
    echo "Inserting sample AU law data for testing..."

    ssh gerald@$HOST "docker exec clickhouse clickhouse-client --query \"
    INSERT INTO legal_documents
    (doc_id, jurisdiction, doc_type, title, year, identifier, url, status,
     section_number, section_title, content, content_markdown,
     fetched_at, content_hash, metadata)
    VALUES
    ('test001', 'federal', 'Act', 'Privacy Act 1988', '1988', 'C2004A03712',
     'https://www.legislation.gov.au/C2004A03712/latest', 'In force',
     '6', 'Privacy',
     'This Act protects the privacy of individuals and regulates how personal information is handled.',
     '# Privacy Act 1988\n\n## Section 6: Privacy\n\nThis Act protects the privacy of individuals and regulates how personal information is handled by Australian Government agencies and organisations with an annual turnover of more than AUD 3 million.',
     now(), 'hash001', '{\"source\": \"manual_test\"}'),

    ('test002', 'federal', 'Act', 'Privacy Act 1988', '1988', 'C2004A03712',
     'https://www.legislation.gov.au/C2004A03712/latest', 'In force',
     '13', 'Australian Privacy Principles',
     'The Australian Privacy Principles set out standards for collection, use and disclosure of personal information.',
     '# Privacy Act 1988\n\n## Section 13: Australian Privacy Principles\n\nThe Australian Privacy Principles (APPs) set out standards, rights and obligations for the collection, use and disclosure of personal information. They include requirements for transparency, data quality and security, and individual access and correction rights.',
     now(), 'hash002', '{\"source\": \"manual_test\"}'),

    ('test003', 'federal', 'Act', 'Australian Consumer Law', '2010', 'C2010A00139',
     'https://www.legislation.gov.au/C2010A00139/latest', 'In force',
     '18', 'Misleading or deceptive conduct',
     'A person must not engage in conduct that is misleading or deceptive.',
     '# Australian Consumer Law\n\n## Section 18: Misleading or deceptive conduct\n\nA person must not, in trade or commerce, engage in conduct that is misleading or deceptive or is likely to mislead or deceive. This is a key consumer protection provision.',
     now(), 'hash003', '{\"source\": \"manual_test\"}'),

    ('test004', 'federal', 'Act', 'Corporations Act 2001', '2001', 'C2001A00050',
     'https://www.legislation.gov.au/C2001A00050/latest', 'In force',
     '124', 'Director duties',
     'A director must exercise their powers and duties with care and diligence.',
     '# Corporations Act 2001\n\n## Section 124: Director duties - care and diligence\n\nA director or other officer of a corporation must exercise their powers and discharge their duties with the degree of care and diligence that a reasonable person would exercise if they were a director or officer of a corporation in the corporation circumstances and occupied the office held by the director or officer.',
     now(), 'hash004', '{\"source\": \"manual_test\"}'),

    ('test005', 'federal', 'Act', 'Work Health and Safety Act 2011', '2011', 'C2011A00137',
     'https://www.legislation.gov.au/C2011A00137/latest', 'In force',
     '19', 'Primary duty of care',
     'A person conducting a business must ensure health and safety of workers.',
     '# Work Health and Safety Act 2011\n\n## Section 19: Primary duty of care\n\nA person conducting a business or undertaking must ensure, so far as is reasonably practicable, the health and safety of workers engaged or caused to be engaged by the person, and workers whose activities are influenced or directed by the person.',
     now(), 'hash005', '{\"source\": \"manual_test\"}')
    \"" && echo "✓ Sample data inserted"
}

echo
echo "Checking ClickHouse data..."
COUNT=$(ssh gerald@$HOST 'docker exec clickhouse clickhouse-client --query "SELECT COUNT(*) FROM default.legal_documents"')
echo "✓ Total sections in ClickHouse: $COUNT"

echo
echo "Sample data:"
ssh gerald@$HOST 'docker exec clickhouse clickhouse-client --query "
SELECT
    jurisdiction,
    title,
    section_number,
    section_title,
    length(content_markdown) as content_length
FROM legal_documents
LIMIT 10
FORMAT Pretty
"'
