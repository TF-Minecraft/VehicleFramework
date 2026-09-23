#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/json-simple-1.1.1.jar" -DgroupId="local" -DartifactId="json-simple" \
    -Dversion="1.1.1-tfmc-4e69696892b8" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/gson-2.14.0.jar" -DgroupId="local" -DartifactId="gson" \
    -Dversion="2.14.0-tfmc-2cbd119bf196" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ProtocolLib-5.5.0-SNAPSHOT.jar" -DgroupId="local" -DartifactId="ProtocolLib" \
    -Dversion="5.5.0-SNAPSHOT-tfmc-355f7117af95" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicMobs-5.13.1-SNAPSHOT-88530541.jar" -DgroupId="local" -DartifactId="MythicMobs" \
    -Dversion="5.13.1-SNAPSHOT-88530541-tfmc-6df72b5b331d" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/joml-1.10.9.jar" -DgroupId="local" -DartifactId="joml" \
    -Dversion="1.10.9-tfmc-feca4db85337" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ModelEngine-R4.1.1.jar" -DgroupId="local" -DartifactId="ModelEngine" \
    -Dversion="R4.1.1-tfmc-44ee292392dd" -Dpackaging=jar -DgeneratePom=true "$@"
