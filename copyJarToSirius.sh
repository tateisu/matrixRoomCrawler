#!/bin/bash --
set -eu

JAR_SRC=`ls -1t matrixRoomCrawler/build/libs/matrixRoomCrawler-*.jar |head -n 1 | sed -e "s/[\r\n]\+//g"`

if [ -n "$JAR_SRC" ]; then
	scp $JAR_SRC sirius:/x/matrixRoomList/matrixRoomCrawler.jar
fi
