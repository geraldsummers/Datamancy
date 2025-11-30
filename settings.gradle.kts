rootProject.name = "Datamancy"

// Include all Kotlin subprojects
include(":kfuncdb")
include(":probe-orchestrator-kt")
include(":ktspeechgateway")
include(":vllm-model-router-kt")
include(":stack-discovery")

// Map subproject directories
project(":kfuncdb").projectDir = file("src/kfuncdb")
project(":probe-orchestrator-kt").projectDir = file("src/probe-orchestrator-kt")
project(":ktspeechgateway").projectDir = file("src/ktspeechgateway")
project(":vllm-model-router-kt").projectDir = file("src/vllm-model-router-kt")
project(":stack-discovery").projectDir = file("src/stack-discovery")
