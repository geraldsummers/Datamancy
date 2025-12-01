rootProject.name = "Datamancy"

// Include all Kotlin subprojects (standardized module IDs)
include(":agent-tool-server")
include(":probe-orchestrator")
include(":speech-gateway")
include(":vllm-router")
include(":stack-discovery")
include(":playwright-controller")
include(":config-generator")

// Map subproject directories (standardized directories)
project(":agent-tool-server").projectDir = file("src/agent-tool-server")
project(":probe-orchestrator").projectDir = file("src/probe-orchestrator")
project(":speech-gateway").projectDir = file("src/speech-gateway")
project(":vllm-router").projectDir = file("src/vllm-router")
project(":stack-discovery").projectDir = file("src/stack-discovery")
project(":playwright-controller").projectDir = file("src/playwright-controller")
project(":config-generator").projectDir = file("src/config-generator")
