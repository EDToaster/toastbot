#!/usr/bin/env bash

cd /home/ec2-user/server
DISCORD_TOKEN=$(cat DISCORD_SECRET) nohup /usr/bin/java -jar target/*.jar > /dev/null 2>&1 &

exit 0