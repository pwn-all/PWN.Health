# Building, signing and releasing PWN.Health

How the app is built from a clean clone, how what the build downloads is held to its hashes, and
how a release is signed and published. What the app does and how it works is in
[DESIGN.md](DESIGN.md).

## Toolchain

The versions Dependabot raises are not repeated here: each is one line in the file the table names.

| Tool | Version | Note |
|---|---|---|
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | wrapper committed, distribution checksum pinned |
| Android Gradle Plugin | `build.gradle.kts` | **AGP 9 has Kotlin support built in** — applying `org.jetbrains.kotlin.android` is an error |
| Kotlin | `build.gradle.kts` | set by the version of `org.jetbrains.kotlin.plugin.compose`, which is applied on its own and brings the Kotlin Gradle plugin of the same version; AGP alone would ask for an older one |
| KSP | `build.gradle.kts` | versioned on its own, not as `<kotlin>-<ksp>` |
| Room | `app/build.gradle.kts` | KSP |
| Compose BOM | `app/build.gradle.kts` | |
| JDK | 21 | not pinned by the build files: 21 is what CI's `setup-java` installs and the releases are built with; Android Studio's bundled JBR is fine |
| SDK packages | `platforms;android-37.1`, `build-tools;37.0.0` | |

To build from a clean clone you need JDK 21 and an Android SDK holding the two packages above; point
Gradle at the SDK with `ANDROID_HOME` or with a `local.properties` holding `sdk.dir=…` (the file is
git-ignored). Nothing else is installed by hand: the wrapper fetches Gradle, ONNX Runtime is the AAR
committed in `app/libs/` (built by `tools/onnxruntime-android/build.sh`), and the models and the map
are committed under `app/src/main/assets`. The first build needs the network for Gradle and the
dependencies (Google's Maven, Maven Central and the Gradle Plugin Portal); after that `--offline`
works. A build produces one APK per ABI,
`arm64-v8a` and `armeabi-v7a` only, so it installs on an ARM device or an arm64 emulator image ([DESIGN.md](DESIGN.md) §18)
and on nothing x86.

Every jar and AAR the build downloads is held to a SHA-256 in `gradle/verification-metadata.xml`:
the libraries, the Gradle plugins, and what the plugins fetch for themselves — the Kotlin compiler,
KSP, Lint, `aapt2`, ktlint and detekt. Gradle refuses one that differs or is not listed, so a
changed version is two files, the second written by Gradle:

```sh
./gradlew --write-verification-metadata sha256 help    # every configuration the build declares
# after a plugin or tool bump (AGP, Kotlin, KSP, ktlint, detekt), also what the tasks fetch:
./gradlew --write-verification-metadata sha256 check --rerun-tasks
./gradlew --write-verification-metadata sha256 assembleRelease --rerun-tasks
python3 -I .github/scripts/aapt2_platforms.py           # after an AGP bump, see below
```

On Dependabot's Gradle branches `dependabot-gradle.yml` does this itself. It runs Gradle with a
read-only token and no secrets, then, in a job that runs no Gradle, takes back that one file and
nothing else, refuses it unless it only adds to the one before, as Gradle reads it
(`.github/scripts/verification_additions.py`), pushes it to the branch and asks for the branch's
build. After a Gradle bump the wrapper scripts and jar stay the old release's, which runs the new
one; `./gradlew wrapper` by hand brings them up to date. By hand, read the file's diff before committing it with the bump: it should
be the artifacts of what was changed and nothing else. Gradle only adds lines; the old version's can
be deleted by hand. `aapt2` is one jar per platform and Gradle records the one for the machine it
runs on, so after an AGP bump `DependencyVerificationTest` asks for the other two, which Google's
Maven has beside it (`…/com/android/tools/build/aapt2/<version>/aapt2-<version>-{linux,osx,windows}.jar`)
with the `.sha256` that `aapt2_platforms.py` copies in. The file is
written on Linux: if Gradle refuses an artifact on macOS or in Android Studio, the first command
with the refused task in place of `help` records it. Not
covered: POM and module files, sources and javadoc, and the Android runtime jars Robolectric
downloads for itself.

Bumping the version: `versionName` is the release (`2.0`, `2.1`, …) and `versionCode` rises by one
with each, 2.0 being 79. Raise both in `app/build.gradle.kts` and open a
`## <versionName>` section at the top of `CHANGELOG.md`: `ChangelogVersionTest` holds the three
together and stays red until the section has its first `- **…**` entry. The code has to be above
every code already published — Android will not install an APK over one carrying the same or a
higher code.

Built artifacts live in `releases/health-<version>/` inside the project, untracked — several hundred
megabytes a version, rebuilt from the tagged commit and the keystore (nothing checks that two builds
of one tag come out byte for byte the same). The sideload build produces one APK per architecture,
`arm64-v8a` and `armeabi-v7a`; x86 and x86_64 are packaged nowhere: the ONNX Runtime built in
`tools/onnxruntime-android` exists for the two ARM ABIs only (14.1 MB and 9.0 MB), and the Intel
libraries other dependencies bring are excluded, because they exist for emulators and Chromebooks.
The two release commands are run separately on purpose: AGP will not build an .aab while it is also
building several APKs, and asking for both in one invocation quietly gives the APK both ABIs.

```sh
# Two distribution flavors: `play` (the .aab for the Play Console) and `sideload`
# (a directly-installable APK). Same app either way; both carry zero network permissions.
./gradlew testSideloadDebugUnitTest assembleSideloadDebug   # tests + installable APK
./gradlew assembleSideloadRelease                           # minified sideload APKs, one per ABI; signed when local credentials exist
./gradlew bundlePlayRelease                                 # the .aab that goes to Play Console — run on its own, see below
```

To build an optimised, signed phone-test APK with an experimental reader package:

```sh
./gradlew -PrequireReleaseSigning \
  -PchatReaderTestAssets="/path/to/reader-package/assets" \
  assembleSideloadRelease
```

The override installs separately as `com.pwnall.health.modeltestrelease`, with a `-modeltest`
version suffix, and permits the package's experimental manifest. Its tokenizer, schema,
calibration and ONNX must travel together. Without the override, the build ships the release reader
in `app/src/main/assets/chat`, whose `reader-manifest.json` says `"smoke": false`; the loader holds
the tokenizer, the schema and the calibration to the manifest's SHA-256 values and the model to its
byte count.
`ChatModelSelected.kt` lets smoke weights load only in a build made with the override, debug or
`.modeltestrelease`.

A signed release in one paste — both flavours, copied into `releases/health-<version>/` under
their published names, failing instead of producing an unsigned artifact when `~/keys` is absent.
It wants JDK 21 on `JAVA_HOME`; on macOS, Android Studio's JBR is at
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`:

```sh
V=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts) && ./gradlew -PrequireReleaseSigning bundlePlayRelease && ./gradlew -PrequireReleaseSigning assembleSideloadRelease && mkdir -p releases/health-$V && cp app/build/outputs/bundle/playRelease/app-play-release.aab releases/health-$V/health-$V-play.aab && for a in arm64-v8a armeabi-v7a; do cp app/build/outputs/apk/sideload/release/app-sideload-$a-release.apk releases/health-$V/health-$V-sideload-$a.apk; done && ls -l releases/health-$V
```

## Quality gates and dependency updates

Quality gates run from Gradle: `ktlintCheck`, `detekt`, `testSideloadDebugUnitTest`,
`lintSideloadDebug` — the flavors name the variants, and the build is kept at zero warnings. Only
sideload has unit tests: the flavors share every source file and differ by one manifest line
(`FlavourDifferenceTest` holds that), and the tests that read a merged manifest are handed all four
variants', debug and release. ktlint and detekt run from their command-line jars, not Gradle
plugins.

Versions are raised by Dependabot (`.github/dependabot.yml`): the Gradle wrapper, plugins and
libraries and the workflows' actions once a month, each a few days after its release (AndroidX,
which Dependabot cannot date, without the wait), and a library at once for a security advisory. A Gradle pull request costs two builds. Its pull requests
go into `main` with nobody to click Merge: `automerge.yml` merges one once the workflow that tests its
kind of manifest has passed on its head commit and nothing else that ran there failed. It looks when
a run ends and once an hour, since GitHub wakes nothing when the build `dependabot-gradle.yml` asks
for ends. On a Gradle branch CI first writes the checksums the new versions need
(`dependabot-gradle.yml`, under Toolchain above). A pull request that stays red stays open. Updates of the actions
are merged only once `AUTOMERGE_TOKEN` is set (`automerge.yml` says what it is). The versions held
back on purpose are listed in `.github/dependabot.yml` under `ignore`, each with its reason.

## Signing

The release key lives outside the checkout, in `~/keys`:

| | |
|---|---|
| Store | `~/keys/pwnall-release.jks` — PKCS12, `chmod 600` |
| Password | `~/keys/pwnall-release.password` — one line, same password for store and key |
| Alias | `pwnall` |
| Key | RSA 4096, signed `SHA384withRSA` |
| Valid until | 18 January 2054 |
| Certificate | `~/keys/pwnall-release_certificate.pem` |

Outside the project directory on purpose. A key kept beside the source is one `git add -f`, one
stray tarball or one shared worktree away from being published, and whoever holds it can sign
updates to this app forever. `.gitignore` already refuses `*.jks` and `*.password`, but a rule that
only has to be right every time is not a boundary; a different directory is.

Both files present ⇒ release builds are signed. Either missing ⇒ the build still runs and produces
an **unsigned** APK, which is what you want on a machine that has no business holding the key. CI
is such a machine on every push to a branch and every pull request from a fork: the workflow names the key's
secrets only in the job that builds a version tag, which nothing else starts (GitHub hands a runner
every secret its job names, and code running there can read it from memory), and `CiWorkflowTest`
holds it to that. On the tag it passes `-PrequireReleaseSigning`, so that a publishing run fails
loudly instead of shipping an artifact nobody can install or upgrade. The release page gets the two
signed sideload APKs and R8's mapping for them, packed.

The key's fingerprint is the app's identity on Play: `SHA-256
96:95:4A:82:2B:8A:96:C3:E2:4B:2D:81:17:90:E5:E9:87:6D:C5:C8:F0:8F:BD:87:8A:31:C7:3A:C8:BB:7D:63`.
An upload signed by any other key is rejected, and a sideload APK signed by another key cannot
upgrade an installed one — it has to be uninstalled first, taking its database with it. Losing
`~/keys` therefore ends the update path for every install already out there, so it is backed up
somewhere that is neither this repo nor this machine.

## Releasing

`git tag v<versionName> && git push origin v<versionName>` is the whole release. The `signed` job of
`.github/workflows/build.yml` runs `check assembleRelease -PrequireReleaseSigning` with the key
restored from two Actions secrets, `KEYSTORE_B64` (the `.jks`, base64) and `KEYSTORE_PASSWORD`;
the `release` job then publishes the two signed sideload APKs and the packed R8 mapping on a GitHub
release whose text is the version's section of `CHANGELOG.md`. A tag whose version has no section
fails before anything is published.
