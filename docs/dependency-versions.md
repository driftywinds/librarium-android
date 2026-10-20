# Dependency version matrix — librarium-android

Target toolchain: **AGP 9.4.1**, **Kotlin 2.4.21**, **Gradle 9.8.1**, **Compose BOM 2026.09.00**.
Research date: **2026-10-19**. Every version below was resolved from live repository metadata
(`maven-metadata.xml`) and/or official release notes; every claim is traceable to the URL in the
"Source" column. Nothing here is invented or copied from memory.

---

## (a) Ready-to-paste `gradle/libs.versions.toml`

This block is valid TOML and is a drop-in replacement for `gradle/libs.versions.toml`. All accessors
already referenced by `app/build.gradle.kts` (`libs.androidx.core.ktx`, `libs.androidx.compose.material3`,
`libs.plugins.kotlin.compose`, …) keep their names. Library entries use `module = "group:artifact"`;
the previous file used the equivalent `group` + `name` pair — both are valid and produce identical
accessors, so either style works.

```toml
# gradle/libs.versions.toml
# Verified 2026-10-19. See docs/dependency-versions.md for sources and rationale.

[versions]
agp = "9.4.1"
kotlin = "2.4.21"
ksp = "2.3.12"

coreKtx = "1.19.1"
appcompat = "1.8.0"
lifecycle = "2.11.0"
activityCompose = "1.13.0"
composeBom = "2026.09.00"
navigation = "2.10.2"

room = "2.8.5"
datastore = "1.2.1"
securityCrypto = "1.1.0"

hilt = "2.60.1"

camerax = "1.6.2"
mlkitBarcode = "17.3.0"
exifinterface = "1.4.2"

retrofit = "3.0.0"
okhttp = "5.5.0"
serialization = "1.11.0"
coroutines = "1.11.0"
coil = "3.6.3"

junit = "4.13.2"
androidxJunit = "1.3.0"

[libraries]
# --- AndroidX core -----------------------------------------------------------
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }

# --- Lifecycle / Activity ----------------------------------------------------
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }

# --- Compose (BOM-managed: no version on individual artifacts) ---------------
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-graphics = { module = "androidx.compose.ui:ui-graphics" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }

# --- Navigation --------------------------------------------------------------
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }

# --- Persistence -------------------------------------------------------------
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "securityCrypto" }

# --- Dependency injection ----------------------------------------------------
hilt-android = { module = "com.google.dagger:hilt-android", version.ref = "hilt" }
hilt-compiler = { module = "com.google.dagger:hilt-android-compiler", version.ref = "hilt" }

# --- CameraX / ML Kit --------------------------------------------------------
androidx-camerax-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
androidx-camerax-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camerax-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camerax-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
mlkit-barcode-scanning = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkitBarcode" }
androidx-exifinterface = { module = "androidx.exifinterface:exifinterface", version.ref = "exifinterface" }

# --- Networking / serialization ---------------------------------------------
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { module = "com.squareup.retrofit2:converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }

# --- Image loading -----------------------------------------------------------
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
coil-network-okhttp = { module = "io.coil-kt.coil3:coil-network-okhttp", version.ref = "coil" }

# --- Test --------------------------------------------------------------------
junit = { module = "junit:junit", version.ref = "junit" }
androidx-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

> **`org.jetbrains.kotlin.android` is intentionally absent.** AGP 9.x applies Kotlin support built in;
> applying that plugin in a module now fails. See section (d). Any existing
> `kotlin-android = { id = "org.jetbrains.kotlin.android", ... }` plugin entry and every
> `alias(libs.plugins.kotlin.android)` must be deleted.

---

## (b) Resolved version table

Two repository hosts are used: **G** = Google Maven (`https://dl.google.com/dl/android/maven2/...`),
**C** = Maven Central (`https://repo1.maven.org/maven2/...`). Google-hosted artifacts are not on
Central and vice versa.

| Artifact | Version | Release date | Source |
| --- | --- | --- | --- |
| `com.android.tools.build:gradle` (AGP) | 9.4.1 | 2026-09 (9.4.0 line) | G `com/android/tools/build/gradle/maven-metadata.xml`; notes https://developer.android.com/build/releases/agp-9-4-0-release-notes |
| `org.jetbrains.kotlin` (KGP) | 2.4.21 | — | C `org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml` |
| `com.google.devtools.ksp:symbol-processing-gradle-plugin` | **2.3.12** | 2026-09-09 | C `com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml` (`lastUpdated 20260909175426`); notes https://api.github.com/repos/google/ksp/releases/tags/2.3.12 |
| `androidx.core:core-ktx` | **1.19.1** | 2026-09-23 | G `androidx/core/core-ktx/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/core |
| `androidx.activity:activity-compose` | **1.13.0** | 2026-03-11 | G `androidx/activity/activity-compose/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/activity |
| `androidx.lifecycle:lifecycle-runtime-compose` | **2.11.0** | 2026-06-17 | G `androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/lifecycle |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | **2.11.0** | 2026-06-17 | same as above |
| `androidx.navigation:navigation-compose` | **2.10.2** | 2026-09-23 | G `androidx/navigation/navigation-compose/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/navigation |
| `androidx.compose:compose-bom` | 2026.09.00 | 2026-09 | G `androidx/compose/compose-bom/maven-metadata.xml`; mapping https://developer.android.com/develop/ui/compose/bom/bom-mapping |
| ↳ `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling`, `ui-tooling-preview`, `foundation` | 1.12.1 (via BOM) | 2026-09-09 | BOM mapping page (selector `2026.09.00`) |
| ↳ `androidx.compose.material3:material3` | 1.4.0 (via BOM) | 2026-10-07 | BOM mapping page |
| ↳ `androidx.compose.material:material-icons-extended` | **1.7.8** (via BOM) | 2025-02-12 | BOM mapping page + G `androidx/compose/material/material-icons-extended/maven-metadata.xml` (`1.7.8` is the newest published version, `lastUpdated 20250212180149`) |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` | **2.8.5** | 2026-09-09 | G `androidx/room/room-runtime/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/room |
| `androidx.datastore:datastore-preferences` | **1.2.1** | 2026-03-11 | G `androidx/datastore/datastore-preferences/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/datastore |
| `androidx.security:security-crypto` | **1.1.0** | 2026-09-09 | G `androidx/security/security-crypto/maven-metadata.xml` (`lastUpdated 20250730170037`); https://developer.android.com/jetpack/androidx/releases/security — page lists `1.1.0` as the sole Stable release, RC/beta/alpha all `-`, "last updated September 09, 2026" |
| `androidx.camera:camera-core` / `-camera2` / `-lifecycle` / `-view` | **1.6.2** | 2026-08-26 | G `androidx/camera/camera-core/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/camera |
| `androidx.appcompat:appcompat` | **1.8.0** | 2026-08-12 | G `androidx/appcompat/appcompat/maven-metadata.xml`; https://developer.android.com/jetpack/androidx/releases/appcompat |
| `com.google.mlkit:barcode-scanning` | 17.3.0 | — | G `com/google/mlkit/barcode-scanning/maven-metadata.xml` |
| `androidx.exifinterface:exifinterface` | 1.4.2 | 2025-12-03 | G `androidx/exifinterface/exifinterface/maven-metadata.xml` (`lastUpdated 20251203180015`; `1.4.2` is the newest published, no newer pre-release) |
| `com.google.dagger:hilt-android` | **2.60.1** | 2026-07-06 | C `com/google/dagger/hilt-android/maven-metadata.xml` (`lastUpdated 20260706203408`) |
| `com.google.dagger:hilt-android-compiler` | **2.60.1** | 2026-07-06 | C `com/google/dagger/hilt-android-compiler/maven-metadata.xml` (`lastUpdated 20260706203413`) |
| `com.google.dagger.hilt.android` (plugin marker) | **2.60.1** | 2026-07-06 | C `com/google/dagger/hilt/android/com.google.dagger.hilt.android.gradle.plugin/maven-metadata.xml` (**not** on Google Maven — that path returns 404) |
| `com.squareup.retrofit2:retrofit` | **3.0.0** | 2025-05-15 | C `com/squareup/retrofit2/retrofit/maven-metadata.xml` |
| `com.squareup.retrofit2:converter-kotlinx-serialization` | **3.0.0** | 2025-05-15 | C `com/squareup/retrofit2/converter-kotlinx-serialization/maven-metadata.xml` (`lastUpdated 20250515163418`) |
| `com.squareup.okhttp3:okhttp` | **5.5.0** | 2026-08-16 | C `com/squareup/okhttp3/okhttp/maven-metadata.xml` |
| `com.squareup.okhttp3:logging-interceptor` | **5.5.0** | 2026-08-16 | C `com/squareup/okhttp3/logging-interceptor/maven-metadata.xml` (`lastUpdated 20260816161722`) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** | 2026-04-09 | C `org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml`; release notes: "based on Kotlin 2.3.20" |
| `org.jetbrains.kotlin.plugin.serialization` (marker) | **2.4.21** | 2026-10-08 | C `org/jetbrains/kotlin/plugin/serialization/org.jetbrains.kotlin.plugin.serialization.gradle.plugin/maven-metadata.xml` (`lastUpdated 20261008081235`; list is `… 2.4.21-RC`, `2.4.21`, `2.5.0-Beta1` → `2.4.21` is the newest stable) |
| `org.jetbrains.kotlin.plugin.compose` (marker) | **2.4.21** | — | same coordinates pattern as above (compose plugin ships with Kotlin) |
| `io.coil-kt.coil3:coil-compose` | **3.6.3** | 2026-09-18 | C `io/coil-kt/coil3/coil-compose/maven-metadata.xml` |
| `io.coil-kt.coil3:coil-network-okhttp` | **3.6.3** | 2026-09-18 | C `io/coil-kt/coil3/coil-network-okhttp/maven-metadata.xml` (`lastUpdated 20260918073824`) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | **1.11.0** | 2026-05-08 | C `org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml` (notes: "Kotlin was updated to 2.2.20") |
| `junit:junit` | 4.13.2 | 2021-02-13 | C `junit/junit/maven-metadata.xml` (unchanged, still newest) |
| `androidx.test.ext:junit` | **1.3.0** | — | G `androidx/test/ext/junit/maven-metadata.xml` (`1.3.0` newest stable; `1.4.0-alpha01` is the only newer entry) |

### Android SDK platform / build tools

| Item | Value | Source |
| --- | --- | --- |
| Newest **stable** platform | `platforms;android-37.2` | https://dl.google.com/android/repository/repository2-4.xml — `<api-level>37.2</api-level>`, `<codename></codename>` **empty**, `channel-0`, archive `platform-37.2_r01.zip` (67,956,472 B) |
| Other stable platforms present | `platforms;android-37.1`, `platforms;android-37.0`, `platforms;android-36.1`, `platforms;android-36` | same file |
| Preview platforms (do **not** use) | `platforms;android-37.2-beta3` (codename `DEV`), `-beta2`, `-beta1` (codename `CinnamonBun`) | same file |
| Build tools | **36.0.0** (min = default) | AGP 9.4.0 compatibility table ("SDK Build Tools | 36.0.0 | 36.0.0"); locally installed under `.tooling/android-sdk/build-tools/36.0.0` |

Note: the platform repository XMLs contain no `build-tools` packages at all, so the build-tools
version cannot be read from `repository2-4.xml`; the AGP release-notes table is the authoritative
source and it says `36.0.0` for both minimum and default on AGP 9.0, 9.3 and 9.4.

### AGP 9.4.1 environment requirements (verbatim from the compatibility table)

> "The maximum API level that Android Gradle plugin 9.4 supports is API level 37."

| | Minimum version | Default version |
| --- | :---: | :---: |
| Gradle | 9.6.0 | 9.6.0 |
| SDK Build Tools | 36.0.0 | 36.0.0 |
| NDK | N/A | 28.2.13676358 |
| JDK | 17 | 17 |

Source: https://developer.android.com/build/releases/agp-9-4-0-release-notes (page footer:
"Last updated 2026-10-16 UTC").

**Gradle 9.8.1 in `gradle/wrapper/gradle-wrapper.properties` satisfies the 9.6.0 minimum.**
The wrapper currently pins `gradle-9.8.1-bin.zip`, which is above the AGP 9.4 minimum — fine, but it
is *not* a version Google has tested against AGP 9.4 (they test 9.6.0). If you hit a confusing
Gradle-level failure, dropping the wrapper to `9.6.x` is the first thing to try.

**JDK 17 is the minimum and default** for AGP 9.4. `compileOptions { sourceCompatibility = VERSION_17 }`
plus `jvmTarget = JVM_17` in the existing module is therefore correct; no JDK 21 requirement exists.

---

## (c) KSP ↔ Kotlin compatibility (the one genuinely uncertain pairing)

### What is verified

1. **KSP is no longer tied to the Kotlin compiler version.** From the `2.3.0` release body
   (https://api.github.com/repos/google/ksp/releases/tags/2.3.0, published `2025-10-22T16:26:19Z`,
   `prerelease: false`):
   > "KSP version is no longer tied to the Kotlin compiler version"
   > "KSP is no longer a compiler plugin (like KSP1). It now operates as a more standalone tool built
   > on stable compiler APIs"
   > "KSP now has fewer limitations on the Kotlin version in use"

2. **The version scheme changed at 2.3.0.** Metadata for
   `com/google/devtools/ksp/com.google.devtools.ksp.gradle.plugin/maven-metadata.xml` shows the tail of
   the version list as `2.2.21-2.0.5`, `2.3.0`, `2.3.1`, … `2.3.12` — a decoupled, plain-semver line.
   Latest/release = `2.3.12`, `lastUpdated 20260909175426`.

3. **Latest stable KSP = 2.3.12**, published `2026-09-09T18:27:29Z`, `prerelease: false`
   (https://api.github.com/repos/google/ksp/releases/tags/2.3.12). Its body includes:
   > "Update minimum supported Android Gradle Plugin (AGP) version to 8.12.0 (#3149)"

   plus the backing-fields / explicit-backing-fields feature, `registerProcessorForNewFeatures`, and
   `KSVisitorNext`. Nothing in the 2.3.12, 2.3.11 or 2.3.10 notes names a Kotlin version.

4. **The official KSP requirements table still caps at KGP 2.3.x.** From
   https://kotlinlang.org/docs/ksp-overview.html (page dated **30 September 2026**):
   > "The latest KSP version, 2.3.10, supports the following dependency versions:"

   | | Supported |
   | --- | --- |
   | Kotlin Gradle plugin | **2.2.10 – 2.3.x** |
   | Android Gradle plugin | 8.12.0 or later |
   | Gradle | 8.13 or later. For AGP 9.0 or later, use Gradle 9.x. |
   | JDK | 17 or later |

### What is not verified

**No published source states that KSP 2.3.12 supports Kotlin 2.4.21.** The docs page above is the most
recent authoritative statement and it says `2.3.x`. Kotlin `2.4.21` is one minor line beyond the
documented ceiling. KSP 2.3.12 was released 2026-09-09, *before* the docs page was last updated
(2026-09-30), and the docs page still names `2.3.10` as "the latest KSP version" — i.e. the table is
maintained by hand and lags the release stream. That is evidence the table is stale, not evidence that
2.4.x works.

**Recommendation:** use Kotlin `2.4.21` + KSP `2.3.12`. The decoupling at 2.3.0 ("fewer limitations on
the Kotlin version in use", "built on stable compiler APIs") makes a 2.4.x Kotlin plausible, and no
release note forbids it. If KSP fails at configuration or codegen time, the **documented-compatible
fallback pair is Kotlin `2.3.21` + KSP `2.3.12`** (satisfies the `2.2.10 – 2.3.x` row and is above the
`8.12.0` AGP floor). Dropping Kotlin to 2.3.21 also keeps `kotlin-compose` and `kotlin-serialization`
plugin ids usable, because all three are published under the same Kotlin version.

Additional constraint to be aware of: **AGP 9.0 declares a runtime dependency on KGP 2.2.10**
(compatibility table row "Kotlin Gradle plugin (KGP) | 2.2.10 | 2.2.10", accompanied by the section
"Runtime dependency on Kotlin Gradle plugin"). Using a newer KGP requires adding it explicitly to the
buildscript classpath in the top-level build file; downgrading KGP below 2.0.0 requires opting out of
built-in Kotlin.

---

## (d) AGP 9.x build-file requirements

### Hard rules

1. **Do not apply `org.jetbrains.kotlin.android`.** AGP 9 has built-in Kotlin support
   (`android.builtInKotlin` default flipped from `false` → `true`). Applying the Kotlin Android plugin
   fails immediately. Verbatim error strings from
   https://developer.android.com/build/migrate-to-built-in-kotlin:
   - `Failed to apply plugin 'org.jetbrains.kotlin.android'. > Cannot add extension with name 'kotlin', as there is an extension already registered with that name.`
   - `Failed to apply plugin 'com.jetbrains.kotlin.android' > The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.`

   Opt-out per module is possible via `enableKotlin = false`, or by applying `com.android.built-in-kotlin`
   "using the same version as your Android Gradle plugin", but for a new app neither is needed.

2. **`build.gradle.kts` (Kotlin DSL) is still fully supported.** Nothing about AGP 9 removes it.

3. **`android.kotlinOptions { }` must be migrated.** It is one of the four documented migration steps
   (remove `kotlin-android`; migrate `kotlin-kapt`; migrate `android.kotlinOptions{}`; migrate
   `kotlin.sourceSets{}`). Use the top-level `kotlin { compilerOptions { } }` block instead — which is
   exactly what `app/build.gradle.kts` already does.

4. **`buildFeatures` defaults flipped to `false`** for `resValues`, `shaders`, `aidl` and `renderscript`.
   Re-enable explicitly if needed. `compose = true` is still required and still works.

5. **`minSdk` / `targetSdk` / `compileSdk` take the version-spec form.** The 9.4.1 DSL reference
   (`https://developer.android.com/reference/tools/gradle-api/9.4/...`) documents
   `compileSdk { version = release(33) { minorApiLevel = 1; sdkExtension = 18 } }`, and
   `CompileSdkBetaSpec` / `MinCompileSdkSpec` / `CompileSdkSpec.beta(...)` are "Added in 9.4.1".
   Plain `compileSdk = 37` also still parses in `build.gradle.kts`.

6. **Removed DSL in 9.0** (do not use): `AndroidSourceSet.jni`, `AndroidSourceSet.wearAppConfigurationId`
   / `wearAppConfigurationName`, `BuildType.isRenderscriptDebuggable`, `DependencyVariantSelection`
   (→ `DependencySelection`), `Installation.installOptions(String)`, `PostProcessing`,
   `ProductFlavor.setDimension`, `Variant.minSdkVersion` / `maxSdkVersion` / `targetSdkVersion`
   (→ `minSdk` / `maxSdk` / `targetSdk`), density-based split APKs, and parameterization of
   `CommonExtension`.

7. **New in 9.4:** `android.newDsl.optOut=:module-name` in `gradle.properties` temporarily opts a module
   out of the new Variant API (mandatory from AGP 10). Not needed for a new single-module app.

### Annotated example (`app/build.gradle.kts`)

```kotlin
plugins {
    alias(libs.plugins.android.application)
    // NO org.jetbrains.kotlin.android / alias(libs.plugins.kotlin.android) — AGP 9 provides Kotlin.
    alias(libs.plugins.kotlin.compose)        // org.jetbrains.kotlin.plugin.compose
    alias(libs.plugins.kotlin.serialization)  // org.jetbrains.kotlin.plugin.serialization
    alias(libs.plugins.ksp)                   // only when Room/Hilt codegen is used
    alias(libs.plugins.hilt)                  // com.google.dagger.hilt.android
}

android {
    namespace = "com.fireball1725.librarium"          // still required, still here
    compileSdk = 37                                   // AGP 9.4 supports up to API 37

    defaultConfig {
        applicationId = "com.fireball1725.librarium"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true                                // required for Compose
        buildConfig = true
        // resValues / aidl / shaders / renderscript now default to false — enable if used
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // NOTE: no android.kotlinOptions { } block — removed; configure Kotlin below.
}

kotlin {
    compilerOptions {                                 // replaces android.kotlinOptions { }
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

ksp {
    // arg("room.schemaLocation", "$projectDir/schemas")  // when Room is wired up
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // … unchanged; use ksp(libs.androidx.room.compiler) and ksp(libs.hilt.compiler), not kapt
}
```

`kapt` should be replaced by KSP — the migration doc lists "migrate `kotlin-kapt`" as a required step,
and both Room 2.8.5 and Hilt 2.60.1 support KSP.

---

## (e) Not resolved / judgement calls

1. **KSP 2.3.12 vs Kotlin 2.4.21 — unproven.** See (c). The only documented ceiling is `2.3.x`. This is
   the single largest build risk in the matrix.
2. **`platforms;android-37.2` vs `compileSdk = 37`.** AGP 9.4 supports "up to API level 37". The newest
   *stable* platform package is `android-37.2` (a minor-API-level variant, not a preview — empty
   codename, `channel-0`). Whether AGP 9.4.1 accepts `compileSdk = 37` plus `minorApiLevel = 2` / the
   `android-37.2` package specifically, or only the base `android-37` platform, is not stated anywhere I
   could fetch. Safe choice: `compileSdk = 37`; the local SDK only has `platforms/android-36` installed,
   so the platform package must be installed before building either way.
3. **`material-icons-extended` is a dead artifact.** The BOM `2026.09.00` maps it (and
   `material-icons-core`) to **1.7.8** while the rest of Compose is `1.12.1`. Metadata confirms `1.7.8`
   is the last version ever published (`lastUpdated 20250212180149`). It still resolves and works, but it
   is ~5 minor versions behind the Compose runtime it links against. Recommendation: keep the BOM-managed
   (version-less) entry only if you actually need the extended icon set; otherwise drop it and use
   `Icons.Filled/Outlined` from `material-icons-core` (also frozen) or copy the handful of vectors you
   need. Do **not** pin a version — that would break BOM alignment.
4. **`security-crypto` is frozen.** `1.1.0` is the newest and only stable release; release notes show no
   RC/beta/alpha and no activity since. Google has publicly deprecated `EncryptedSharedPreferences`-style
   usage in favour of platform APIs, but the *library* is not marked deprecated on that page. Treat it as
   stable-but-unmaintained; prefer a DataStore + Keystore implementation for new code.
5. **Retrofit converters.** `converter-kotlinx-serialization`, `converter-scalars` and `converter-moshi`
   all exist at `3.0.0` (Central metadata; `converter-kotlinx-serialization` has exactly
   `2.10.0, 2.11.0, 2.12.0, 3.0.0`). Retrofit `3.0.0` dates from 2025-05-15 and has had no release since —
   it is stable, not abandoned as far as metadata shows, but it is a year old.
6. **`appcompat` is probably unnecessary.** A pure-Compose app does not need it. Included in the catalog
   at `1.8.0` for completeness; delete if unused.
7. **`hilt-navigation-compose` was deliberately omitted** — I did not fetch its metadata, and including an
   unverified version would be invention. Add it only after checking
   `androidx/hilt/hilt-navigation-compose/maven-metadata.xml`.
8. **Gradle 9.8.1 vs AGP-tested 9.6.0.** The wrapper is newer than what AGP 9.4.1 documents. Works in
   principle; not tested by Google.
9. **`kotlinx-serialization-json` 1.11.0 is built against Kotlin 2.3.20** while the toolchain is 2.4.21.
   Forward compatibility is normal for this library (it is compiler-plugin-driven), but the runtime is one
   Kotlin line behind.
10. **`converter-moshi`/`converter-scalars` are irrelevant here** unless you switch away from kotlinx
    serialization; listed only because the request asked which ones exist.

---

## (f) Risks — ordered by likelihood of breaking the build

| # | Risk | Why it could fail | Mitigation |
| --- | --- | --- | --- |
| 1 | **KSP 2.3.12 + Kotlin 2.4.21** | Official support table says KGP `2.2.10–2.3.x`; 2.4.21 is outside it. Symptom: `ksp` configuration failure, or "unsupported Kotlin metadata version" during codegen. | Fall back to Kotlin `2.3.21` (documented-compatible pair with KSP 2.3.12). |
| 2 | **Residual `org.jetbrains.kotlin.android`** | AGP 9 hard-fails at configuration: "Cannot add extension with name 'kotlin', as there is an extension already registered with that name." The current `libs.versions.toml` *does* declare this plugin. | Delete the `[plugins]` entry and every `alias(...)` of it. |
| 3 | **`android.kotlinOptions {}` left anywhere** | Removed in AGP 9; the `android { }` block no longer has it → unresolved reference at configure time. | Use top-level `kotlin { compilerOptions { } }`. |
| 4 | **`material-icons-extended` at 1.7.8 against Compose 1.12.1** | Metadata/ABI mismatch in a Compose runtime that changed substantially between 1.7 and 1.12; possible `NoSuchMethodError`/`NoClassDefFoundError` at runtime, not compile time. | Omit it, or accept the frozen version and test icon rendering first. |
| 5 | **`compileSdk` 37.2 resolution** | AGP 9.4 max is API 37; the `37.2` minor-level package may not be accepted by 9.4.1. | Use `compileSdk = 37` and install `platforms;android-37`. |
| 6 | **Gradle 9.8.1 vs documented 9.6.0** | Untested combination; Gradle-internal API drift can surface as obscure plugin failures. | Drop wrapper to `9.6.x` if configuration fails early. |
| 7 | **KSP AGP floor 8.12.0 / KGP runtime pin 2.2.10** | AGP 9.0 pulls KGP 2.2.10 onto the classpath; a newer KGP needs explicit `classpath(...)` in the root build file. Wrong resolution → Kotlin/Compose plugin version mismatch. | Add KGP explicitly to the root buildscript classpath when using 2.4.21. |
| 8 | **Hilt 2.60.1 requires AGP ≥ 9.0.0** | Fine here (9.4.1), but note the floor was raised in 2.59.1. | None needed. |
| 9 | **`security-crypto` 1.1.0 unmaintained** | No newer release since 2025-07; possible `targetSdk 37` behaviour gaps. | Consider DataStore + Keystore. |
| 10 | **Retrofit 3.0.0 is ~17 months old** | Not a failure mode by itself; flagged because it is the oldest stable in the matrix. | None. |
