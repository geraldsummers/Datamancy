rootProject.name = "Datamancy"

// Include all Kotlin subprojects (standardized module IDs)
include(":agent-tool-server")
include(":speech-gateway")
include(":vllm-router")
include(":stack-discovery")
include(":playwright-controller")
include(":ldap-sync-service")
include(":vm-provisioner")
// NOTE: config-generator has been replaced by process-config-templates.main.kts
// Removed: include(":config-generator")

// Map subproject directories (standardized directories)
project(":agent-tool-server").projectDir = file("src/agent-tool-server")
project(":speech-gateway").projectDir = file("src/speech-gateway")
project(":vllm-router").projectDir = file("src/vllm-router")
project(":stack-discovery").projectDir = file("src/stack-discovery")
project(":playwright-controller").projectDir = file("src/playwright-controller")
project(":ldap-sync-service").projectDir = file("src/ldap-sync-service")
project(":vm-provisioner").projectDir = file("src/vm-provisioner")

// New services
include(":task-scheduler")
include(":data-fetcher")
include(":unified-indexer")
include(":search-service")
project(":task-scheduler").projectDir = file("src/task-scheduler")
project(":data-fetcher").projectDir = file("src/data-fetcher")
project(":unified-indexer").projectDir = file("src/unified-indexer")
project(":search-service").projectDir = file("src/search-service")

// Test framework module (Playwright-based stack testing)
include(":test-framework")
project(":test-framework").projectDir = file("src/test-framework")
