package cosmos.cosmonaut

import de.gesellix.docker.client.DockerAsyncCallback
import de.gesellix.docker.client.DockerClient
import de.gesellix.docker.client.DockerClientImpl
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

if (args[1].isEmpty() || args[2].isEmpty()) {
    println "You need to supply values for these properties: 1) cosmosUrl 2) cosmosTable"
    println "You can supply them via the gradle command:"
    println "./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name>"
    System.exit(2)
}

String scriptsDir = args[0]
String cosmosUrl = args[1]
String cosmosTable = args[2]

String keyword = 'asteroid'
if (args[3].isEmpty()) {
    println "INFO Cosmonaut: keyword property not supplied."
    println "INFO Cosmonaut: keyword defaulting to '$keyword'."
} else {
    keyword = args[3]
}

String certPath = System.getenv('DOCKER_CERT_PATH') ? System.getenv('DOCKER_CERT_PATH') : ''
System.setProperty('docker.cert.path', certPath)

class Cosmonaut implements DockerAsyncCallback {
    DockerClient dockerClient
    String cosmosUrl
    String cosmosTable
    String scriptsDir
    String keyword
    List events = []

    static final COSMOS_PARAM_CONFIG_MODE = 'configMode'
    static final COSMOS_PARAM_SERVICE_NAME = 'serviceName'
    static final COSMOS_PARAM_PREDICATE = 'predicate'
    static final COSMOS_PARAM_COOKIE = 'cookie'
    static final COSMOS_PARAM_PORT = 'port'
    static final ENV_VAR_CONFIG_MODE = 'CONFIG_MODE'
    static final ENV_TO_COSMOS_KEYMAP = [
        CONFIG_MODE: COSMOS_PARAM_CONFIG_MODE,
        SERVICE_NAME: COSMOS_PARAM_SERVICE_NAME,
        PREDICATE: COSMOS_PARAM_PREDICATE,
        COOKIE: COSMOS_PARAM_COOKIE,
        PORT: COSMOS_PARAM_PORT
    ]

    @Override
    def onEvent(Object event) {
        events << event
        performRequiredUpdate(event)
    }

    def launch() {
        dockerClient.events(this)
    }

    /**
     * Determine if an update should be performed based on:
     *  - Event status
     *  - Event container type
     *  - Keyword
     * @param object docker event object.
     * @return boolean whether or not update should be performed.
     */
    def shouldPerformUpdate(object) {
        def eventType = object?.Type
        def eventStatus = object?.status
        def containerName = object?.Actor?.Attributes?.name
        def uninterestedStatuses = ['die', 'destroy', 'kill', 'create']

        if (eventType != 'container'
            || (containerName && !containerName.toString().toLowerCase().contains(keyword))
            || uninterestedStatuses.contains(eventStatus)) {
            if (System.getenv('COSMONAUT_LOG') && System.getenv('COSMONAUT_LOG').toLowerCase() == 'debug') {
                println "DEBUG Cosmonaut: No action will be taken for event received."
                println "DEBUG Cosmonaut: --- Event:"
                println "DEBUG Cosmonaut: ${object}"
                println "DEBUG Cosmonaut: ---"
            }
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

        String eventStatus = object?.status
        String containerId = object?.id
        def inspectionContent = inspectContainer(containerId)?.content

        waitForDnsToRegisterEvent()
        String dnsEntries = weaveDnsEntries()

        if (!inspectionContent
                || !isContainerEnvValid(inspectionContent, containerId)
                || !isContainerInDns(dnsEntries, shortenContainerId(containerId))) {
            return
        }

        switch(eventStatus) {
            case 'start':
                startEvent(inspectionContent, dnsEntries)
                break
            case ['stop']:
                stopEvent(dnsEntries)
                break
            default:
                noOp()
        }
    }

    /**
     * When a container is started, weave dns needs some time to configure the network.
     * @return void
     */
    def waitForDnsToRegisterEvent() {
        sleep 3000
    }

    /**
     * Run `docker inspect <container_id>` command.
     * @param containerId container id.
     * @return object result of docker inspection.
     */
    def inspectContainer(containerId) {
        def content = null
        try {
            content = dockerClient.inspectContainer(containerId)
        } catch (Exception e) {
            println "ERROR Cosmonaut: Unable to inspect container '$containerId'."
            println "ERROR Cosmonaut: Event will be ignored."
            println "ERROR Cosmonaut: ${e.getMessage()}"
        }
        content
    }

    def updateHAProxy(payload) {
        def updateScript = "$scriptsDir/update_haproxy.sh"
        def command = [updateScript, cosmosUrl, JsonOutput.toJson(payload)]
        def process = command.execute()
        process.waitForProcessOutput((OutputStream)System.out, System.err)
    }

    def startEvent(inspectionContent, String dnsEntries) {
        updateHAProxy(startEventPayload(inspectionContent, dnsEntries))
    }

    def stopEvent(String dnsEntries) {
        updateHAProxy(stopEventPayload(dnsEntries))
    }

    def noOp() {}

    def startEventPayload(inspectionContent, String dnsEntries) {
        [table: cosmosTable] + runningServices(dnsEntries) + candidateService(inspectionContent, dnsEntries)
    }

    def stopEventPayload(String dnsEntries) {
        [table: cosmosTable] + runningServices(dnsEntries)
    }

    def isContainerInDns(String entries, String id) {
        entries.contains(id)
    }

    /**
     * Run `weave status dns` command.
     * @return String string of weave dns entries.
     */
    def weaveDnsEntries() {
        'weave status dns'.execute().text
    }

    /**
     * Obtain ip address of the event's container from weave dns entries.
     * @param containerId container id.
     * @param dnsEntries weave dns entries.
     * @return String ip address.
     */
    def findContainerWeaveIp(containerId, String dnsEntries) {
        List<Map<String, String>> services = servicesFromDns(dnsEntries)
        services.findAll { it['id'] == containerId }.first().ip
    }

    /**
     * Get services running in the weave network.
     * @param dnsEntries weave dns entries.
     * @return Map single-valued map of array of running services.
     */
    def runningServices(String dnsEntries) {
        [running: servicesFromDns(dnsEntries)]
    }

    /**
     * Get services in the weave network.
     * @param dnsEntries weave dns entries.
     * @return Array array of maps of services.
     */
    def servicesFromDns(String dnsEntries) {
        String[] entries = dnsEntries.split('\\r?\\n')
        List<Map<String, String>> services = entries.collect { entry ->
            String[] tokens = entry.tokenize()
            return [serviceName: tokens[0], id: tokens[2], ip: tokens[1]]
        }
        services
    }

    /**
     * Get services(s) from container(s) that initiated the docker event,
     * along with service information extracted from container environment variables.
     * @param inspectionContent result of `docker inspect` command.
     * @param dnsEntries weave dns entries.
     * @return Map map of array of services.
     */
    def candidateService(inspectionContent, String dnsEntries) {
        Map<String, List<Map<String, Object>>> containers = serviceContainers(inspectionContent, dnsEntries)
        Map<String, String> containerEnv = serviceEnv(inspectionContent)
        [candidates: [containerEnv + containers]]
    }

    /**
     * Get container(s) that is/are the subject of the docker event.
     * @param inspectionContent
     * @param dnsEntries
     * @return Map map of array of containers.
     */
    def serviceContainers(inspectionContent, String dnsEntries) {
        String containerId = shortenContainerId(inspectionContent.Id.toString())
        String ipAddress = findContainerWeaveIp(containerId, dnsEntries)
        [containers: [[id: containerId, ip: ipAddress]]]
    }

    /**
     * Truncate container id to a length that matches docker's shortened format.
     * @param id container id
     * @return String
     */
    def shortenContainerId(String id) {
        id.substring(0, 12)
    }

    /**
     * Compute a map of the container's environment variables,
     * with keys mapped to their cosmos payload counterparts e.g. CONFIG_MODE -> configMode.
     * @param inspectionContent result of `docker inspect` command.
     * @return Map map of environment variables formatted for Cosmos payload.
     */
    def serviceEnv(inspectionContent) {
        def containerEnv = inspectionContent.Config.Env

        List<Tuple2> tuples = containerEnv.collect {
            def (key, val) = it.tokenize('=')
            return new Tuple2(key, val)
        }

        Map<String, String> env = tuples
                .collectEntries { tuple -> [tuple.first, tuple.second] }
                .findAll { entry -> ENV_TO_COSMOS_KEYMAP.keySet().contains entry.key }
                .collectEntries { k, v -> [ENV_TO_COSMOS_KEYMAP.get(k), v] } as Map<String, String>
        return env
    }

    /**
     * Verify that required container environment variables exist and are only set to allowed values.
     * @param content result of `docker inspect` command.
     * @param containerId id of container that triggered docker event.
     * @return boolean whether or not the environment variables are valid.
     */
    def isContainerEnvValid(content, containerId) {
        def env = serviceEnv(content)
        def requiredKeys = [
            COSMOS_PARAM_CONFIG_MODE,
            COSMOS_PARAM_SERVICE_NAME,
            COSMOS_PARAM_PORT,
            COSMOS_PARAM_PREDICATE
        ]
        def isValid = true

        requiredKeys.each {
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

    /**
     * Is CONFIG_MODE environment variable set to an allowed value?
     * Values allowed are:
     *  - host
     *  - path
     * @param mode value of CONFIG_MODE environment variable.
     * @return boolean whether or not the mode is allowed.
     */
    def isConfigModeAllowed(mode) {
        def allowed = ['host', 'path'].contains(mode)

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
