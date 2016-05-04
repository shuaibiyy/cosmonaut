package cosmos.cosmonaut
import de.gesellix.docker.client.DockerAsyncCallback
import de.gesellix.docker.client.DockerClientImpl
import groovy.json.JsonSlurper
import groovyx.net.http.HTTPBuilder

if (args[0].isEmpty() || args[1].isEmpty()) {
    System.exit(2)
}

def cosmosUrl = args[0]
def cosmosTable = args[1]

System.setProperty("docker.cert.path", "/Users/${System.getProperty('user.name')}/.docker/machine/machines/dev")

class Cosmonaut implements DockerAsyncCallback {
    def dockerClient
    def cosmosUrl
    def cosmosTable
    def events = []

    static final COSMOS_PARAM_CONFIG_MODE = 'configMode'
    static final COSMOS_PARAM_SERVICE_NAME = 'serviceName'
    static final COSMOS_PARAM_PREDICATE = 'predicate'
    static final COSMOS_PARAM_COOKIE = 'cookie'

    @Override
    def onEvent (Object event) {
        events << event
        println event

        performRequiredUpdate(event)
    }

    def launch() {
        dockerClient.events(this)
    }

    def shouldPerformUpdate(object) {
        def eventType = object?.Type
        def eventStatus = object?.status
        def image = object?.from
        def uninterestedStatuses = ['die', 'destroy']

        if (eventType != 'container' || (image && image.contains('weave'))
            || uninterestedStatuses.contains(eventStatus)) {
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

        def eventStatus = object?.status
        def containerId = object?.id
        def inspectionContent = inspectContainer(containerId)?.content

        if (!inspectionContent) {
            return
        }

        waitForWeaveToRegisterEvent()

        switch(eventStatus) {
            case 'start':
                startEvent(inspectionContent)
                break
            case ['stop', 'kill']:
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
            println e.getMessage()
        }
        return content
    }

    def getConfigFile(payload) {
        def http = new HTTPBuilder(cosmosUrl)

        http.request(POST, JSON) { req ->
            body = payload

            response.success = { resp, json ->
                println resp.status
            }

            response.failure = { resp ->
                println resp.status
            }
        }
    }

    def startEvent(inspectionContent) {
//        getConfigFile(startEventPayload(inspectionContent))

        println startEventPayload(inspectionContent).inspect()
    }

    def stopEvent() {
        getConfigFile(stopEventPayload())
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
        def envToServiceKeysMap = [
                CONFIG_MODE: COSMOS_PARAM_CONFIG_MODE,
                SERVICE_NAME: COSMOS_PARAM_SERVICE_NAME,
                PREDICATE: COSMOS_PARAM_PREDICATE,
                COOKIE: COSMOS_PARAM_COOKIE
        ]

        def envTuples = env.collect { envVar ->
            def (key, val) = envVar.tokenize('=')
            return new Tuple2(key, val)
        }

        def envMap = envTuples
                .collectEntries { tuple -> [tuple.first, tuple.second] }
                .findAll { entry -> envToServiceKeysMap.keySet().contains entry.key }
                .collectEntries { k, v -> [envToServiceKeysMap.get(k), v] }

        return envMap
    }

    def isServiceEnvValid(env) {
        def allowedConfigModeVals = ['host', 'path']

        return allowedConfigModeVals.contains(env.get(COSMOS_PARAM_CONFIG_MODE).toLowerCase())
    }
}

Cosmonaut cosmonaut = new Cosmonaut(dockerClient: new DockerClientImpl(System.env.DOCKER_HOST),
        cosmosUrl: cosmosUrl, cosmosTable: cosmosTable)
cosmonaut.launch()
