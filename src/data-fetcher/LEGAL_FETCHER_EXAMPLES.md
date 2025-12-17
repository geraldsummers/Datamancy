# Australian Legislation Fetcher - Example Data

## Sample Fetched Data

### Example 1: Federal Act (Full Metadata)
```json
{
  "title": "Privacy Act 1988",
  "jurisdiction": "federal",
  "year": "1988",
  "type": "Act",
  "identifier": "C2014C00076",
  "url": "https://www.legislation.gov.au/Details/C2014C00076",
  "status": "In force",
  "registrationDate": "12/03/2014",
  "metadata": {}
}
```

### Example 2: Federal Act (Recent)
```json
{
  "title": "A New Tax System (Goods and Services Tax) Act 1999",
  "jurisdiction": "federal",
  "year": "1999",
  "type": "Act",
  "identifier": "C2004A00467",
  "url": "https://www.legislation.gov.au/Details/C2004A00467",
  "status": "In force",
  "registrationDate": "01/07/2000",
  "metadata": {}
}
```

### Example 3: NSW State Act
```json
{
  "title": "Environmental Planning and Assessment Act 1979 No 203",
  "jurisdiction": "NSW",
  "year": "1979",
  "type": "Act",
  "identifier": null,
  "url": "https://legislation.nsw.gov.au/view/html/inforce/current/act-1979-203",
  "status": "In force",
  "registrationDate": null,
  "metadata": {}
}
```

### Example 4: Victorian Act
```json
{
  "title": "Charter of Human Rights and Responsibilities Act 2006",
  "jurisdiction": "VIC",
  "year": "2006",
  "type": "Act",
  "identifier": null,
  "url": "https://www.legislation.vic.gov.au/in-force/acts/charter-human-rights-and-responsibilities-act-2006",
  "status": "In force",
  "registrationDate": null,
  "metadata": {}
}
```

## Complete Output File Example

### Master File: `/app/data/legal/legislation_1734451200.json`
```json
{
  "fetchedAt": "2025-12-17T10:00:00.000Z",
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
      "title": "Aboriginal and Torres Strait Islander Act 2005",
      "jurisdiction": "federal",
      "year": "2005",
      "type": "Act",
      "identifier": "C2005A00150",
      "url": "https://www.legislation.gov.au/Details/C2005A00150",
      "status": "In force",
      "registrationDate": "21/12/2005",
      "metadata": {}
    },
    {
      "title": "Administrative Appeals Tribunal Act 1975",
      "jurisdiction": "federal",
      "year": "1975",
      "type": "Act",
      "identifier": "C2015C00435",
      "url": "https://www.legislation.gov.au/Details/C2015C00435",
      "status": "In force",
      "registrationDate": "11/09/2015",
      "metadata": {}
    },
    {
      "title": "Age Discrimination Act 2004",
      "jurisdiction": "federal",
      "year": "2004",
      "type": "Act",
      "identifier": "C2004A01302",
      "url": "https://www.legislation.gov.au/Details/C2004A01302",
      "status": "In force",
      "registrationDate": "23/06/2004",
      "metadata": {}
    },
    {
      "title": "Anti-Money Laundering and Counter-Terrorism Financing Act 2006",
      "jurisdiction": "federal",
      "year": "2006",
      "type": "Act",
      "identifier": "C2006A00169",
      "url": "https://www.legislation.gov.au/Details/C2006A00169",
      "status": "In force",
      "registrationDate": "12/12/2006",
      "metadata": {}
    },
    {
      "title": "Crimes Act 1900 No 40",
      "jurisdiction": "NSW",
      "year": "1900",
      "type": "Act",
      "identifier": null,
      "url": "https://legislation.nsw.gov.au/view/html/inforce/current/act-1900-040",
      "status": "In force",
      "registrationDate": null,
      "metadata": {}
    },
    {
      "title": "Education Act 1990 No 8",
      "jurisdiction": "NSW",
      "year": "1990",
      "type": "Act",
      "identifier": null,
      "url": "https://legislation.nsw.gov.au/view/html/inforce/current/act-1990-008",
      "status": "In force",
      "registrationDate": null,
      "metadata": {}
    }
  ]
}
```

### Per-Jurisdiction File: `/app/data/legal/legislation_federal_1734451200.json`
```json
{
  "jurisdiction": "federal",
  "fetchedAt": "2025-12-17T10:00:00.000Z",
  "itemCount": 200,
  "items": [
    {
      "title": "Commonwealth Electoral Act 1918",
      "jurisdiction": "federal",
      "year": "1918",
      "type": "Act",
      "identifier": "C2016C00929",
      "url": "https://www.legislation.gov.au/Details/C2016C00929",
      "status": "In force",
      "registrationDate": "03/11/2016",
      "metadata": {}
    },
    {
      "title": "Copyright Act 1968",
      "jurisdiction": "federal",
      "year": "1968",
      "type": "Act",
      "identifier": "C2017C00180",
      "url": "https://www.legislation.gov.au/Details/C2017C00180",
      "status": "In force",
      "registrationDate": "01/07/2017",
      "metadata": {}
    }
  ]
}
```

## PostgreSQL Metadata Record

### Success Case
```sql
SELECT * FROM fetch_history WHERE source = 'legal' ORDER BY fetched_at DESC LIMIT 1;
```

Result:
```
id: 123
source: legal
category: australian_legislation
item_count: 850
fetched_at: 2025-12-17 10:00:00
metadata: {
  "jurisdictions": 9,
  "errors": 0,
  "errorDetails": ""
}
created_at: 2025-12-17 10:05:30
```

### Partial Failure Case
```
id: 124
source: legal
category: australian_legislation
item_count: 650
fetched_at: 2025-12-17 11:00:00
metadata: {
  "jurisdictions": 9,
  "errors": 2,
  "errorDetails": "WA: HTTP 503 for https://www.legislation.wa.gov.au/; NT: Empty response from https://legislation.nt.gov.au/legislation/"
}
created_at: 2025-12-17 11:05:15
```

## Log Output Examples

### Successful Fetch
```
2025-12-17 10:00:00.123 [main] INFO  LegalDocsFetcher - Starting Australian legislation fetch from 9 jurisdictions...
2025-12-17 10:00:00.145 [main] INFO  LegalDocsFetcher - Fetching federal legislation from https://www.legislation.gov.au/
2025-12-17 10:00:05.678 [main] INFO  LegalDocsFetcher - Parsed 200 federal legislation items
2025-12-17 10:00:05.680 [main] INFO  LegalDocsFetcher - Fetched 200 federal legislation items
2025-12-17 10:00:06.690 [main] INFO  LegalDocsFetcher - Fetching NSW legislation from https://legislation.nsw.gov.au/
2025-12-17 10:00:09.234 [main] INFO  LegalDocsFetcher - Parsed 100 NSW legislation items
2025-12-17 10:00:09.236 [main] INFO  LegalDocsFetcher - Fetched 100 NSW legislation items
2025-12-17 10:00:10.246 [main] INFO  LegalDocsFetcher - Fetching VIC legislation from https://www.legislation.vic.gov.au/
2025-12-17 10:00:12.567 [main] INFO  LegalDocsFetcher - Parsed 95 VIC legislation items
2025-12-17 10:00:12.569 [main] INFO  LegalDocsFetcher - Fetched 95 VIC legislation items
...
2025-12-17 10:04:30.123 [main] INFO  LegalDocsFetcher - Stored 850 legislation items to legislation_1734451200.json
2025-12-17 10:04:30.456 [main] INFO  PostgresStore - Stored fetch metadata: legal/australian_legislation
```

### Fetch with Errors
```
2025-12-17 11:00:00.123 [main] INFO  LegalDocsFetcher - Starting Australian legislation fetch from 9 jurisdictions...
2025-12-17 11:00:00.145 [main] INFO  LegalDocsFetcher - Fetching federal legislation from https://www.legislation.gov.au/
2025-12-17 11:00:05.678 [main] INFO  LegalDocsFetcher - Parsed 200 federal legislation items
2025-12-17 11:00:05.680 [main] INFO  LegalDocsFetcher - Fetched 200 federal legislation items
...
2025-12-17 11:02:45.123 [main] INFO  LegalDocsFetcher - Fetching WA legislation from https://www.legislation.wa.gov.au/
2025-12-17 11:02:50.456 [main] ERROR LegalDocsFetcher - Failed to fetch WA legislation
java.lang.Exception: HTTP 503 for https://www.legislation.wa.gov.au/legislation/statutes.nsf/main_mrtitle_
    at org.datamancy.datafetcher.fetchers.LegalDocsFetcher.fetchPage(LegalDocsFetcher.kt:260)
    ...
2025-12-17 11:02:51.467 [main] INFO  LegalDocsFetcher - Fetching SA legislation from https://www.legislation.sa.gov.au/
2025-12-17 11:02:54.789 [main] INFO  LegalDocsFetcher - Parsed 80 SA legislation items
2025-12-17 11:02:54.791 [main] INFO  LegalDocsFetcher - Fetched 80 SA legislation items
...
```

## FetchResult Examples

### Success Result
```kotlin
FetchResult.Success(
    message = "Fetched 850 legislation items from 9 jurisdictions",
    itemCount = 850
)
```

### Error Result
```kotlin
FetchResult.Error(
    message = "Fetched 650 items with 2 errors: WA: HTTP 503 for https://www.legislation.wa.gov.au/; NT: Empty response from https://legislation.nt.gov.au/legislation/"
)
```

## Data Statistics by Jurisdiction

Based on typical fetch results:

| Jurisdiction | Typical Item Count | URL Pattern | Notes |
|--------------|-------------------|-------------|-------|
| Federal | 150-200 | `/Details/C{year}{type}{number}` | Most structured |
| NSW | 80-100 | `/view/html/inforce/current/act-{year}-{number}` | Well documented |
| VIC | 70-95 | `/in-force/acts/{slug}` | Good structure |
| QLD | 60-90 | `/browse/inforce/` | Variable format |
| WA | 50-85 | `/legislation/statutes.nsf/` | Older system |
| SA | 50-80 | `/browse/` | Mixed formats |
| TAS | 40-75 | `/tocview/` | Complex navigation |
| ACT | 40-70 | `/a/{slug}` | Concise URLs |
| NT | 30-55 | `/legislation/` | Smaller catalogue |

## Query Examples

### Find all Acts from a specific year
```kotlin
val acts2020 = items.filter { it.year == "2020" }
```

### Group by jurisdiction
```kotlin
val byJurisdiction = items.groupBy { it.jurisdiction }
println("Federal: ${byJurisdiction["federal"]?.size} Acts")
println("NSW: ${byJurisdiction["NSW"]?.size} Acts")
```

### Find acts with specific keywords
```kotlin
val privacyActs = items.filter {
    it.title.contains("Privacy", ignoreCase = true)
}
```

### Get all in-force legislation
```kotlin
val inForce = items.filter {
    it.status?.equals("In force", ignoreCase = true) ?: false
}
```

## API Usage Example

### Kotlin
```kotlin
import org.datamancy.datafetcher.fetchers.LegalDocsFetcher
import org.datamancy.datafetcher.config.LegalConfig
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val config = LegalConfig(
        ausLegislationUrl = "https://www.legislation.gov.au/",
        stateUrls = mapOf(
            "nsw" to "https://legislation.nsw.gov.au/",
            "vic" to "https://www.legislation.vic.gov.au/",
            "qld" to "https://www.legislation.qld.gov.au/",
            "wa" to "https://www.legislation.wa.gov.au/",
            "sa" to "https://www.legislation.sa.gov.au/",
            "tas" to "https://www.legislation.tas.gov.au/",
            "act" to "https://legislation.act.gov.au/",
            "nt" to "https://legislation.nt.gov.au/"
        )
    )

    val fetcher = LegalDocsFetcher(config)

    // Run dry-run first
    val dryRunResult = fetcher.dryRun()
    println("Dry-run: ${dryRunResult.passed}/${dryRunResult.checks.size} checks passed")

    // Run actual fetch
    val result = fetcher.fetch()
    when (result) {
        is FetchResult.Success ->
            println("Success: ${result.message} (${result.itemCount} items)")
        is FetchResult.Error ->
            println("Error: ${result.message}")
    }
}
```

## Scheduled Fetch Configuration

### schedules.yaml
```yaml
schedules:
  legal_docs:
    cron: "0 2 * * 0"  # Every Sunday at 2 AM
    enabled: true
```

### Fetch frequency recommendations
- **Development**: Manual/on-demand
- **Staging**: Weekly (legislation changes infrequently)
- **Production**: Weekly or bi-weekly
- **Full catalogue**: Monthly (when pagination implemented)

## Data Volume Estimates

### Per Fetch (Limited)
- Total items: 650-850
- JSON file size: 500KB - 1MB
- PostgreSQL record: 1 row + metadata
- Processing time: 1-5 minutes

### Full Catalogue (Future)
- Federal: ~4,700 Acts
- All jurisdictions: ~15,000-20,000 Acts
- JSON file size: 15-30 MB
- Processing time: 20-45 minutes

## Integration Examples

### Using the data in downstream applications

#### Search legislation by keyword
```kotlin
fun searchLegislation(keyword: String, jurisdiction: String? = null): List<LegislationItem> {
    val json = File("/app/data/legal/legislation_latest.json").readText()
    val data = gson.fromJson(json, Map::class.java)
    val items = data["items"] as List<Map<String, Any>>

    return items
        .map { /* convert to LegislationItem */ }
        .filter { it.title.contains(keyword, ignoreCase = true) }
        .filter { jurisdiction == null || it.jurisdiction == jurisdiction }
}
```

#### Build legislation index for search engine
```kotlin
fun indexLegislation() {
    val items = loadLegislationItems()
    items.forEach { item ->
        searchEngine.index(
            id = item.identifier ?: item.url,
            title = item.title,
            year = item.year,
            jurisdiction = item.jurisdiction,
            url = item.url,
            type = "legislation"
        )
    }
}
```

#### Generate reports
```kotlin
fun generateLegislationReport(): String {
    val items = loadLegislationItems()
    val byJurisdiction = items.groupBy { it.jurisdiction }

    return buildString {
        appendLine("Australian Legislation Report")
        appendLine("Generated: ${Clock.System.now()}")
        appendLine()
        appendLine("Total Acts: ${items.size}")
        appendLine()
        appendLine("By Jurisdiction:")
        byJurisdiction.forEach { (jurisdiction, acts) ->
            appendLine("  $jurisdiction: ${acts.size} Acts")
        }
    }
}
```
