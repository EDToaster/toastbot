#!/usr/bin/env bash

cd /home/ec2-user/server
export DISCORD_TOKEN=$(cat DISCORD_SECRET)
sudo /usr/bin/java -jar target/*.jar &