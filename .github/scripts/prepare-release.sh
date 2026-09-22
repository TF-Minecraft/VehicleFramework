#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=726208728d6b3b66d09e5efcdfab9a63b8e39228
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/2d9484f4c649/json-simple-1.1.jar?ref=$ref" > "libs/json-simple-1.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/4241c14a7727/gson-2.10.1.jar?ref=$ref" > "libs/gson-2.10.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/runtime/plugins/ProtocolLib.jar?ref=$ref" > "libs/ProtocolLib.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/575aa30aee8e/MythicMobs-5.8.0-SNAPSHOT.jar?ref=$ref" > "libs/MythicMobs-5.8.0-SNAPSHOT.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/bf1951014517/joml-1.10.8.jar?ref=$ref" > "libs/joml-1.10.8.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/runtime/plugins/ModelEngine.jar?ref=$ref" > "libs/ModelEngine-4.0.8.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/b7156eab5677/spigot-api.jar?ref=$ref" > "libs/spigot-api.jar"
sha256sum --check .github/dependencies.sha256
