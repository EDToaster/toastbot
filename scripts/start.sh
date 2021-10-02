#!/usr/bin/env bash

cd /home/ec2-user/server
nohup sudo DISCORD_TOKEN=$(cat DISCORD_SECRET) /usr/bin/java -jar target/*.jar /dev/null 2> /dev/null < /dev/null &

exit 0