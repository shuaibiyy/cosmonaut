package cosmos.cosmonaut
import de.gesellix.docker.client.DockerAsyncCallback
import de.gesellix.docker.client.DockerClientImpl
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

if (args[1].isEmpty() || args[2].isEmpty()) {
    println "You need to supply values for these properties: 1) cosmosUrl 2) cosmosTable"
    println "You can supply them via the gradle command:"
    println "./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name>"
    System.exit(2)
}

def scriptsDir = args[0]
def cosmosUrl = args[1]
def cosmosTable = args[2]

def keyword = 'asteroid'
if (args[3].isEmpty()) {
    println "INFO Cosmonaut: keyword property not supplied."
    println "INFO Cosmonaut: keyword defaulting to '$keyword'."
} else {
    keyword = args[3]
}

System.setProperty('docker.cert.path', System.getenv('DOCKER_CERT_PATH'))

class Cosmonaut implements DockerAsyncCallback {
    def dockerClient
    def cosmosUrl
    def cosmosTable
    def scriptsDir
    String keyword
    def events = []

    static final COSMOS_PARAM_CONFIG_MODE = 'configMode'
    static final COSMOS_PARAM_SERVICE_NAME = 'serviceName'
    static final COSMOS_PARAM_PREDICATE = 'predicate'
    static final COSMOS_PARAM_COOKIE = 'cookie'
    static final ENV_VAR_CONFIG_MODE = 'CONFIG_MODE'
    static final ENV_TO_COSMOS_KEYMAP = [
        CONFIG_MODE: COSMOS_PARAM_CONFIG_MODE,
        SERVICE_NAME: COSMOS_PARAM_SERVICE_NAME,
        PREDICATE: COSMOS_PARAM_PREDICATE,
        COOKIE: COSMOS_PARAM_COOKIE
    ]

    @Override
    def onEvent(Object event) {
        events << event

        performRequiredUpdate(event)
    }

    def launch() {
        dockerClient.events(this)
    }

    def shouldPerformUpdate(object) {
        def eventType = object?.Type
        def eventStatus = object?.status
        def containerName = object?.Actor?.Attributes?.name
        def uninterestedStatuses = ['die', 'destroy', 'kill', 'create']

        if (eventType != 'container'
            || (containerName && !containerName.toString().toLowerCase().contains(keyword))
            || uninterestedStatuses.contains(eventStatus)) {
            println "INFO Cosmonaut: No action will be taken for event received."
            return false
        }

        return true
    }

    def performRequiredUpdate(event) {
        def slurper = new JsonSlurper()
        def object = slurper.parseText(event?.toString())

        if (!shouldPerformUpdate(object)) {
            return
        }

        println event

        def eventStatus = object?.status
        def containerId = object?.id
        def inspectionContent = inspectContainer(containerId)?.content

        if (!inspectionContent) {
            return
        }

        if (!isContainerEnvValid(inspectionContent, containerId)) {
            return
        }

        waitForWeaveToRegisterEvent()

        switch(eventStatus) {
            case 'start':
                startEvent(inspectionContent)
                break
            case ['stop']:
                stopEvent()
                break
            default:
                return noOp()
        }
    }

    def waitForWeaveToRegisterEvent() {
        sleep 1000
    }

    def inspectContainer(containerId) {
        def content = null
        try {
            content = dockerClient.inspectContainer(containerId)
        } catch (Exception e) {
            println "ERROR Cosmonaut: Unable to inspect container '$containerId'."
            println "ERROR Cosmonaut: Event will be ignored."
            println "ERROR Cosmonaut: ${e.getMessage()}"
        }
        return content
    }

    def updateHAProxy(payload) {
        def updateScript = "$scriptsDir/update_haproxy.sh"
        def command = [updateScript, cosmosUrl, JsonOutput.toJson(payload)]
        def process = command.execute()

        process.waitForProcessOutput(System.out, System.err)
    }

    def startEvent(inspectionContent) {
        updateHAProxy(startEventPayload(inspectionContent))
    }

    def stopEvent() {
        updateHAProxy(stopEventPayload())
    }

    def noOp() {}

    def startEventPayload(inspectionContent) {
        def dnsEntries = weaveDnsEntries()

        return [tableName: cosmosTable] + runningServices(dnsEntries) + candidateService(inspectionContent, dnsEntries)
    }

    def stopEventPayload() {
        return [tableName: cosmosTable] + runningServices(weaveDnsEntries())
    }

    def weaveDnsEntries() {
        return 'weave status dns'.execute().text
    }

    def findContainerWeaveIp(containerId, String dnsEntries) {
        def servicesMapArray = servicesFromDns(dnsEntries)

        def ip = servicesMapArray.findAll { serviceMap ->
            serviceMap['id'] == containerId
        }.first().ip

        return ip
    }

    def runningServices(String dnsEntries) {
        return [runningServices: servicesFromDns(dnsEntries)]
    }

    def servicesFromDns(String dnsEntries) {
        def entriesArray = dnsEntries.split('\\r?\\n')

        def servicesMapArray = entriesArray.collect { entry ->
            def tokens = entry.tokenize()
            return [serviceName: tokens[0], id: tokens[2], ip: tokens[1]]
        }

        return servicesMapArray
    }

    def candidateService(inspectionContent, String dnsEntries) {
        def containerMapArray = containerArrayMap(inspectionContent, dnsEntries)
        def serviceEnvMap = serviceEnvMap(inspectionContent)

        Map serviceAttrs = serviceEnvMap + containerMapArray

        return [candidateServices: [serviceAttrs]]
    }

    def containerArrayMap(inspectionContent, String dnsEntries) {
        def containerId = shortenContainerId(inspectionContent.Id)
        def ipAddress = findContainerWeaveIp(containerId, dnsEntries)
        return [containers: [[id: containerId, ip: ipAddress]]]
    }

    def shortenContainerId(id) {
        return id.substring(0, 12)
    }

    def serviceEnvMap(inspectionContent) {
        def env = inspectionContent.Config.Env

        def envTuples = env.collect { envVar ->
            def (key, val) = envVar.tokenize('=')
            return new Tuple2(key, val)
        }

        def envMap = envTuples
                .collectEntries { tuple -> [tuple.first, tuple.second] }
                .findAll { entry -> ENV_TO_COSMOS_KEYMAP.keySet().contains entry.key }
                .collectEntries { k, v -> [ENV_TO_COSMOS_KEYMAP.get(k), v] }

        return envMap
    }

    def isContainerEnvValid(content, containerId) {
        def env = serviceEnvMap(content)
        def requiredEnvKeys = [
            COSMOS_PARAM_CONFIG_MODE,
            COSMOS_PARAM_SERVICE_NAME,
            COSMOS_PARAM_PREDICATE
        ]
        def isValid = true

        requiredEnvKeys.forEach {
            if (!env.containsKey(it)) {
                isValid = false
                def envKey = ENV_TO_COSMOS_KEYMAP.find { v -> v.value == it }?.key
                printMissingEnvVar(envKey, containerId)
            }
        }

        if (isValid) {
            isValid = isConfigModeAllowed(env[COSMOS_PARAM_CONFIG_MODE].toString().toLowerCase())
        }

        return isValid
    }

    def isConfigModeAllowed(mode) {
        def allowedConfigModeVals = ['host', 'path']
        def allowed = allowedConfigModeVals.contains(mode)

        if (!allowed) {
            println "ERROR Cosmonaut: '$ENV_VAR_CONFIG_MODE' environment variable has an incorrect value."
            println "ERROR Cosmonaut: Event will be ignored."
        }

        return allowed
    }

    def printMissingEnvVar(key, containerId) {
        println "ERROR Cosmonaut: Missing environment variable '$key' in container '$containerId'."
        println "ERROR Cosmonaut: Event will be ignored."
    }
}

Cosmonaut cosmonaut = new Cosmonaut(dockerClient: new DockerClientImpl(System.getenv('DOCKER_HOST')),
        cosmosUrl: cosmosUrl, cosmosTable: cosmosTable, scriptsDir: scriptsDir, keyword: keyword)
cosmonaut.launch()
