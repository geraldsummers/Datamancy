rootProject.name = "Datamancy"

// Include all Kotlin subprojects (standardized module IDs)
include(":agent-tool-server")
//include(":speech-gateway")
//include(":vllm-router")
//include(":stack-discovery")
//include(":playwright-controller")
//include(":ldap-sync-service")
//include(":vm-provisioner")
// NOTE: config-generator has been replaced by process-config-templates.main.kts
// Removed: include(":config-generator")

// Map subproject directories (standardized directories)
project(":agent-tool-server").projectDir = file("kotlin.src/agent-tool-server")
//project(":speech-gateway").projectDir = file("kotlin.src/speech-gateway")
//project(":vllm-router").projectDir = file("kotlin.src/vllm-router")
//project(":stack-discovery").projectDir = file("kotlin.src/stack-discovery")
//project(":playwright-controller").projectDir = file("kotlin.src/playwright-controller")
//project(":ldap-sync-service").projectDir = file("kotlin.src/ldap-sync-service")
//project(":vm-provisioner").projectDir = file("kotlin.src/vm-provisioner")

// New services
//include(":task-scheduler")
include(":pipeline-common")
include(":knowledge-ingestion")
include(":embedding-worker")
include(":market-data-ingestion")
include(":content-publisher")
include(":search-service")
//project(":task-scheduler").projectDir = file("kotlin.src/task-scheduler")
project(":pipeline-common").projectDir = file("kotlin.src/pipeline-common")
project(":knowledge-ingestion").projectDir = file("kotlin.src/knowledge-ingestion")
project(":embedding-worker").projectDir = file("kotlin.src/embedding-worker")
project(":market-data-ingestion").projectDir = file("kotlin.src/market-data-ingestion")
project(":content-publisher").projectDir = file("kotlin.src/content-publisher")
project(":search-service").projectDir = file("kotlin.src/search-service")

include(":test-runner")
project(":test-runner").projectDir = file("kotlin.src/test-runner")

// Trading system
include(":trading-sdk")
include(":tx-gateway")
project(":trading-sdk").projectDir = file("kotlin.src/trading-sdk")
project(":tx-gateway").projectDir = file("kotlin.src/tx-gateway")

// Deprecated: stack-tests (replaced by test-runner)
// include(":stack-tests")
// project(":stack-tests").projectDir = file("src/stack-tests")
