# ONNX Runtime for this app

`app/libs/onnxruntime-android-1.30.0-pwnall.aar` is ONNX Runtime 1.30.0 built by `build.sh` in this
folder, not the AAR Microsoft publishes on Maven Central. Two reasons:

- **Telemetry.** From 1.29 the official AAR compiles in Microsoft's 1DS telemetry client: native
  code that posts device and model details to `mobile.events.data.microsoft.com`, a
  `TelemetryInitializer` provider that starts it at every launch, and the `INTERNET` and
  `ACCESS_NETWORK_STATE` permissions it needs. A manifest rule can only switch the start off; a
  build with `--no_telemetry` has nothing to switch, and `OnnxRuntimeBuildTest` fails if the
  libraries ever carry the collector again.
- **Size.** The official AAR carries every operator and three accelerators the app never uses
  (NNAPI, XNNPACK, WebGPU). This build keeps only the operators `ops.config` names.

| `libonnxruntime.so` | Maven 1.30.0 | this build |
|---|---|---|
| arm64-v8a | 32,990,480 B | 14,056,272 B |
| armeabi-v7a | 23,311,344 B | 8,994,696 B |

## Adding a model

A model that needs an operator this build left out fails on the phone at session creation with
"Failed to find kernel" — and only there: the JVM tests load the full desktop runtime from Maven.
So, before shipping a new `.onnx`:

1. Put it, with every other `.onnx` the app runs — `find app/src/main/assets -name '*.onnx'` lists
   them: `ocr/PP-OCRv6_small_det.onnx`, `ocr/PP-OCRv6_small_rec.onnx`,
   `ocr/eslav_PP-OCRv5_mobile_rec.onnx`, `photo_v3.onnx` and `chat/reader.onnx` — in one folder, and run
   `ORT_DISABLE_TELEMETRY=1 python3.12 make_ops_config.py <onnxruntime checkout> <folder>` (it needs
   `onnxruntime==1.30.0`, `onnx` and `flatbuffers`). The config is the operators of the raw graphs,
   plus those of the same graphs after ORT's own level-"all" optimisation for ARM — the fused
   `com.microsoft` kernels the phone actually runs — plus the same operator families at opsets
   11–18 as headroom. The committed config is made from the three OCR models and a quantised
   Multilingual-MiniLM standing in for the chat encoder; `photo_v3.onnx` and `chat/reader.onnx`
   run on it unchanged: every operator of their raw graphs is in the config, and
   `ShippedModelsTest` creates both sessions on a device. The next config is made from all five
   files. The JVM test `OnnxOpsConfigTest` holds every `.onnx` under the assets to both halves of
   this step: its raw operators are in `ops.config` at its opset, and the list above names it.

2. If `ops.config` changed, run `build.sh` (about an hour) and commit the new AAR with it. Commit
   with them the two lines the script ends on, in the table below, and the new `ops.config`.
3. Run the instrumented tests on a device or emulator — `LabDocumentReaderTest` creates every OCR
   session through this runtime, `ShippedModelsTest` the chat reader's and the food network's —
   and a release build on a phone (R8 can break ONNX Runtime's JNI lookups, which find Java types
   by name; `verifyPlayReleaseKeeps` and `verifySideloadReleaseKeeps` guard the keep rule for them).

## What is committed

| | |
|---|---|
| `app/libs/onnxruntime-android-1.30.0-pwnall.aar` | SHA-256 `6caf71e350777865c70be845a6b4f0a8c3d74c6d1c23214cf0ffc61f922629e7` |
| built | from the tag `v1.30.0`, commit `f2c39fe` |
| `ops.config` | SHA-256 `7878491bb967c0a2f12dc4e6b4d6a44b2bac1745a219aeca690a5943f864dd5d` |

The commit is as the AAR's own libraries name it (`strings libonnxruntime.so | grep
git-commit-id`): seven digits, all that build recorded. `build.sh` refuses a `v1.30.0` that is
some other commit, and prints the whole id it built from.

The libraries compile in the path of every source file, for their error messages, and so the
directory they were built in. `neutral_paths.py`, which `build.sh` runs on the AAR it copies,
replaces that directory with `/ortbuild/` padded with `./` to the same length: no offset in a
library moves, and the paths still name the same files of the source tree.

`ops.config` stays byte for byte the file that build read: the table above names it by its
SHA-256, and with a changed comment it would be another file. That is why its header names the
models it was made from — the OCR models and the stand-in — and not all five that ship.
`make_ops_config.py` writes the header from the files it is given, so the next config names its own.

`build.sh` takes what it downloads by what it is: the JDK archive by its SHA-256, cmake and ninja
as one release each with the wheel's SHA-256, ONNX Runtime's dependencies by the SHA-1 `deps.txt`
pins, and ONNX Runtime by that commit. `aapt2` comes from Google's Maven repository through
Gradle, which checks no figure written here.

## The next rebuild

Not built: an AAR newer than 1.30.0. By its release notes, ONNX Runtime 1.30.1 repairs two faults
in memory that 1.30.0 has: a heap overflow where the
storage of a packed tensor of elements narrower than a byte is reused for a larger tensor (#32611),
and a read out of bounds at session creation where an input of a `Loop` or `Scan` body is also an
initializer. Neither can be reached with the five models the app ships: no tensor of theirs is
narrower than a byte, none has a `Loop` or a `Scan`, and `ops.config` builds neither kernel.
`OnnxOpsConfigTest` holds the models to that for as long as the AAR is 1.30.0. Every model comes
out of the APK's own assets, so a graph made to trip the runtime would take a changed APK.

The rebuild takes a Mac with Apple silicon (below) and about an hour:

1. The release. Maven Central has no 1.30.1 of the desktop runtime the JVM tests load, and both
   sides have to be one release: so `ORT_TAG=v1.32.0` in `build.sh`, and `ORT_COMMIT` the commit
   that tag is, all forty digits of it.
2. `ops.config` made again from all five models with `onnxruntime==1.32.0` (step 1 of "Adding a
   model").
3. `build.sh`, then: the AAR it leaves in `app/libs` under its new name, with the old one deleted;
   that name and the same version of `com.microsoft.onnxruntime:onnxruntime` in
   `app/build.gradle.kts`; the version in this file's commands and in the table above, with the two
   lines the script ends on. `OnnxRuntimeBuildTest` fails until the four agree.
4. Step 3 of "Adding a model": the instrumented tests on a device and a release build on a phone.
5. `noModelHasWhatTheRuntimeOfTheAarMishandles` leaves `OnnxOpsConfigTest`.

On another machine the script needs that platform's JDK 17 archive and its SHA-256, the hashes of
that platform's cmake and ninja wheels, its `aapt2`, and the SDK and NDK paths named: all of it is
written for a Mac with Apple silicon only.

## What `build.sh` works around

- `build_aar_package.py` forces vcpkg with Microsoft's internal asset cache; the script removes
  those two flags and the build takes its sources from `cmake/deps.txt`.
- Every dependency is fetched with curl into `mirror/`, which ORT reads first, each checked against
  the SHA-1 `deps.txt` pins, rather than by CMake's own downloader.
- `--skip_tests` still compiles ORT's unit tests, and the reduced operator set leaves some of their
  constants unused, which `-Werror` refuses; `settings.json` turns the tests off.
- ORT's Java build runs AGP 7.4.2, whose `jlink` step fails under JDK 21; the script fetches a
  pinned Temurin 17. An outbound filter can keep that fresh binary off the network, so `aapt2` is
  fetched into the shared Gradle cache first with Android Studio's JDK, and the Android
  packaging runs `assembleRelease` instead of `build` — the Java bindings' own tests and lint would
  each need more downloads.
