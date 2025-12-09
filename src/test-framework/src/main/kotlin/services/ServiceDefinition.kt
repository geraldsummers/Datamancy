package services

data class ServiceDefinition(
    var name: String = "",
    var url: String = "",
    var authType: AuthType = AuthType.NONE,
    var healthEndpoint: String? = null,
    var screenshots: List<ScreenshotStep> = emptyList(),
    var checkpoints: List<Checkpoint> = emptyList(),
    var dependencies: List<String> = emptyList(),
    var oauthProvider: String? = null,
    var credentials: BasicCredentials? = null,
    var token: String? = null,
    var testType: String? = null,
    var expectedStatus: Int? = null,
    var internal: Boolean = false,
    var retries: Int = 0,
    var finalWaitSelector: String? = null,
    var oidcButtonSelector: String? = null
) {
    constructor() : this("", "")
}

data class ScreenshotStep(
    var name: String = "",
    var waitFor: String? = null
) {
    constructor() : this("")
}

data class Checkpoint(
    var selector: String = "",
    var description: String = ""
) {
    constructor() : this("", "")
}

data class BasicCredentials(
    var username: String = "",
    var password: String = ""
) {
    constructor() : this("", "")
}

data class ServicesConfig(
    var services: List<ServiceDefinition> = emptyList()
) {
    constructor() : this(emptyList())
}
