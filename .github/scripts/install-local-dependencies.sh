#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/json-simple-1.1.jar" -DgroupId="local" -DartifactId="json-simple" \
    -Dversion="1.1-tfmc-2d9484f4c649" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/gson-2.10.1.jar" -DgroupId="local" -DartifactId="gson" \
    -Dversion="2.10.1-tfmc-4241c14a7727" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ProtocolLib.jar" -DgroupId="local" -DartifactId="ProtocolLib" \
    -Dversion="1.0-tfmc-ee2e7ab9b538" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicMobs-5.8.0-SNAPSHOT.jar" -DgroupId="local" -DartifactId="MythicMobs" \
    -Dversion="5.8.0-SNAPSHOT-tfmc-575aa30aee8e" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/joml-1.10.8.jar" -DgroupId="local" -DartifactId="joml" \
    -Dversion="1.10.8-tfmc-bf1951014517" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ModelEngine-4.0.8.jar" -DgroupId="local" -DartifactId="ModelEngine" \
    -Dversion="4.0.8-tfmc-44ee292392dd" -Dpackaging=jar -DgeneratePom=true "$@"
