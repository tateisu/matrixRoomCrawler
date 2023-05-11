#!/bin/bash --
set -eu

(
	cd matrixRoomCrawler
	./gradlew shadowJar
)

JAR_SRC=`ls -1t matrixRoomCrawler/build/libs/matrixRoomCrawler-*.jar |head -n 1 | sed -e "s/[\r\n]\+//g"`

if [ -n "$JAR_SRC" ]; then
	rsync $JAR_SRC sirius:/x/matrixRoomList/matrixRoomCrawler.jar
	ssh sirius "cd /x/matrixRoomList && ./update.pl"
fi
