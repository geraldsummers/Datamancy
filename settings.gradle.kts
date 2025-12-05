rootProject.name = "Datamancy"

// Include all Kotlin subprojects (standardized module IDs)
include(":agent-tool-server")
include(":probe-orchestrator")
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
project(":probe-orchestrator").projectDir = file("src/probe-orchestrator")
project(":speech-gateway").projectDir = file("src/speech-gateway")
project(":vllm-router").projectDir = file("src/vllm-router")
project(":stack-discovery").projectDir = file("src/stack-discovery")
project(":playwright-controller").projectDir = file("src/playwright-controller")
project(":ldap-sync-service").projectDir = file("src/ldap-sync-service")
project(":vm-provisioner").projectDir = file("src/vm-provisioner")

// New services
include(":task-scheduler")
include(":rag-gateway")
project(":task-scheduler").projectDir = file("src/task-scheduler")
project(":rag-gateway").projectDir = file("src/rag-gateway")
