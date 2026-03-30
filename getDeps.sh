#!/bin/bash
WP_VER="2.26.1"
WP_URL="https://www.worldpainter.net/files/worldpainter_$WP_VER.tar.gz"

mkdir -p tmp_jide
cd tmp_jide

curl -o WorldPainter.tar.gz "$WP_URL"
tar -xvf WorldPainter.tar.gz

mvn install:install-file \
   -Dfile=./worldpainter/lib/jide-dock.jar \
   -DgroupId=com.jidesoft \
   -DartifactId=jide-dock \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true


mvn install:install-file \
   -Dfile=./worldpainter/lib/jide-common.jar \
   -DgroupId=com.jidesoft \
   -DartifactId=jide-common \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true


mvn install:install-file \
   -Dfile=./worldpainter/lib/jide-plaf-jdk7.jar \
   -DgroupId=com.jidesoft \
   -DartifactId=jide-plaf-jdk7 \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true


mvn install:install-file \
   -Dfile=./worldpainter/lib/jpen.jar \
   -DgroupId=net.sourceforge \
   -DartifactId=jpen \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true

mvn install:install-file \
   -Dfile=./worldpainter/lib/laf-dark.jar \
   -DgroupId=org.netbeans.swing \
   -DartifactId=laf-dark \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true

mvn install:install-file \
   -Dfile=./worldpainter/lib/DynmapCore.jar \
   -DgroupId=us.dynmap \
   -DartifactId=DynmapCore \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true

mvn install:install-file \
   -Dfile=./worldpainter/lib/DynmapCoreAPI.jar \
   -DgroupId=us.dynmap \
   -DartifactId=DynmapCoreAPI \
   -Dversion=local \
   -Dpackaging=jar \
   -DgeneratePom=true

cd ..
rm -r tmp_jide