# Cosmonaut

Cosmonaut monitors containers, and maintains a HAProxy config based on their states. Cosmonaut works with [Cosmos](https://github.com/shuaibiyy/cosmos) to achieve this.

## How does it work?

When Cosmonaut is run on a machine, it does the following:

1. Listens to events from the machine's docker daemon.
2. When an event relevant to Cosmonaut occurs such as starting or stopping a service container, it retrieves a HAProxy configuration from Cosmos based on the services running and the event's subject.
3. It uses the retrieved HAProxy to reload the machine's HAProxy container.

## Requirements

1. [Docker 1.11 or higher](https://www.docker.com/).
2. [Weave Net](https://www.weave.works/products/weave-net/): monitored containers must belong to a weave network.
3. Java 7 or higher.
4. HAProxy: I use [rstiller/haproxy](https://github.com/rstiller/dockerfiles/tree/master/haproxy). If you are using a different HAProxy, be sure to update the config reload strategy in `scripts/update_haproxy.sh`.

## Concepts

### Keyword

Cosmonaut uses a keyword to determine if the container tied to an event is significant. This limits the containers that are included in the HAProxy config to the ones you are interested in. Cosmonaut performs a case insensitive search for the keyword in a container's name. The default keyword is `asteroid`.

## Setup

1. Make sure you have a Cosmos instance running.
2. `git clone` this repo.
3. Ensure Docker and Weave Net are running, and the Weave Net environment is configured, so that containers launched via the Docker command line are automatically attached to the Weave network. This is normally done by running the following commands:

        $ weave launch
        $ eval $(weave env)
4. Set the required `DOCKER_HOST` and optional `DOCKER_CERT_PATH` environment variables. For example:

        $ export DOCKER_HOST="tcp://192.168.99.100:2376"
        $ export DOCKER_CERT_PATH="/Users/<user>/.docker/machine/machines/dev"
Or:

        $ export DOCKER_HOST=unix:///var/run/docker.sock
5. Run:

        $ ./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name>
where `cosmosUrl` is the URL of your Cosmos endpoint and `cosmosTable` is the name of the DynamoDB table Cosmos will create and use to persist the state of the cosmonaut machine's services. Cosmos uses the persisted state to ensure the HAProxy config data for running services can be reproduced. Also, you can pass in a [keyword](#keyword) by running:

        $ ./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name> -Pkeyword=<keyword_value>
        
### Debugging

To enable debug logging, set the environment variable `COSMONAUT_LOG` to `debug` before starting Cosmonaut, e.g.

        $ export COMSONAUT_LOG=debug

## Usage

Simply run a docker container with the required environment variables:

        $ docker run -d -ti -e CONFIG_MODE=[host|path] -e SERVICE_NAME=<service_name> -e PREDICATE=<predicate> -e COOKIE=<cookie_name> --name <container_name> <image>

E.g.:

        $ eval "$(weave env)"
        $ docker run -d -ti -e CONFIG_MODE=host -e SERVICE_NAME=app1 -e PREDICATE=first.example.com -e COOKIE=JSESSIONID --name a1-asteroid ubuntu

Note that Cosmonaut requires Weave to be used as an overlay network on the host, and containers must be run within the Weave network, hence the `eval $(weave env)` command.

### Environment Variables

* __CONFIG_MODE__: type of routing. It can be either `path` or `host`.
        [1] In `path` mode, the URL path is used to determine which backend to forward the request to.
        [2] In `host` mode, the HTTP host header is used to determine which backend to forward the request to.
        Defaults to `host` mode.
* __SERVICE_NAME__: name of service the container belongs to.
* __PREDICATE__: value used along with mode to determine which service a request will be forwarded to.
        [1] `path` mode example: `acl <cluster> url_beg /<predicate>`.
        [2] `host` mode example: `acl <cluster> hdr(host) -i <predicate>`.
* __COOKIE__: name of cookie to be used for sticky sessions. If not defined, sticky sessions will not be configured.
* __PORT__: port number where the service on the container is listening on.
