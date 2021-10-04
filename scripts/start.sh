#!/usr/bin/env bash

cd /home/ec2-user/server
echo "DISCORD_SECRET_$DEPLOYMENT_GROUP_NAME"
DISCORD_TOKEN=$(cat "DISCORD_SECRET_$DEPLOYMENT_GROUP_NAME") nohup /usr/bin/java -jar target/*.jar > /dev/null 2>&1 &

exit 0