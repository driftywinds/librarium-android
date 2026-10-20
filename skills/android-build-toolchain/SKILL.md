---
name: android-build-toolchain
description: Build, compile-check, or debug the Librarium Android Gradle build on this Windows machine. Use when you need to run gradle, resolve a compilation error, or set up the toolchain from scratch.
---

# Android build toolchain (this machine)

This machine had **no** git, JDK, Android SDK or Gradle when the repo was
created. They are now installed, but **none of them are on `PATH`**, and the
toolchain deliberately lives *inside* the workspace so it is disposable.

## Environment facts

| Tool | Location | Notes |
| --- | --- | --- |
| JDK 17 (Temurin) | `<absolute path to your JDK 17 install>` | Installed via winget. |
| Git | `C:\Program Files\Git\cmd\git.exe` | Installed via winget. |
| Android SDK | `<workspace>\.tooling\android-sdk` | `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`. |
| Gradle 9.8.1 | `<workspace>\.tooling\gradle-9.8.1` | Used to generate the wrapper; the build itself should use `gradlew`. |
| `local.properties` | repo root | `sdk.dir` points at the bundled SDK. **Git-ignored.** |

`.tooling/` is git-ignored on purpose: it is machine-local, several GB, and
rebuildable. Never commit it.

## Running a build

Always drive the build through the wrapper, and always set `JAVA_HOME` and
`ANDROID_HOME` first — nothing is on `PATH`:

```powershell
$env:JAVA_HOME = '<absolute path to your JDK 17 install>'
$env:ANDROID_HOME = "$PWD\.tooling\android-sdk"
./gradlew.bat assembleDebug
```

`local.properties` already carries `sdk.dir`, so AGP finds the SDK even without
`ANDROID_HOME`; setting both is belt-and-braces.

## Sandbox rules that will waste your time if you forget them

Two failures here look like code problems but are **environment** problems:

1. **`SetNamedSecurityInfoW failed (Win32 5)` on the first command.**
   The workspace root lost its `WRITE_OWNER` right, so DSH cannot provision the
   workspace grant. Every tool call fails until it is repaired. Fix with the
   `diagnose-windows-sandbox-acl` skill, run **once, unconfined**.
2. **`schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS` or
   `The SSL connection could not be established` from `curl`/`Invoke-WebRequest`.**
   This is the sandbox blocking outbound TLS, *not* a broken URL. Downloads
   (SDK, Gradle, Gradle dependencies on first run) need the command run with
   `sandbox_permissions: danger-full-access`. Anything that resolves
   `dl.google.com`, `repo1.maven.org` or `services.gradle.org` from inside a
   Gradle build needs the wider access too.

Gradle's first build downloads the entire dependency graph into
`~/.gradle`. If a build dies with TLS/connection errors rather than compiler
errors, re-run it unconfined before suspecting the code.

## Reading a failed build

Long Gradle output is truncated to its tail by the tool, which usually keeps the
part you need (`BUILD FAILED` + the first compiler error). To get the full log,
redirect it to a file inside the workspace and `grep` that:

```powershell
./gradlew.bat assembleDebug *> build-log.txt
```

Useful narrowing flags while iterating:

| Goal | Command |
| --- | --- |
| Compile Kotlin only, no packaging | `./gradlew.bat compileDebugKotlin` |
| Just the Kotlin compile task, with full stacktrace | `./gradlew.bat compileDebugKotlin --stacktrace` |
| See what is actually resolvable | `./gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` |
| Stop on the first failure | `./gradlew.bat assembleDebug --no-daemon` |

`compileDebugKotlin` is the fast loop: it skips resource merging, D8, and
packaging, so it surfaces source errors in a fraction of the time.

## Toolchain version policy

Versions are pinned in `gradle/libs.versions.toml` and were **verified against
real repository metadata** — see [`../../docs/dependency-versions.md`](../../docs/dependency-versions.md)
for each version's source URL.

Rules when changing them:

- Never pick an `alpha`, `beta`, `rc` or `dev` version for a dependency that has
  a stable line. The Android ecosystem publishes previews constantly and they
  are not what anyone wants in a diff.
- **Kotlin, KSP and the serialization plugin move together.** KSP is built
  against one exact Kotlin compiler version. Bumping Kotlin without bumping KSP
  to the matching release breaks the annotation processor with a confusing
  "incompatible plugin" error. Check the compatibility section of the
  dependency-versions doc before touching either.
- Compose library versions come from the BOM, not from individual entries. Add
  a Compose dependency to the catalog **without** a version and let the BOM
  decide, or the build silently mixes incompatible Compose versions.

## Regenerating the wrapper

The wrapper is committed (`gradle/wrapper/gradle-wrapper.jar` — note the
`!gradle/wrapper/gradle-wrapper.jar` un-ignore rule in `.gitignore`; the global
`*.jar`-style ignores would otherwise swallow it). To regenerate after changing
the Gradle version:

```powershell
$env:JAVA_HOME = '<absolute path to your JDK 17 install>'
& "$PWD\.tooling\gradle-9.8.1\bin\gradle.bat" wrapper --gradle-version 9.8.1
```
