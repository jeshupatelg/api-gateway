#!/bin/bash

# Create the network only if it doesn't already exist
docker network inspect gateway_net >/dev/null 2>&1 || docker network create gateway_net