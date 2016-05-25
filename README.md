# Cosmonaut

Cosmonaut monitors containers, and maintains a HAProxy config based on their states. Cosmonaut works with [Cosmos](https://github.com/shuaibiyy/cosmos) to achieve this.

## How does it work?

When Cosmonaut is run on a machine, it does the following:

1. Listens to events from the machine's docker daemon.
2. When an event relevant to Cosmonaut occurs such as starting or stopping a service container, it retrieves a HAProxy configuration from Cosmos based on the services running and the event's subject.
3. It uses the retrieved HAProxy to reload the machine's HAProxy container.

## Requirements

1. [Docker](https://www.docker.com/).
2. [Weave Net](https://www.weave.works/products/weave-net/): monitored containers must belong to a weave network.
3. Java 7 or higher.
4. HAProxy: I use [rstiller/haproxy](https://github.com/rstiller/dockerfiles/tree/master/haproxy). If you are using a different HAProxy, be sure to update the config reload strategy in `scripts/update_haproxy.sh`.

## Concepts

### Keyword

Cosmonaut uses a keyword to determine if the container tied to an event is significant. This limits the containers that are included in the HAProxy config to the ones you are interested in. Cosmonaut performs a case insensitive search for the keyword in a container's name. The default keyword is `asteroid`.

## Setup

1. Make sure you have a Cosmos instance running.
2. `git clone` this repo.
3. Set the required `DOCKER_HOST` and optional `DOCKER_CERT_PATH` environment variables. For example:

        $ export DOCKER_HOST="tcp://192.168.99.100:2376"
        $ export DOCKER_CERT_PATH="/Users/<user>/.docker/machine/machines/dev"
Or:

        $ export DOCKER_HOST=unix:///var/run/docker.sock
4. Run:

        $ ./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name>
where `cosmosUrl` is the URL of your Cosmos endpoint and `cosmosTable` is the name of the DynamoDB table Cosmos will create and use to persist the state of the cosmonaut machine's services. Cosmos uses the persisted state to ensure the HAProxy config data for running services can be reproduced. Also, you can pass in a [keyword](#keyword) by running:

        $ ./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name> -Pkeyword=<keyword_value>

## Usage

* Containers should be run within the weave network.
* Containers should be run with the required environment variables, i.e. `SERVICE_NAME`, `CONFIG_MODE`, and `PREDICATE`. Here's a sample run command:

        $ eval "$(weave env)"
        $ docker run -d -ti -e CONFIG_MODE=host -e SERVICE_NAME=app1 -e PREDICATE=first.example.com -e COOKIE=JSESSIONID --name a1-asteroid ubuntu
You can find an explanation of the environment variables and their uses [here](https://github.com/shuaibiyy/cosmos/blob/master/index.js#L6).
