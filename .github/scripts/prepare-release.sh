#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?Set DEPS_TOKEN with Contents read access to TF-Minecraft/ServerAssets}"
ref=4b80431398e4ff35d703cad915b7ee4e5924a763
mkdir -p libs
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/4e69696892b8/json-simple-1.1.1.jar?ref=$ref" > "libs/json-simple-1.1.1.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/2cbd119bf196/gson-2.14.0.jar?ref=$ref" > "libs/gson-2.14.0.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/355f7117af95/ProtocolLib-5.5.0-SNAPSHOT.jar?ref=$ref" > "libs/ProtocolLib-5.5.0-SNAPSHOT.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/6df72b5b331d/MythicMobs-5.13.1-SNAPSHOT-88530541.jar?ref=$ref" > "libs/MythicMobs-5.13.1-SNAPSHOT-88530541.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/feca4db85337/joml-1.10.9.jar?ref=$ref" > "libs/joml-1.10.9.jar"
curl --fail --location --silent --show-error --retry 3 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github.raw+json" "https://api.github.com/repos/TF-Minecraft/ServerAssets/contents/jars/44ee292392dd/ModelEngine-R4.1.1.jar?ref=$ref" > "libs/ModelEngine-R4.1.1.jar"
bash .github/scripts/install-local-dependencies.sh "$@"
