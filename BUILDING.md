# Building and reusing the Gradle script

`build.gradle.kts` is a reusable **Java Starsector mod** build. Wingman's exceptional partial-source overlay
settings live in `gradle.properties`, not in the script. Copy **the script alone** to another mod; do not copy
Wingman's properties unchanged.

## Requirements and commands

Use JDK 17+ (or a newer JDK if your selected Java release requires it). The included Gradle wrapper
uses Gradle 9.8.2 and downloads it on first use; no system Gradle installation is required.
Supply your Starsector core directory if the mod is not already under `<game>/mods/<mod>`:

```sh
./gradlew build -PcorePath=/mnt/d/Starsector/starsector-core
./gradlew installMod -PcorePath=/mnt/d/Starsector/starsector-core
```

On Windows, use `gradlew.bat` instead of `./gradlew`.
Use native paths for the JVM running Gradle: Windows Gradle uses `D:/Starsector/starsector-core`; Linux/WSL
Gradle uses `/mnt/d/Starsector/starsector-core`.

- `jar`: compile the mod JAR.
- `packageMod`: stage the complete mod in `build/package/<mod folder>/`.
- `modZip`: produce `build/distributions/<mod folder>-<version>.zip`.
- `build`: compile/check and produce the ZIP; **does not install**.
- `installMod`: build the ZIP and extract it into the configured mods directory.

Installation uses an overlay, not a directory synchronization: unrelated files/mods survive, but matching
files **including settings JSON** are overwritten. It refuses to install over the source project itself.
Close the game before replacing an installed mod. No task enables the mod or changes saves.

## What is inferred

The script reads the root `mod_info.json`, accepting comments and trailing commas:

- `id` supplies the default distribution/install folder name.
- `version` supplies the archive version; it must be a string.
- The first `jars` entry names the JAR to build. Other declared JARs are compile dependencies and are bundled
  unchanged at their declared relative paths. Set `modJar` if the main JAR is not first.
- A declared `patchlib` dependency enables separate patch compilation and checks for the PatchLib API.

Supported source layouts are `src/` + `test/`, or `src/main/java/` + `src/test/java/`. Normal Gradle
`src/main/resources` processing remains available for resources intended to go **inside** the JAR.

Runtime packaging includes root `*.json`/`*.version`, README/changelog/release notes/license/notice files,
and the `data/`, `graphics/`, `sounds/`, and `music/` trees. The same runtime layout can instead live under
`resources/`. Duplicate packaged paths fail rather than silently pick one copy. Source code, build tooling,
undeclared JARs, and arbitrary project files are not bundled. Use `extraPackageIncludes` for other runtime files.

## Configuration

Set these in the destination mod's `gradle.properties` or with `-Pname=value`. Lists are comma-separated;
an explicitly empty list disables its defaults.

| Property | Default / purpose |
| --- | --- |
| `corePath` | `STARSECTOR_CORE`, then `../../starsector-core` |
| `modsPath` | `STARSECTOR_MODS`, then `<core parent>/mods`; installation destination only |
| `patchLibPath` | `PATCHLIB_API`, then `<core parent>/mods/PatchLib/jars/PatchLibAPI.jar` |
| `modFolderName` | Metadata `id`; a single folder name |
| `modJar` | First metadata `jars` entry; must be a declared relative JAR path |
| `javaRelease` | `8`; main Java bytecode/API target |
| `patchJavaRelease` | `17`; separate patch source target |
| `patchSourceIncludes` | For PatchLib mods: `**/patch/**/*.java,**/*Patch.java`; otherwise empty |
| `mainSourceExcludes` | Additional source patterns not compiled into the main output |
| `compileJars` | Extra compile/test JAR paths; also includes `libs/*.jar` automatically |
| `extraPackageIncludes` | Additional runtime include patterns, relative to the project or `resources/` |
| `seedJar` | None; explicitly opt into merging an existing JAR for partial-source projects |
| `seedExcludes` | Class/resource patterns to omit from an optional seed JAR |

Patch sources are excluded from the main compile, compiled against main outputs, and merged into the output
JAR. If your mod does not use this separation, set `patchSourceIncludes=` and select the appropriate
`javaRelease` for the entire mod. Main sources must not require classes that are only compiled afterward as patches.

Extra compile JARs are not automatically distributed: add runtime dependencies to metadata `jars` when they
must ship with the mod. Dependencies on other mods still need their compile JARs configured; the script does
not guess arbitrary installed mod folder names or download libraries.

The standard Gradle `test` task supports test frameworks once their dependencies/framework configuration are
provided. It does **not** automatically execute Java classes with `main` methods, such as Wingman's focused tests.

## Wingman's overlay

Wingman is not a fully sourced mod. Its `gradle.properties` retains the required original `jars/Wingman.jar`,
specific excluded sources/classes, patch selection, and historical `Wingman` distribution folder. Freshly
compiled main/patch classes take precedence over seed classes. Obsolete classes are removed through
`seedExcludes`; signature files are removed because merging changes the JAR contents.

For a fully sourced mod, leave `seedJar` unset. No existing main JAR or Wingman classes are then required.
This is not a universal build for data-only mods, Kotlin projects, multiple separately compiled main JARs,
or arbitrary resource layouts; those need their own small adaptations.
