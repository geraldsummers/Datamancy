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
project(":agent-tool-server").projectDir = file("stack.kotlin/agent-tool-server")
//project(":speech-gateway").projectDir = file("stack.kotlin/speech-gateway")
//project(":vllm-router").projectDir = file("stack.kotlin/vllm-router")
//project(":stack-discovery").projectDir = file("stack.kotlin/stack-discovery")
//project(":playwright-controller").projectDir = file("stack.kotlin/playwright-controller")
//project(":ldap-sync-service").projectDir = file("stack.kotlin/ldap-sync-service")
//project(":vm-provisioner").projectDir = file("stack.kotlin/vm-provisioner")

// New services
//include(":task-scheduler")
include(":pipeline-common")
include(":knowledge-ingestion")
include(":embedding-worker")
include(":market-data-ingestion")
include(":content-publisher")
include(":search-service")
//project(":task-scheduler").projectDir = file("stack.kotlin/task-scheduler")
project(":pipeline-common").projectDir = file("stack.kotlin/pipeline-common")
project(":knowledge-ingestion").projectDir = file("stack.kotlin/knowledge-ingestion")
project(":embedding-worker").projectDir = file("stack.kotlin/embedding-worker")
project(":market-data-ingestion").projectDir = file("stack.kotlin/market-data-ingestion")
project(":content-publisher").projectDir = file("stack.kotlin/content-publisher")
project(":search-service").projectDir = file("stack.kotlin/search-service")

include(":test-runner")
project(":test-runner").projectDir = file("tests.kotlin/test-runner")

// Trading system
include(":trading-sdk")
include(":tx-gateway")
project(":trading-sdk").projectDir = file("stack.kotlin/trading-sdk")
project(":tx-gateway").projectDir = file("stack.kotlin/tx-gateway")

// Deprecated: stack-tests (replaced by test-runner)
// include(":stack-tests")
// project(":stack-tests").projectDir = file("src/stack-tests")
