# Australian Legislation Fetcher - Implementation Documentation

## Overview

The `LegalDocsFetcher` class provides comprehensive scraping of Australian legislation from 9 government portals:
- **Federal**: legislation.gov.au
- **States/Territories**: NSW, VIC, QLD, WA, SA, TAS, ACT, NT

## Implementation Details

### Architecture

The fetcher uses:
- **OkHttp**: HTTP client for making requests
- **Jsoup**: HTML parsing and CSS selector-based extraction
- **Rate Limiting**: 1-second delay between requests to respect server load
- **Error Handling**: Graceful failure per-jurisdiction with error reporting

### Data Flow

1. **Fetch Phase**:
   - Connects to federal portal first
   - Iterates through each state/territory portal
   - Applies rate limiting between requests
   - Collects errors without stopping execution

2. **Parse Phase**:
   - Uses multiple CSS selectors for resilience
   - Extracts metadata: title, year, jurisdiction, status, URL
   - Handles different HTML structures per portal
   - Limits items per jurisdiction to avoid overwhelming

3. **Storage Phase**:
   - Stores master JSON file with all items
   - Stores per-jurisdiction JSON files
   - Records metadata in PostgreSQL
   - Logs statistics and errors

### Data Structure

Each legislation item contains:
```kotlin
data class LegislationItem(
    val title: String,              // e.g., "Privacy Act 1988"
    val jurisdiction: String,       // "federal", "NSW", "VIC", etc.
    val year: String?,              // e.g., "1988"
    val type: String,               // "Act", "Regulation", etc.
    val identifier: String?,        // e.g., "C2004A00467" (federal)
    val url: String,                // Full URL to legislation
    val status: String?,            // "In force", "Repealed", etc.
    val registrationDate: String?,  // Date registered
    val metadata: Map<String, String> = emptyMap()
)
```

### Example Output

#### Master JSON File: `/app/data/legal/legislation_1734451200.json`
```json
{
  "fetchedAt": "2025-12-17T10:00:00Z",
  "itemCount": 850,
  "jurisdictions": {
    "federal": 200,
    "NSW": 100,
    "VIC": 95,
    "QLD": 90,
    "WA": 85,
    "SA": 80,
    "TAS": 75,
    "ACT": 70,
    "NT": 55
  },
  "items": [
    {
      "title": "A New Tax System (Goods and Services Tax) Act 1999",
      "jurisdiction": "federal",
      "year": "1999",
      "type": "Act",
      "identifier": "C2004A00467",
      "url": "https://www.legislation.gov.au/Details/C2004A00467",
      "status": "In force",
      "registrationDate": "01/07/2000"
    },
    {
      "title": "Privacy Act 1988",
      "jurisdiction": "federal",
      "year": "1988",
      "type": "Act",
      "identifier": "C2014C00076",
      "url": "https://www.legislation.gov.au/Details/C2014C00076",
      "status": "In force"
    }
  ]
}
```

#### Per-Jurisdiction File: `/app/data/legal/legislation_federal_1734451200.json`
```json
{
  "jurisdiction": "federal",
  "fetchedAt": "2025-12-17T10:00:00Z",
  "itemCount": 200,
  "items": [...]
}
```

#### PostgreSQL Metadata Record
```sql
INSERT INTO fetch_history VALUES (
  source: "legal",
  category: "australian_legislation",
  item_count: 850,
  fetched_at: "2025-12-17 10:00:00",
  metadata: {
    "jurisdictions": 9,
    "errors": 0,
    "errorDetails": ""
  }
)
```

## Scraping Strategy by Portal

### Federal (legislation.gov.au)
- **Target URL**: `/search/status(InForce)/collection(Act)`
- **Method**: Parses HTML search results with structured listings
- **Selectors**: `.result-item`, `.search-result`, `article`
- **Metadata**: Full (identifier, status, registration date)
- **Limit**: 200 items per fetch
- **Notes**: Most structured portal; supports CSV export (not yet implemented)

### NSW (legislation.nsw.gov.au)
- **Target URL**: `/browse/inforce/`
- **Method**: Parses alphabetical browse listings
- **Selectors**: Links with `/act-`, `/view/html`
- **Limit**: 100 items per fetch
- **Notes**: Well-structured HTML, clear navigation

### VIC (legislation.vic.gov.au)
- **Target URL**: `/in-force/acts`
- **Method**: Parses in-force Acts listing
- **Selectors**: Links with `/act-`, `/in-force/`
- **Limit**: 100 items per fetch

### QLD (legislation.qld.gov.au)
- **Target URL**: `/browse/inforce`
- **Method**: Parses browse interface
- **Selectors**: Links with `/act-`, `/inforce/`
- **Limit**: 100 items per fetch

### WA, SA, TAS, ACT, NT
- **Method**: Generic link extraction from homepage and browse pages
- **Selectors**: Multiple patterns to catch different HTML structures
- **Limit**: 100 items per state
- **Notes**: Less standardized structures; fallback parsing strategies

## Rate Limiting and Best Practices

1. **Request Delay**: 1 second between requests
2. **User-Agent**: Identifies as "DatamancyBot/1.0" with contact URL
3. **Accept Headers**: Standard browser-like headers
4. **Connection Pooling**: Reuses HTTP client
5. **Timeouts**: 30-second connect/read timeouts
6. **Error Handling**: Per-jurisdiction try/catch; continues on failure

## Limitations and Future Improvements

### Current Limitations

1. **Limited Items**: Fetches first 100-200 items per jurisdiction (not full catalogue)
2. **No Pagination**: Doesn't follow pagination links
3. **No Full-Text**: Only fetches listings, not full legislation text/PDF
4. **No Amendments**: Doesn't track amendment history
5. **HTML Dependence**: Breaks if portal HTML structure changes
6. **No CSV Parsing**: Federal portal offers CSV export but not utilized

### Recommended Improvements

#### Phase 2: Full Catalogue Scraping
```kotlin
// Add pagination support
private fun fetchAllPages(baseUrl: String): List<Document> {
    val pages = mutableListOf<Document>()
    var page = 1
    while (page <= maxPages) {
        val url = "$baseUrl&page=$page"
        pages.add(fetchPage(url))
        page++
        delay(requestDelay)
    }
    return pages
}
```

#### Phase 3: Full-Text Download
```kotlin
// Download PDF/HTML content
private suspend fun downloadLegislationText(item: LegislationItem) {
    val doc = fetchPage(item.url)
    val pdfLink = doc.select("a[href$='.pdf']").firstOrNull()?.attr("abs:href")

    if (pdfLink != null) {
        val pdfBytes = downloadBinary(pdfLink)
        fsStore.storeRawData("legal/pdfs", "${item.identifier}.pdf", pdfBytes)
    }
}
```

#### Phase 4: CSV Parsing for Federal Portal
```kotlin
// Use CSV export instead of HTML scraping
private fun fetchFederalViaCsv(): List<LegislationItem> {
    val csvUrl = "${config.ausLegislationUrl}search/download?status=InForce&collection=Act"
    val csvContent = downloadText(csvUrl)
    return parseCsv(csvContent)
}
```

#### Phase 5: Amendment Tracking
```kotlin
// Track legislation history
data class LegislationHistory(
    val originalAct: LegislationItem,
    val amendments: List<Amendment>,
    val consolidations: List<String>
)
```

#### Phase 6: API Integration
- Check if portals offer REST APIs or data feeds
- Use structured data instead of HTML scraping
- Monitor RSS feeds for updates

## Usage

### Manual Execution
```kotlin
val config = LegalConfig(
    ausLegislationUrl = "https://www.legislation.gov.au/",
    stateUrls = mapOf(
        "nsw" to "https://legislation.nsw.gov.au/",
        // ... other states
    )
)

val fetcher = LegalDocsFetcher(config)
val result = fetcher.fetch()
```

### Scheduled Execution
Configure in `schedules.yaml`:
```yaml
schedules:
  legal_docs:
    cron: "0 2 * * 0"  # Weekly on Sunday 2am
    enabled: true
```

### Dry-Run Test
```bash
# Test connectivity to all 9 portals
./data-fetcher dry-run legal
```

## Monitoring and Debugging

### Log Messages
- `INFO`: Normal operation, statistics
- `WARN`: Fallback parsing, no items found
- `ERROR`: HTTP failures, parsing exceptions

### Success Indicators
- `FetchResult.Success` with item count > 0
- No errors in metadata
- JSON files created in `/app/data/legal/`
- PostgreSQL record inserted

### Failure Indicators
- `FetchResult.Error` with error list
- Empty items list for jurisdiction
- HTTP errors in logs
- Missing jurisdiction in output

## Testing Checklist

- [ ] Dry-run passes for all 9 portals (10/10 checks)
- [ ] Fetches at least some items from federal portal
- [ ] Fetches at least some items from each state
- [ ] Creates master JSON file
- [ ] Creates per-jurisdiction JSON files
- [ ] Inserts PostgreSQL metadata
- [ ] Handles portal downtime gracefully
- [ ] Respects rate limits
- [ ] Logs progress clearly
- [ ] Returns accurate statistics

## Maintenance

### Regular Tasks
- Monitor fetch success rate
- Review error logs for pattern changes
- Update selectors if portal HTML changes
- Verify data quality periodically

### When Portals Change
1. Check portal HTML structure
2. Update CSS selectors in `fetchFederalLegislation()` or `fetchStateLegislation()`
3. Test with dry-run
4. Review sample output
5. Deploy update

## Contact and Resources

- Federal Portal: https://www.legislation.gov.au/
- Federal Support: https://www.legislation.gov.au/help
- NSW Support: https://legislation.nsw.gov.au/information
- robots.txt: Check each portal for crawling policies
