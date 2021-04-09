#!/bin/bash --
set -eux

date=`date +"%Y/%m/%d %T"`
echo "$date deploy start."

./roomSpeedBot.pl start

cd web
git pull
cd ..

java -jar ./matrixRoomCrawler.jar

./roomSpeed.pl

cd web
git add public/avatar/
git commit -a -m "auto update"
git push

date=`date +"%Y/%m/%d %T"`
echo "$date deploy end."
