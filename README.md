# Cosmonaut

A [Cosmos](https://github/shuaibiyy/cosmos) client written in Groovy.

When Cosmonaut is run on a machine, it does the following:

1. Listens to events from the machine's docker daemon.
2. When an event relevant to Cosmonaut occurs such as starting or stopping a service container, it retrieves a HAProxy configuration from Cosmos based on the services running and the event's subject.
3. It uses the retrieved HAProxy to reload the machine's HAProxy container.

## Setup

1. Make sure you have a Cosmos instance running.
2. `git clone` this repo.
3. Run `./gradlew -PcosmosUrl=<url> -PcosmosTable=<table_name>` where `cosmosUrl` is the URL of your Cosmos endpoint and `cosmosTable` is the name of the DynamoDB table Cosmos will create and use to persist the state of the cosmonaut machine's services. Cosmos uses the persisted state to ensure the HAProxy config data for running services can be reproduced. 
