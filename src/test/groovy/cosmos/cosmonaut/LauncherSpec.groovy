package cosmos.cosmonaut

import de.gesellix.docker.client.DockerClient
import spock.lang.Specification

class LauncherSpec extends Specification {

    def dockerClient = Mock(DockerClient)

    def "Service environments are extracted from docker inspection content"() {
        given:
        def configModeVal = randomAlphanumeric(7)
        def serviceNameVal = randomAlphanumeric(7)
        def predicateVal = randomAlphanumeric(7)
        def cookieVal = randomAlphanumeric(7)
        Map expectedServiceEnvMap = [configMode:configModeVal, serviceName:serviceNameVal,
                                     predicate:predicateVal, cookie:cookieVal]

        def inspectionContent = someInspectionContent([
                'CONFIG_MODE=' + configModeVal,
                'SERVICE_NAME=' + serviceNameVal,
                'PREDICATE=' + predicateVal,
                'COOKIE=' + cookieVal,
                'PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
        ])

        when:
        def serviceEnvMap = new Cosmonaut(dockerClient: dockerClient).serviceEnvMap(inspectionContent)

        then:
        serviceEnvMap == expectedServiceEnvMap
    }

    def "Container attributes are extracted from docker inspection content"() {
        given:
        def containerId = randomAlphanumeric(12)
        def ipAddress = randomAlphanumeric(9)
        def serviceName = randomAlphanumeric(9)
        def inspectionContent = someInspectionContent([], containerId, ipAddress)
        def dnsEntries = someDnsEntries([[serviceName: serviceName, id: containerId, ip: ipAddress]])
        def expectedContainerMapArray = [containers:[[id: containerId, ip: ipAddress]]]

        when:
        def containerMapArray = new Cosmonaut(dockerClient: dockerClient).containerArrayMap(inspectionContent, dnsEntries)

        then:
        containerMapArray == expectedContainerMapArray
    }

    def "Running services are retrieved from Weave DNS entries"() {
        given:
        def serviceName1 = randomAlphanumeric(7)
        def serviceName2 = randomAlphanumeric(7)
        def serviceName3 = randomAlphanumeric(7)
        def ipAddress1 = randomAlphanumeric(7)
        def ipAddress2 = randomAlphanumeric(7)
        def ipAddress3 = randomAlphanumeric(7)
        def containerId1 = randomAlphanumeric(7)
        def containerId2 = randomAlphanumeric(7)
        def containerId3 = randomAlphanumeric(7)
        def services = [
                [serviceName: serviceName1, id: containerId1, ip: ipAddress1],
                [serviceName: serviceName2, id: containerId2, ip: ipAddress2],
                [serviceName: serviceName3, id: containerId3, ip: ipAddress3]
        ]
        def dnsEntries = someDnsEntries(services)
        def expectedRunningServices = [runningServices: services]

        when:
        def runningServices = new Cosmonaut(dockerClient: dockerClient).runningServices(dnsEntries)

        then:
        runningServices == expectedRunningServices
    }

    def "A containers weave IP address can be found"() {
        given:
        def containerId = randomAlphanumeric(12)
        def serviceName = randomAlphanumeric(9)
        def expectedIp = randomAlphanumeric(9)
        def dnsEntries = someDnsEntries([[serviceName: serviceName, ip: expectedIp, id: containerId]])

        when:
        def ip = new Cosmonaut(dockerClient: dockerClient).findContainerWeaveIp(containerId, dnsEntries)

        then:
        ip == expectedIp
    }

    def randomAlphanumeric = { int n ->
        new Random().with {
            def characters = (('A'..'Z')+('0'..'9')).join("")
            (1..n).collect { characters[ nextInt( characters.length() ) ] }.join("")
        }
    }

    def someDnsEntries(services) {
        return services.collect {
            "${it['serviceName']}           ${it['ip']}        ${it['id']} 52:b9:66:dc:96:3c"
        }.join("\n")
    }

    def someInspectionContent(serviceEnv = [], id = "", ipAdress = "") {
        return ['AppArmorProfile':'', 'Args':[],
               'Config':
                       ['AttachStderr':false, 'AttachStdin':false, 'AttachStdout':false, 'Cmd':['/bin/bash'],
                        'Domainname':'', 'Entrypoint':null,
                        'Env': serviceEnv,
                        'Hostname':'b9afcdc706c5', 'Image':'ubuntu', 'Labels':[:], 'OnBuild':null, 'OpenStdin':true,
                        'StdinOnce':false, 'Tty':true, 'User':'', 'Volumes':null, 'WorkingDir':''],
               'Created':'2016-05-03T04:26:13.06333819Z', 'Driver':'aufs', 'ExecIDs':null,
               'GraphDriver':['Data':null, 'Name':'aufs'],
               'HostConfig':
                       ['AutoRemove':false, 'Binds':null, 'BlkioBps':0, 'BlkioDeviceReadBps':null,
                        'BlkioDeviceReadIOps':null, 'BlkioDeviceWriteBps':null, 'BlkioDeviceWriteIOps':null,
                        'BlkioIOps':0, 'BlkioWeight':0, 'BlkioWeightDevice':null, 'CapAdd':null, 'CapDrop':null,
                        'Cgroup':'', 'CgroupParent':'', 'ConsoleSize':[0, 0], 'ContainerIDFile':'', 'CpuCount':0,
                        'CpuPercent':0, 'CpuPeriod':0, 'CpuQuota':0, 'CpuShares':0, 'CpusetCpus':'', 'CpusetMems':'',
                        'Devices':[], 'DiskQuota':0, 'Dns':[], 'DnsOptions':[], 'DnsSearch':[], 'ExtraHosts':null,
                        'GroupAdd':null, 'IpcMode':'', 'Isolation':'', 'KernelMemory':0, 'Links':null,
                        'LogConfig':
                                ['Config':[:], 'Type':'json-file'],
                        'Memory':0, 'MemoryReservation':0, 'MemorySwap':0, 'MemorySwappiness':-1,
                        'NetworkMode':'default', 'OomKillDisable':false,'OomScoreAdj':0, 'PidMode':'', 'PidsLimit':0,
                        'PortBindings':[:], 'Privileged':false, 'PublishAllPorts':false, 'ReadonlyRootfs':false,
                        'RestartPolicy':['MaximumRetryCount':0,'Name':'no'], 'SandboxSize':0, 'SecurityOpt':null,
                        'ShmSize':67108864, 'StorageOpt':null, 'UTSMode':'', 'Ulimits':null, 'UsernsMode':'',
                        'VolumeDriver':'', 'VolumesFrom':null],
               'HostnamePath':'/mnt/sda1/var/lib/docker/containers/b9afcdc706c5ed604d140ad9ab7503634ed1032205e2cb433f7d46712698586a/hostname',
               'HostsPath':'/mnt/sda1/var/lib/docker/containers/b9afcdc706c5ed604d140ad9ab7503634ed1032205e2cb433f7d46712698586a/hosts',
               'Id':id,
               'Image':'sha256:54060fb55e8320da8c7568a615ad903e1078a1a40f2441f31c3835fdbed1f984',
               'LogPath':'/mnt/sda1/var/lib/docker/containers/b9afcdc706c5ed604d140ad9ab7503634ed1032205e2cb433f7d46712698586a/b9afcdc706c5ed604d140ad9ab7503634ed1032205e2cb433f7d46712698586a-json.log',
               'MountLabel':'', 'Mounts':[], 'Name':'/a2',
               'NetworkSettings':
                       ['Bridge':'', 'EndpointID':'0f18585b6421e8fda0438dac3e01f9450596a4bb90fa2c534eec5d94e8e07f01',
                        'Gateway':'172.17.0.1', 'GlobalIPv6Address':'', 'GlobalIPv6PrefixLen':0, 'HairpinMode':false,
                        'IPAddress':ipAdress, 'IPPrefixLen':16, 'IPv6Gateway':'', 'LinkLocalIPv6Address':'',
                        'LinkLocalIPv6PrefixLen':0, 'MacAddress':'02:42:ac:11:00:03',
                        'Networks':
                                ['bridge':
                                         ['Aliases':null,
                                          'EndpointID':'0f18585b6421e8fda0438dac3e01f9450596a4bb90fa2c534eec5d94e8e07f01',
                                          'Gateway':'172.17.0.1', 'GlobalIPv6Address':'', 'GlobalIPv6PrefixLen':0,
                                          'IPAMConfig':null, 'IPAddress':ipAdress, 'IPPrefixLen':16, 'IPv6Gateway':'',
                                          'Links':null, 'MacAddress':'02:42:ac:11:00:03',
                                          'NetworkID':'e4c26c1f6372473d52540ddbf2f9f0dbbcf44d458188af1f7524e9054cb85daf']],
                        'Ports':[:], 'SandboxID':'8d2a6e423937bc703797068464fe82124685189ba49f71507857049c9e5ce231',
                        'SandboxKey':'/var/run/docker/netns/8d2a6e423937', 'SecondaryIPAddresses':null,
                        'SecondaryIPv6Addresses':null], 'Path':'/bin/bash', 'ProcessLabel':'',
               'ResolvConfPath':'/mnt/sda1/var/lib/docker/containers/b9afcdc706c5ed604d140ad9ab7503634ed1032205e2cb433f7d46712698586a/resolv.conf',
               'RestartCount':0,
               'State':
                       ['Dead':false, 'Error':'', 'ExitCode':0, 'FinishedAt':'0001-01-01T00:00:00Z','OOMKilled':false,
                        'Paused':false, 'Pid':7106, 'Restarting':false, 'Running':true,
                        'StartedAt':'2016-05-03T04:26:13.570434294Z', 'Status':'running']]
    }
}