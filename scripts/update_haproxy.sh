#!/bin/bash

cosmosUrl=$1
payload=$2

# To store data received from Cosmos, which contains formatting characters.
haproxy_uf="/tmp/haproxycfg_unformatted"

# To store final HAProxy config.
haproxy_cfg="/tmp/haproxy.cfg"

# Remove files if they exist.
rm ${haproxy_uf} 2> /dev/null
rm ${haproxy_cfg} 2> /dev/null

# Get config from Cosmos
curl -o ${haproxy_uf} -H "Content-Type: application/json" \
    -X POST -d ${payload} ${cosmosUrl}

# Exit if error is found in cURL result.
if grep -q error ${haproxy_uf}; then
    echo "Error occurred:" >&2
    echo "$(<${haproxy_uf})" >&2
    exit 1
fi

# Store unformatted config in a variable.
text=$(cat ${haproxy_uf})

# Strip double quotes.
nodoublequotes=$(echo "$text" | tr -d '"')

# Save config with processed formatted in haproxy.cfg.
echo -e ${nodoublequotes} > ${haproxy_cfg}

# Check if HAProxy container is running.
haproxy_status=$(docker inspect -f {{.State.Running}} haproxy)

if [ "${haproxy_status}" == true ] ; then
    echo "Reloading HAProxy config..."
    docker exec haproxy /haproxy.sh
else
    echo "There is no container with the name 'haproxy' running!"
    exit 1
fi

exit 0
