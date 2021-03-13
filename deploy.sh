#!/bin/bash --
set -eux

cd web
git pull
cd ..

java -jar ./matrixRoomCrawler.jar

cd web
git add public/avatar/
git commit -a -m "auto update"
git push

