# vehicleframework

Technical documentation is maintained in [TF-Minecraft/docs](https://github.com/TF-Minecraft/docs/tree/main/projects/vehicleframework).

Use that project index for setup, configuration, architecture, integration and testing guides. This repository contains the source and project-specific assets.

## TLibs build dependency

TLibs is a versioned Maven `provided` dependency. From this repository, prepare
it once with the shared installer, then build as usual:

```sh
python3 ../tlibs/tools/install-dependency.py --pom pom.xml
bash .github/scripts/prepare-release.sh
mvn clean verify
```

See [TLibs dependency setup](https://github.com/TF-Minecraft/TLibs/blob/v1.1.0/DEPENDENCIES.md)
for private-source access, offline installation and the pinned binary versions.
Set `GH_TOKEN` to a token with Contents read access to ServerAssets for the dependency preparation steps.
Use JDK 25 for this TLibs binary; the server must also run Java 25.

## Builds and releases

PR builds run unit tests and publish UTC `DEV-YYYYMMDD-HHmm` JARs. Numeric tags matching the Maven version create draft releases. See the [shared pipeline guide](https://github.com/TF-Minecraft/Docs/blob/main/PIPELINES.md).

Builds and server runtime require Java 25 and [TLibs 1.1.0](https://github.com/TF-Minecraft/TLibs/releases/tag/v1.1.0).
