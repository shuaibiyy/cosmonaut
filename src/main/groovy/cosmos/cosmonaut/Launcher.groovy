package cosmos.cosmonaut

import de.gesellix.docker.client.DockerAsyncCallback
import de.gesellix.docker.client.DockerClient
import de.gesellix.docker.client.DockerClientImpl
import groovy.json.JsonSlurper

if (args[0].isEmpty() || args[1].isEmpty()) {
    System.exit(2)
}

def cosmosUrl = args[0]
def cosmosTable = args[1]

System.setProperty("docker.cert.path", "/Users/${System.getProperty('user.name')}/.docker/machine/machines/dev")

class Cosmonaut implements DockerAsyncCallback {
    DockerClient dockerClient

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
        getDockerClient().events(this)
    }

    def shouldPerformUpdate(object) {
        def eventType = object?.Type
        def eventStatus = object?.status
        def image = object?.from
        def undesirableStatuses = ['die', 'destroy']

        if (eventType != 'container' || (image && image.contains('weave'))
            || undesirableStatuses.contains(eventStatus)) {
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

        waitForWeaveRegistration()

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

    def waitForWeaveRegistration() {
        sleep 1000
    }

    def inspectContainer(containerId) {
        def content = null
        try {
            content = getDockerClient().inspectContainer(containerId)
        } catch (Exception e) {
            println e.getMessage()
        }
        return content
    }

    def startEvent (inspectionContent) {
        def runningServices = servicesFromDns(weaveDnsEntries())
        def newServiceAttrs = serviceAttrs(inspectionContent)

        Map cosmosPayload = runningServices + newServiceAttrs

        println cosmosPayload.toMapString()
    }

    def stopEvent () {}

    def noOp () {}

    def weaveDnsEntries() {
        def entries = 'weave status dns'.execute().text

        return entries
    }

    def servicesFromDns(String entries) {
        def entriesArray = entries.split('\\r?\\n')

        def services = entriesArray.collect {
            def tokens = it.tokenize()
            return [serviceName: tokens[0], id: tokens[2], ip: tokens[1]]
        }

        return [runningServices: services]
    }

    def serviceAttrs(inspectionContent) {
        def containerMapArray = containerArrayMap(inspectionContent)
        def serviceEnvMap = serviceEnvMap(inspectionContent)

        Map serviceAttrs = serviceEnvMap + containerMapArray

        return serviceAttrs
    }

    def containerArrayMap(inspectionContent) {
        def ipAddress = inspectionContent.NetworkSettings.IPAddress
        def containerId = inspectionContent.Id
        return [containers: [[id: shortenContainerId(containerId), ip: ipAddress]]]
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

        def envTuples = env.collect {
            def (key, val) = it.tokenize('=')
            return new Tuple2(key, val)
        }

        def envMap = envTuples
                .collectEntries { tuple -> [tuple.first, tuple.second] }
                .findAll { envToServiceKeysMap.keySet().contains it.key }
                .collectEntries { k, v -> [envToServiceKeysMap.get(k), v] }

        return envMap
    }

    def isServiceEnvValid(env) {
        def allowedConfigModeVals = ['host', 'path']

        return allowedConfigModeVals.contains(env.get(COSMOS_PARAM_CONFIG_MODE).toLowerCase())
    }
}

Cosmonaut cosmonaut = new Cosmonaut(dockerClient: new DockerClientImpl(System.env.DOCKER_HOST))
cosmonaut.launch()
