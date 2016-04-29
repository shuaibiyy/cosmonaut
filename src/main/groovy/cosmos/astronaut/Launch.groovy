package cosmos.astronaut

import de.gesellix.docker.client.DockerAsyncCallback
import de.gesellix.docker.client.DockerClientImpl
import groovy.json.JsonSlurper

if (args[0].isEmpty() || args[1].isEmpty()) {
    System.exit(2)
}

def cosmosUrl = args[0]
def cosmosTable = args[1]

System.setProperty("docker.cert.path", "/Users/${System.getProperty('user.name')}/.docker/machine/machines/dev")
def dockerClient = new DockerClientImpl(System.env.DOCKER_HOST)

DockerAsyncCallback callback = new DockerAsyncCallback() {
    def events = []

    @Override
    def onEvent (Object event) {
        events << event
        println event
        performRequiredUpdates(event)
    }

    def performRequiredUpdates (event) {
        def slurper = new JsonSlurper()
        def object = slurper.parseText(event?.toString())

        def eventType = object?.status
        def containerId = object?.id

        switch(eventType) {
            case "start":
                startEvent(containerId)
                break
            case ["stop", "destroy"]:
                stopOrDestroyEvent(containerId)
                break
            default:
                return noOp()
        }
    }

    def startEvent (containerId) {
        def serviceAttrs = getServiceAttrs(containerId)

        println serviceAttrs.toMapString()

        getWeaveDnsEntries()
    }

    def stopOrDestroyEvent (containerId) {
        def serviceAttrs = getServiceAttrs(containerId)

        println serviceAttrs.toMapString()
    }

    def noOp () {}

    def getWeaveDnsEntries () {
        def entries = "weave status dns".execute().text

        println entries

        return entries
    }

    def getServiceAttrs (containerId) {

        // What if docker inspection fails?
        // Also, how do we perform updates only for containers we are interested in?

        def inspectionContent = dockerClient.inspectContainer(containerId).content
        def ipAddress = getIpAddressFromInspection(inspectionContent)

        def containersMap = [containers:[[id: containerId, ip: ipAddress]]]
        def serviceEnvMap = getServiceEnvFromInspection(inspectionContent)

        Map serviceAttrs = serviceEnvMap + containersMap

        return serviceAttrs
    }

    def getIpAddressFromInspection(content) {
        return content.NetworkSettings.IPAddress
    }

    def getServiceEnvFromInspection (content) {
        def env = content.Config.Env
        def envToServiceKeysMap = [
            CONFIG_MODE: "configMode",
            SERVICE_NAME: "serviceName",
            DOMAIN_NAME: "predicate",
            COOKIE: "cookie"
        ]

        def envTuples = env.collect {
            def (key, val) = it.tokenize('=')
            return new Tuple2(key, val)
        }

        def envMap = envTuples
                .collectEntries { tuple -> [tuple.first, tuple.second] }
                .findAll { envToServiceKeysMap.keySet().contains it.key }

        def serviceEnvMap = envMap.collectEntries { k, v -> [envToServiceKeysMap.get(k), v] }

        return serviceEnvMap
    }
}

dockerClient.events(callback)
