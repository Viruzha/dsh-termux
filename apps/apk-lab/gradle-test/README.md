# Gradle + AGP on Termux aarch64 (Android 16 / SDK 36, no root)

## VERDICT: WORKS

Gradle + Android Gradle Plugin build real, signed APKs on this device.
`compileSdk 34` / `minSdk 24` / `targetSdk 34`, plain `android.app.Activity`,
**no x86_64 binaries executed at any point**.

## Verified versions

| Component | Version |
|---|---|
| Gradle | `1:9.6.0` (Termux package) |
| AGP | `9.4.0` |
| Java | OpenJDK `21.0.11` (Termux) |
| compileSdk / minSdk / targetSdk | 34 / 24 / 34 |
| aapt2 | Termux `2.19` (aarch64) |
| aidl | Termux `13.0.0.6-23` (aarch64 ELF) |
| Build-tools (faked) | `36.0.0` |

## Build

```sh
cd /data/data/com.termux/files/home/dsh-workspace/apk-lab/gradle-test
gradle assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

## Timing (7 cores, no Gradle build cache)

| Scenario | Wall clock |
|---|---|
| True clean build, cold (daemon restarted) | **10.1 s** |
| True clean build, warm daemon | **3.6-4.0 s** |
| Warm no-op rebuild | **2.5 s** |
| AndroidX appcompat clean build (first run, incl. artifact download) | 1 m 21 s |

Note: with `org.gradle.caching=true` a "clean" build reports 2 s because 21/33
tasks are served from the build cache. Use `--no-build-cache` for honest numbers.

## The five non-obvious things that were required

1. **`android.aapt2FromMavenOverride`** in `gradle.properties`, pointing at
   `/data/data/com.termux/files/usr/bin/aapt2`. Accepted (prints an
   "experimental" warning). AGP's aapt2 *binary daemon* protocol works fine
   with the Termux aapt2.

2. **AGP 9.4.0 requires build-tools >= 36.0.0** and refuses anything lower
   ("The specified Android SDK Build Tools version (34.0.0) is ignored, as it is
   below the minimum supported version (36.0.0)"). The faked build-tools
   directory must be *complete* or AGP fails with
   `Installed Build Tools revision 36.0.0 is corrupted`.
   Specifically `BuildToolInfo.isValid()` compares the `package.xml`
   `<revision>` against `Pkg.Revision` in `source.properties`; a major-only
   `<major>36</major>` does **not** equal `36.0.0`. Use
   `<major>36</major><minor>0</minor><micro>0</micro>`.

3. **A real aarch64 `aidl` exists**: `apt-get install -y aidl`.
   No stub or placeholder is needed.

4. The platform directory needs more than `android.jar`:
   `source.properties`, `build.prop`, `package.xml`, and
   **`core-for-system-modules.jar`** (without it `JdkImageTransform` fails).
   `source.properties` must NOT contain `AndroidVersion.CodeName`, and
   `package.xml` must have an EMPTY `<codename></codename>`, otherwise
   `AndroidVersion.isPreview()` becomes true and the platform hash turns into
   `android-UpsideDownCake` instead of `android-34`.

5. **Stale Gradle daemon.** After fixing SDK metadata, builds kept failing with
   `Failed to find target with hash string 'android-34'` because the daemon held
   a cached `AndroidTargetManager`. `gradle --stop` cleared it. A direct sdklib
   probe confirmed the platform loads correctly all along. **Always `gradle --stop`
   after changing anything under `sdk/`.**

## Layout

```
gradle-test/
  settings.gradle build.gradle gradle.properties local.properties
  app/                                  # the verified minimal example (no 3rd-party deps)
  androidx-test/                        # stretch goal: appcompat 1.7.0, also builds
  sdkprobe/                             # sdklib diagnostic (prints parsed targets/hashes)
  sdk/                                  # LOCAL faked SDK root (apk-lab/sdk is never written to)
    platforms/android-34/               # android.jar (symlink), build.prop,
                                        #   core-for-system-modules.jar, source.properties, package.xml
    build-tools/36.0.0/                 # authentic layout, aarch64 binaries substituted
```

`sdk/platforms/android-34/android.jar` is a **symlink** to the pre-existing
`/data/data/com.termux/files/home/dsh-workspace/apk-lab/sdk/platforms/android-34/android.jar`
so nothing under `apk-lab/sdk/` was modified.

## SDK skeleton provenance

- `platform-34-ext7_r03.zip` from `dl.google.com` -> authentic `source.properties`,
  `build.prop`, `core-for-system-modules.jar`.
- `build-tools_r36_linux.zip` from `dl.google.com` -> authentic layout,
  `source.properties`, `core-lambda-stubs.jar`, `runtime.properties`,
  `renderscript/`, `NOTICE.txt`.
- `package.xml` files are hand-written (Google's archives ship without them;
  `sdkmanager` normally generates them).
- x86_64 binaries kept only for existence checks (`dexdump`, `llvm-rs-cc`, `lld`,
  `*-ld`); they are never invoked because the project has no native/RenderScript code.
- `aapt2`, `aidl`, `zipalign`, `apksigner`, `d8`, `aapt` are symlinks to Termux's
  aarch64 binaries; `lib/{d8,apksigner}.jar` symlink to `/usr/share/java/`.

## Gotcha that cost real time

`cp -r <authentic-build-tools>/. <dir>` where `<dir>` already contained symlinks
(e.g. `aapt2 -> /usr/bin/aapt2`) made `cp` **write through the symlinks**, silently
overwriting Termux's `aapt2`, `aapt`, `aidl`, `zipalign`, `apksigner`, `d8` with
x86_64 binaries. Symptoms: `error: "..." is for EM_X86_64 (62) instead of EM_AARCH64 (183)`.
Recovery: `apt-get install --reinstall -y aapt2 aapt aidl apksigner d8`.
**Never `cp` into a directory that contains symlinks** — extract into an empty dir first.

## Not exercised

- AIDL compilation, NDK/native builds, RenderScript, release signing, lint.
- `./gradlew` wrapper (not generated — the system `gradle` is the verified path).
