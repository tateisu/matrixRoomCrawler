#!/bin/bash --
set -eux
# cd /z/matrixRoomList
# cp matrixRoomCrawler/build/libs/matrixRoomCrawler-1.0-SNAPSHOT.jar ./matrixRoomCrawler.jar
java -jar ./matrixRoomCrawler.jar
cd web
git add public/avatar/
git commit -a -m "auto update"
git push

