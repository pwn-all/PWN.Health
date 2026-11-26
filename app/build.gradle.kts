import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder
import org.gradle.process.CommandLineArgumentProvider
import java.time.Duration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Outside the checkout on purpose. A signing key that lives beside the source is one `git add -f`
// or one tarball of the project directory away from being published, and the identity of every
// future update goes with it. ~/keys is chmod 700 and the store inside it chmod 600, so a copy of
// the repo carries none of it and a build on a machine without the key simply goes unsigned.
val keysDir = File(System.getProperty("user.home"), "keys")
val releaseKeystore = File(keysDir, "pwnall-release.jks")
val releaseKeystorePassword = File(keysDir, "pwnall-release.password")
val hasReleaseSigning = releaseKeystore.isFile && releaseKeystorePassword.isFile

// A local test package overlays assets and installs beside the usual app.
val chatReaderTestAssets = providers.gradleProperty("chatReaderTestAssets").orNull?.let(::file)
if (chatReaderTestAssets != null) {
    require(File(chatReaderTestAssets, "chat/reader-manifest.json").isFile) {
        "chatReaderTestAssets must contain a complete chat reader package"
    }
}

// A local build without the keystore is meant to fall through to an unsigned APK. A build whose
// output is published as the official download for a version tag is not: CI passes
// -PrequireReleaseSigning so missing signing material fails the run instead of shipping an APK
// nobody can install or upgrade.
if (!hasReleaseSigning && providers.gradleProperty("requireReleaseSigning").isPresent) {
    error("Release signing required but ${releaseKeystore.path} / ${releaseKeystorePassword.path} are missing")
}

android {
    namespace = "com.pwnall.health"
    // Android 17's first minor release: Compose 1.13 compiles against no platform older than 37.1.
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    // The build tools CI installs (.github/workflows/build.yml), named here: left to the Android
    // Gradle plugin's default, 36.0.0 under 9.3.3, the build would read those and leave the 37.0.0
    // that CI installs unused. CiWorkflowTest holds the two together.
    buildToolsVersion = "37.0.0"
    // The merged resources and the test manifest on the unit-test classpath, which Robolectric
    // needs to inflate the app's own strings, drawables and theme: for the screen shots, and for the
    // unit tests that compose a screen or post a notification.
    testOptions.unitTests.isIncludeAndroidResources = true

    defaultConfig {
        // Android identifies an install by this string, so an installed com.pwnall.fitness (the
        // app's earlier package name) will not upgrade to this: it is a different identity, and Play
        // sees a different listing. Carry a phone's existing record over with the app's own
        // export/import.
        applicationId = "com.pwnall.health"
        minSdk = 33 // POST_NOTIFICATIONS is a runtime permission from 33; one notification path
        targetSdk = 37
        // versionCode must rise for every tagged build: Android refuses to install an APK over one
        // that already carries the same code.
        versionCode = 79
        versionName = "2.0"
        // The runner for app/src/androidTest: what only a device can run, MigrationTest first among
        // it. The dependencies block says what that source set costs and why it is worth paying.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Whether a chat model ships at all: its manifest in the assets. With
        // none, the chat page asks for no reader, so nothing of a model is made, opened or read on any
        // opening of the chat, and the chat reads with its rules alone. ChatModelAssetsTest holds it to
        // the assets.
        buildConfigField(
            "boolean",
            "CHAT_MODEL_SHIPS",
            file("src/main/assets/chat/reader-manifest.json").isFile.toString(),
        )
        buildConfigField("boolean", "CHAT_MODEL_EXPERIMENTAL", "false")
    }

    // Phones only. libonnxruntime.so weighs 20–35 MB per ABI, and the two Intel ones exist for
    // emulators and Chromebooks — neither of which anybody wears on a walk. This is where they
    // leave the .aab as well: `abiFilters` would say the same thing but AGP refuses to hold it
    // beside the ABI splits below, and the splits are what gives the sideload build one APK per
    // architecture. Emulators on Apple silicon are arm64 and unaffected.
    packaging {
        jniLibs {
            excludes += setOf("lib/x86/*.so", "lib/x86_64/*.so")
        }
    }

    // One APK per architecture rather than one carrying both: a phone downloads the native code it
    // can run, not every architecture's copy of it. The Play build splits itself inside the .aab,
    // so this is what the sideload download gets instead — and AGP refuses to build a bundle while
    // it is also building several APKs (b/402800800), so the splitting stands aside exactly when a
    // bundle is what was asked for. Ask for both in one invocation and the APK comes back holding
    // both architectures; ask separately, as the release commands in docs/BUILDING.md do, and each is right.
    splits {
        abi {
            // "Asked for" includes Gradle's camel-case abbreviations — bPR runs bundlePlayRelease —
            // so besides names starting with the word, treat a typed name whose first camel-case
            // word abbreviates "bundle" as a bundle build too.
            isEnable = gradle.startParameter.taskNames.none { typed ->
                val name = typed.substringAfterLast(':')
                val firstWord = name.takeWhile { !it.isUpperCase() }
                name.startsWith("bundle", ignoreCase = true) ||
                    (firstWord.isNotEmpty() && "bundle".startsWith(firstWord))
            }
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildFeatures {
        compose = true
        // For two fields of ours: CHAT_MODEL below, the chat model's switch, set in the build
        // per flavour, and CHAT_MODEL_SHIPS above. The version and the
        // debuggable bit are read from the package wherever the app shows them.
        buildConfig = true
    }

    // Every Lint check, on every source set and on the dependencies, at error severity. A warning
    // that cannot fail a build is a warning nobody reads.
    lint {
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        checkTestSources = true
        // Two of the opt-in checks are advice this project should not take.
        //
        // SyntheticAccessor was written for pre-Java-11 bytecode, where a private member touched
        // across a nested-class boundary cost a generated bridge method. JVM 11 nestmates removed
        // the bridge, and R8 removes what is left; taking the advice would mean widening the
        // visibility of 139 genuinely private members to save nothing.
        //
        // DuplicateStrings wants one resource wherever two happen to read the same today. A nav
        // label and a screen title that both say "Sensors" are different strings that agree by
        // coincidence, and merging them means one cannot be retranslated without the other.
        disable += setOf("SyntheticAccessor", "DuplicateStrings")
        // Three more ask the network for the newest release of every dependency, of Gradle and of
        // AGP, and fail a tree nobody touched on the day one is published. Dependabot proposes those
        // releases instead (.github/dependabot.yml), each in a pull request the build has to pass.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    // AGP's default writes the list of this build's dependencies into the signing block of every
    // APK, encrypted to a key Google Play holds. In an APK that is downloaded and never passes
    // through Play that is a block only Google can read, in an app whose promise is that it tells
    // nobody anything, and F-Droid's scanner refuses an APK that carries it. The bundle keeps its
    // copy: Play is where that file goes, and Play reads it to warn of a library with a known fault.
    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        generateLocaleConfig = true
        // The chat and photo models are mapped directly from the APK; OCR stays compressed. The
        // world map, its relief and its forests deflate their own levels and tiles, and are read by
        // seeking to one: stored as they are, a seek is a seek rather than inflating everything
        // before it. The towns are one small zlib stream, already as small as deflate makes them.
        noCompress += listOf("reader.onnx", "photo_v3.onnx", "world.map", "world.relief", "world.forest", "world.towns")
    }

    // Room's exported schemas ride along as the instrumentation APK's assets. MigrationTestHelper
    // reads `com.pwnall.health.StepsDb/25.json` and its successors from there to build a database at
    // an old version and to hold the migrated one against the newer. Room writes them under
    // app/schemas (room.schemaLocation below), so this is the same directory, not a copy that can
    // fall behind.
    sourceSets.getByName("androidTest").assets.directories.add("schemas")

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword.readText().trim()
                keyAlias = "pwnall"
                keyPassword = releaseKeystorePassword.readText().trim()
            }
        }
    }

    // Two distributions of the same app: `play` builds the .aab the Play Console takes, `sideload`
    // the .apk that installs directly. One thing differs: only sideload declares location all the
    // time (src/sideload/AndroidManifest.xml), which Google Play admits only with an approved
    // permissions declaration, and so only it offers the Work screen's start by location. Otherwise
    // no extra features on either side, no store client in the APK, and both stay network-free.
    // The names are load-bearing beyond Gradle: the build commands of docs/BUILDING.md, tools/screens-test.sh, the
    // CI artifact glob and the released file names are all written in terms of them.
    //
    // sideload is the default: the variant `check` runs Android Lint on and Android Studio opens
    // with. It is the one with the unit tests (see `hostTests` below), and Lint reads the test
    // sources through the variant that has them.
    //
    // CHAT_MODEL is the chat's on-device reader (ChatModelMode): OFF loads nothing and the chat
    // reads with its rules alone, SHADOW reads and keeps the readings without the chat using them
    // (for comparing the model's readings with the rules'), ON lets the chat use them. ON in both:
    // the chat is taught to use what the model reads, and uses it only once a model that is not a
    // smoke-test package ships in the assets with its manifest. The v1 package is shared by both
    // flavours; its routing_languages list enables topic and entity proposals in all 16 languages.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            buildConfigField("String", "CHAT_MODEL", "\"ON\"")
        }
        create("sideload") {
            isDefault = true
            buildConfigField("String", "CHAT_MODEL", "\"ON\"")
        }
    }

    buildTypes {
        getByName("debug") {
            if (chatReaderTestAssets != null) {
                applicationIdSuffix = ".modeltest"
                versionNameSuffix = "-modeltest"
                buildConfigField("boolean", "CHAT_MODEL_EXPERIMENTAL", "true")
                sourceSets.getByName("debug").assets.directories.add(chatReaderTestAssets.absolutePath)
            }
        }
        release {
            if (chatReaderTestAssets != null) {
                applicationIdSuffix = ".modeltestrelease"
                versionNameSuffix = "-modeltest"
                buildConfigField("boolean", "CHAT_MODEL_EXPERIMENTAL", "true")
                sourceSets.getByName("release").assets.directories.add(chatReaderTestAssets.absolutePath)
            }
            isMinifyEnabled = true
            isShrinkResources = true
            // Default rules plus one file, and that file holds exactly three keeps — ONNX Runtime's, the
            // Glance widget classes' and their tap callbacks' constructors — each of which a release build
            // breaks without. Room and Compose ship their own consumer rules and need nothing here.
            //
            // An -assumevalues rule pinning Build.VERSION.SDK_INT to the minSdk floor would change the APK
            // by zero bytes: AGP already hands R8 the floor, and the classes the Play Console's
            // deprecated-API report names — androidx.activity.EdgeToEdgeApi26 through Api29 — survive
            // because EdgeToEdgeApi35, the one this app does run, inherits from them.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// The keeps in proguard-rules.pro are load-bearing and invisible: delete one and every gate here
// still passes, while the release APK dies on its first inference — the crash is inside ONNX
// Runtime's JNI, which finds its Java types by name, and it aborts the process rather than
// throwing — or the widgets break, because Glance tells them apart by class name and builds each
// tap's callback by reflection. So the build reads R8's own mapping back and refuses a release
// that shrank, merged or renamed any of them. Costs a file read; catches a class of bug no unit
// test on the JVM can see.
val widgetSources = fileTree("src/main/java") { include("**/*.kt") }
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val name = variant.name
        val titled = name.replaceFirstChar(Char::uppercase)
        // R8's mapping as AGP publishes it, not a path under build/: AGP 9 writes the file in
        // outputs/mapping from a later Compose-mapping task, and a check that read that path on the
        // strength of running after R8 would find nothing whenever the build stopped between the two,
        // turning a failure there (a signing failure on CI) into a second, bogus one. The artifact
        // brings the producing tasks with it, so the check waits for them and is skipped if they fail.
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        // A local name for the sources: the action below would otherwise reach for the script's own
        // property, and a lambda holding the script is one the configuration cache cannot store.
        val sources = widgetSources
        val verify = tasks.register("verify${titled}Keeps") {
            inputs.file(mapping).withPropertyName("mapping")
            inputs.files(sources).withPropertyName("sources")
            doLast {
                // One pass over the mapping, some 130 MB, a line at a time rather than held whole in
                // the daemon's heap. A block is a class's header line and the indented member lines and
                // R8's `#` notes under it; the header of a class kept as itself names it twice.
                var tensorInfoKept = false
                val keptAsThemselves = mutableSetOf<String>()
                val constructible = mutableSetOf<String>()
                var block: String? = null
                mapping.get().asFile.forEachLine { line ->
                    if (line.startsWith(" ") || line.startsWith("#")) {
                        if ("void <init>()" in line && line.endsWith("-> <init>")) block?.let(constructible::add)
                    } else {
                        val tensorInfo = line.startsWith("ai.onnxruntime.TensorInfo ->") &&
                            line.endsWith("ai.onnxruntime.TensorInfo:")
                        tensorInfoKept = tensorInfoKept || tensorInfo
                        val cls = line.substringBefore(" -> ").removePrefix("com.pwnall.health.")
                        block = cls.takeIf { line == "com.pwnall.health.$cls -> com.pwnall.health.$cls:" }
                        block?.let(keptAsThemselves::add)
                    }
                }
                check(tensorInfoKept) {
                    "R8 renamed or removed ai.onnxruntime.TensorInfo in $name: the native " +
                        "layer looks it up by name, and the release build aborts on its first " +
                        "inference without an exception anyone can catch. Restore the keep in " +
                        "proguard-rules.pro."
                }
                // Every widget class and tap callback the sources declare, by the mapping's block
                // for it: the header names the class kept as itself, and the member lines and R8's
                // `#` notes under it hold a callback's kept constructor.
                fun declared(base: String) = sources.flatMap { file ->
                    Regex("""class (\w+) : $base\b""").findAll(file.readText()).map { it.groupValues[1] }.toList()
                }
                val widgets = declared("GlanceAppWidget")
                val merged = widgets.filter { it !in keptAsThemselves }
                check(widgets.isNotEmpty() && merged.isEmpty()) {
                    "R8 renamed or merged the widgets $merged in $name (found ${widgets.size}): " +
                        "Glance files each widget under its class name, and one kind's update " +
                        "would draw its face into the other's. Restore the GlanceAppWidget keep " +
                        "in proguard-rules.pro."
                }
                val callbacks = declared("ActionCallback")
                val hollow = callbacks.filter { it !in constructible }
                check(callbacks.isNotEmpty() && hollow.isEmpty()) {
                    "R8 left no constructor in the widget callbacks $hollow in $name (found " +
                        "${callbacks.size}): Glance makes each one by reflection, and every " +
                        "tap on its widget would do nothing. Restore the ActionCallback keep in " +
                        "proguard-rules.pro."
                }
            }
        }
        val r8 = "minify${titled}WithR8"
        tasks.matching { it.name == r8 }.configureEach { finalizedBy(verify) }
        // Hooked by name, which is AGP's to change: under another name the check would follow nothing
        // and every release would pass it, so the build stops instead.
        gradle.projectsEvaluated {
            check(r8 in tasks.names) { "No task $r8 to run verify${titled}Keeps after: hook it to R8's new name." }
        }
    }
}

// The desktop ONNX Runtime the JVM tests load (testImplementation below) has Microsoft's 1DS
// telemetry compiled in from 1.29 on, and nothing like the app's manifest strips it: every test
// run would queue the machine, the model hashes and the timings for upload. The runtime reads this
// variable before it initialises; OnnxOcrTest fails if it is missing.
tasks.withType<Test>().configureEach {
    environment("ORT_DISABLE_TELEMETRY", "1")
    // Away from UTC, across a date line and in Turkish: build machines and CI runners are often UTC in the
    // C locale, where an offset of nought and an "i" that upper-cases to "I" hide what a phone does not
    // share. The whole suite passes so; TestEnvironmentTest keeps it set.
    systemProperty("user.timezone", "Pacific/Auckland")
    systemProperty("user.language", "tr")
    systemProperty("user.country", "TR")
    // Where the screen shots land when -Pscreens asks for them; absent, ScreenShots.kt skips them.
    if (providers.gradleProperty("screens").isPresent) {
        systemProperty("screens.out", rootProject.file("ScreensTest/onboarding").absolutePath)
        // Robolectric reaches into the JDK's file descriptors to fake Android's shared memory.
        jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
    } else {
        // Not even started without -Pscreens: Robolectric would build its sandbox for each one
        // only for the shot to skip itself.
        filter { excludeTestsMatching("*ShotsTest") }
        // Five minutes of tests on a slow machine. One that hangs would otherwise hold a CI runner
        // for the six hours GitHub allows a job.
        timeout = Duration.ofMinutes(30)
    }
    chatReaderTestAssets?.let { systemProperty("chatReader.testAssets", File(it, "chat").absolutePath) }
    // What the APK stores uncompressed, for ChatModelAssetsTest: the chat's model has to be among it.
    systemProperty("android.noCompress", android.androidResources.noCompress.joinToString("\n"))
    // What the guard tests open straight from the checkout. Gradle reruns a task only for inputs it
    // was told of: without these, a commit that changed the policy in docs/ and not its copy in the
    // assets, or the changelog and not the version, would leave this task up to date, and the test
    // written for exactly that commit would not run. The sources are named as well, because several
    // guards read comments, which are not in the classes the task already watches.
    inputs.files(
        rootProject.files("CHANGELOG.md", "index.html", "docs/privacy-policy.html", "docs/REPORT_COMPARISONS.md"),
        rootProject.fileTree("tools") {
            include("*.sh", "onnxruntime-android/README.md", "onnxruntime-android/ops.config")
            include("onnxruntime-android/build.sh")
        },
        // DocsMapTest: the map, the top level of docs/ it must name, the record held to the reader, and
        // the licence with the README that names it.
        rootProject.fileTree("docs") { include("*") },
        rootProject.files("LICENSE", "README.md"),
        // DependencyVerificationTest and CiWorkflowTest: where AGP's version is set, the hashes of what
        // the build downloads, and the workflows with Dependabot's settings.
        rootProject.files("build.gradle.kts"),
        rootProject.file("gradle/verification-metadata.xml"),
        rootProject.fileTree(".github"),
        file("build.gradle.kts"),
        fileTree("libs"),
        fileTree("schemas"),
        fileTree("src") { include("*/AndroidManifest.xml", "main/java/**", "main/res/**", "main/assets/**") },
        // DeviceTestsTest reads the device tests it cannot run.
        fileTree("src/androidTest/java"),
    ).withPropertyName("checkoutFilesTheTestsRead").withPathSensitivity(PathSensitivity.RELATIVE)
}

// `check` compiles the device tests it cannot run. Lint reads app/src/androidTest and detekt and
// ktlint hold its style, and none of the three resolves a name: a migration renamed in Data.kt or a
// DAO method given another parameter would leave that source set unable to build with every gate
// green. Compiled and no more: running them takes a device,
// connectedSideloadDebugAndroidTest, and docs/play-release-checklist.md asks for that run before a
// release. What needs no device — Room on SQLite, the migrations, a composed screen's semantics —
// runs in the unit tests on Robolectric instead, in this same `check`.
tasks.named("check") { dependsOn("compileSideloadDebugAndroidTestKotlin") }

// The unit tests run once, on sideload, and not once for each flavour. The flavours are one program:
// no source set, dependency or build field of their own, and one line of manifest between them
// (FlavourDifferenceTest holds that), so a second run would tell nothing the first does not, at a
// cost of four minutes of `check`'s ten. What does differ per variant is the merged manifest, and the tests
// that read it are handed every variant's below. Give play its run back by deleting this line on the
// day the flavours differ in code.
androidComponents {
    beforeVariants(selector().withFlavor("distribution" to "play")) {
        it.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

// [NoNetworkPermissionTest] reads the *merged* manifests — the files that ship, everything a
// dependency contributed included. Handed the artifacts themselves, Gradle builds the manifests
// before the test task and the test reads the current ones or fails outright. A manifest looked
// for under build/intermediates would be whatever an earlier build left there: one edited in this
// commit would be tested against the previous commit's copy, and a tree with no build at all would
// have nothing to read.
//
// All of them, both flavours and both build types. Only debug variants have unit tests, so a test
// that read its own variant's manifest would never see a release one, and those are the two that
// ship: a library declared for release builds alone could add INTERNET to them with every test green.
androidComponents {
    onVariants { variant ->
        val merged = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        // Local names: a lambda that held `variant` is one the configuration cache cannot store.
        val name = variant.name
        tasks.withType<Test>().configureEach {
            inputs.file(merged).withPropertyName("mergedManifest${name.replaceFirstChar(Char::uppercase)}")
            jvmArgumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("-Dmerged.manifest.$name=${merged.get().asFile.absolutePath}")
                },
            )
        }
    }
}

// One `./gradlew check` runs Android Lint, detekt, ktlint and the unit tests; compiler warnings
// fail the build so they cannot pile up. `extraWarnings` adds the opt-in diagnostics — unused
// expressions and parameters, redundant nullable types — that the default warning set stays quiet
// about.
kotlin {
    compilerOptions {
        allWarningsAsErrors = true
        extraWarnings = true
        // Room's generated DAO is compiled by this same task and trips these two on almost every
        // method it writes. They are the only cosmetic diagnostics in the extraWarnings set, and
        // ktlint already governs visibility modifiers in hand-written code, so dropping them costs
        // nothing and keeps the ~20 substantive extra checks — unused parameters and expressions,
        // redundant nullable types — failing the build.
        freeCompilerArgs.addAll(
            "-Xwarning-level=REDUNDANT_VISIBILITY_MODIFIER:disabled",
            "-Xwarning-level=CAN_BE_VAL:disabled",
        )
    }
}

// Room's generated code is compiled by KSP, not by the task above, and has its own switch.
ksp {
    allWarningsAsErrors = true
    // Room writes one JSON schema per database version here, and the files are committed. They are
    // what [RoomSchemaTest] holds the hand-written migration chain to, and what old databases are
    // built from to run it: by MigrationRunTest in the unit tests and by MigrationTest on a device.
    // Without them the migrations carrying every health record forward are verified by nothing.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// No Java sources today. The gate is here so that the day one appears it arrives held to the same
// standard as the Kotlin, instead of quietly bypassing every warning setting in this file.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// detekt through its CLI and not its Gradle plugin. The plugin of 1.23.8 calls
// ReportingExtension.file(String) while it is applied, which Gradle 10 removes: under a Gradle 10
// wrapper the plugin would stop the build before any script of ours ran. The CLI is the same
// detekt with no Gradle API under it. The rules and their thresholds are detekt.yml on top of
// detekt's defaults; the instrumentation tests are named so they are held to the same rules as
// the code they exercise. Without resolved types: detekt.yml says which rule that leaves out and
// what stands in for it.
val detektCli = configurations.create("detektCli")
val detektSources = listOf("app/src/main/java", "app/src/test/java", "app/src/androidTest/java")
val detektReport = "app/build/reports/detekt/detekt.html"
val detekt = tasks.register<JavaExec>("detekt") {
    group = "verification"
    classpath = detektCli
    mainClass = "io.gitlab.arturbosch.detekt.cli.Main"
    workingDir = rootProject.projectDir
    args("--build-upon-default-config", "--config", "detekt.yml")
    args("--input", detektSources.joinToString(","), "--report", "html:$detektReport")
    // What the verdict rests on, so that a tree detekt has passed is not read again.
    inputs.files(detektSources.map(rootProject::file), rootProject.file("detekt.yml"))
        .withPropertyName("sourcesAndRules").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(rootProject.file(detektReport))
}

// ktlint through its CLI as well: the ktlint Gradle plugin does not see AGP 9's built-in Kotlin
// source sets, so it would silently check nothing. The CLI is one configuration plus two JavaExec
// tasks and no guesswork.
val ktlintCli = configurations.create("ktlintCli")

// The Kotlin of every source set and the three build scripts of this one module. Named rather than
// globbed as `**/*.kts`, which walked every file of the checkout, models and corpora among them.
val ktlintSources = listOf("app/src/**/*.kt", "*.kts", "app/*.kts")

fun ktlintTask(name: String, vararg extraArgs: String) = tasks.register<JavaExec>(name) {
    group = "verification"
    classpath = ktlintCli
    mainClass = "com.pinterest.ktlint.Main"
    workingDir = rootProject.projectDir
    args(*extraArgs, *ktlintSources.toTypedArray())
}

// What the verdict rests on, and a report as the output, so that a tree ktlint has passed is not read
// again, as detekt's is not. ktlintFormat rewrites the sources and runs whenever it is asked.
val ktlintReport = "app/build/reports/ktlint/ktlint.xml"
val ktlintCheck = ktlintTask("ktlintCheck", "--reporter=plain", "--reporter=checkstyle,output=$ktlintReport")
ktlintCheck.configure {
    inputs.files(
        fileTree("src") { include("**/*.kt") },
        rootProject.files("build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts", ".editorconfig"),
    ).withPropertyName("sourcesAndRules").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(rootProject.file(ktlintReport))
}
ktlintTask("ktlintFormat", "--format")

tasks.named("check") { dependsOn(ktlintCheck, detekt) }

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:1.8.0")
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")

    // Health Connect pins guava 31.1, which has open CVEs. Constraints are the standard Gradle way
    // to raise a transitive version without taking on the dependency itself.
    //
    // Only what ships in the APK gets raised here. The other Dependabot alerts are on AGP's own
    // build classpath — bouncycastle from APK signing, jose4j from bundletool, jdom2 from
    // jetifier, commons-lang3 from sdklib — none of which reach the APK. Checked by grepping the
    // built dex: the guava control matches, those packages do not. The netty and logback alerts
    // are the Unified Test Platform arriving through testRuntimeOnly for the device tests: both
    // run on the computer that drives the emulator, neither is in the test APK, and nothing of UTP
    // reaches the app's.
    constraints {
        implementation("com.google.guava:guava:33.8.0-android")

        // Floors under two libraries nothing here declares: a transitive edge can pin appcompat 1.1.0
        // and fragment 1.1.0 — both from 2019, both flagged by the Play Console — and nothing reports
        // when one does. A floor costs nothing while no dependency asks for them.
        implementation("androidx.fragment:fragment:1.9.1")
        implementation("androidx.appcompat:appcompat:1.8.0")

        // kotlinx.serialization is in the APK only because lifecycle-viewmodel-savedstate 2.11.1
        // asks for core 1.7.3; nothing here calls it. room-testing reads the exported schema JSON
        // through room-migration 2.8.5, whose serializers were compiled against 1.8.1 and die on
        // the device with an AbstractMethodError when an older core answers — and AGP holds the
        // test APK to the app's resolved versions, so the app's copy is what has to rise. To the
        // newest release rather than 1.8.1, with the JSON half pinned to the same one on the test
        // side, because the two halves share internal API and only match within one release.
        // Consumers built against an older core keep working: the library's compatibility promise
        // runs in exactly that direction.
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.13.0")
        androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    }

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The date a picked photograph was taken. The framework has an ExifInterface of its own and
    // lint forbids it by name; this one also reads the containers a phone actually shoots in —
    // HEIC, AVIF, WebP — where the framework's cover is thin, and reading the date out of those
    // is the whole point of asking.
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    // collectAsStateWithLifecycle: the screens call it directly, so it is declared directly rather
    // than left to arrive through another library, which can drop it. With its version too, which
    // the Compose BOM does not give: declared bare, it takes whatever another library asks for, and
    // resolves to nothing when none does.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")

    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")

    // ONNX Runtime (MIT), running the chat reader, the single photo classifier and the OCR
    // models that read the nutrition table off a photographed package. The PP-OCR models keep
    // Cyrillic, Latin diacritics and Japanese alike. Declares no permissions —
    // [NoNetworkPermissionTest] keeps that honest. The det/rec models and their CTC dictionaries
    // ship in assets/ocr.
    //
    // Built here rather than taken from Maven: tools/onnxruntime-android/build.sh compiles 1.30.0
    // with --no_telemetry, without NNAPI, XNNPACK or WebGPU, and with only the operators its
    // ops.config lists. Microsoft's AAR carries every operator and, since 1.29, a 1DS telemetry
    // client with INTERNET and a provider that starts it at launch. OnnxRuntimeBuildTest holds this
    // one to having none of it; a model that needs an operator the build left out fails on the
    // phone only, so the README beside build.sh says how to regenerate the config and the AAR.
    implementation(files("libs/onnxruntime-android-1.30.0-pwnall.aar"))
    // The desktop twin of the same API lets plain JVM tests run the real pipeline on real
    // photographs; it shadows the Android artifact only on the test classpath. Kept in step with
    // the Android artifact above: the tests are only evidence while both sides run one engine. Its
    // telemetry is switched off for every Test task by ORT_DISABLE_TELEMETRY, set above. It is the
    // full desktop build, so an operator the Android build lacks shows only on a device —
    // LabDocumentReaderTest runs every OCR session there.
    //noinspection NewerVersionAvailable,GradleDependency
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    // Room's own open-helper layer, declared because Data.kt calls it directly: the wrapper that
    // moves a corrupt database aside instead of letting Room delete it is written against
    // SupportSQLiteOpenHelper and builds the framework helper Room would have built itself.
    //
    // Held at whatever the graph resolves rather than at the newest published. Room 2.8.5 asks for
    // 2.6.2 and SQLCipher below for 2.7.0, and androidx's own constraints hold the sqlite artifacts
    // together at the higher one — so 2.7.0 is what ships, and the number here says so instead of
    // arguing with it. This is the layer Room drives, not one this app owns, and raising it alone
    // would put Room on a version of its own foundation it was never built against. Raise it with
    // Room or SQLCipher, whichever asks.
    //noinspection GradleDependency
    implementation("androidx.sqlite:sqlite-framework:2.7.0")
    // SQLCipher (BSD), behind the "Encrypt the database on this phone" switch: the same SQLite with
    // every page of steps.db under AES-256-CBC and an HMAC, so a copy of the file lifted off the
    // phone's storage reads as noise without the key this phone's Keystore holds. Off by default,
    // because that key never leaves the hardware — a database encrypted here cannot be opened on
    // the next phone even though the transfer copies it, and DatabaseEncryption.kt spells that out
    // under the switch. The library's own SupportOpenHelperFactory is deliberately not used: it
    // hands SQLCipher's default error handler to the helper it builds, and under a codec that
    // handler neither quarantines nor deletes a file it cannot read, so a damaged encrypted
    // database would stop the app at every start. Data.kt builds the helper itself, with the same
    // quarantine the plain one has. Costs libsqlcipher.so — about 2.0 MB on arm64 and 1.0 MB on
    // armeabi-v7a — and declares no permissions, which [NoNetworkPermissionTest] keeps honest.
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
    // The real org.json on the test classpath, where android.jar offers only stubs that throw: the
    // labs screen, the backup and the Bluetooth payloads all speak JSON, and without it the tests
    // could only assert around them.
    testImplementation("org.json:json:20260814")
    // Virtual time, for the rules that are timeouts: how long a returning page waits for its rows
    // and how long its picture is held over it are seconds of patience a test should not sit out.
    // Kept in step with the coroutines above.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The screens, drawn on the JVM: Robolectric's native graphics runs the real Compose pipeline
    // into a Bitmap, so onboarding can be shot frame by frame on a machine with no emulator and no
    // KVM. Test classpath only — none of it reaches the APK — and the shots run only when asked for
    // with -Pscreens (ScreenShots.kt). The test manifest library registers the empty activity the
    // compose rule hosts its content in, in the unit-test manifest alone.
    //
    // The same Robolectric runs, in every `check`, the tests where the framework itself is what is
    // tested and a plain JVM has none: Room and the hand-written migrations on its SQLite
    // (MigrationRunTest, ChatActTest), a backup written and read back with its photographs, the
    // work timer over real preferences, a dose's notification, and the semantics of a composed
    // screen (OnboardingNextTest, ControlSemanticsTest). Kept to those: the sandbox takes some
    // three seconds to start, once for the run, and every test in it a fresh application.
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.11.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.compose.ui:ui-test-manifest")

    // MigrationTest executes the hand-written Room migrations on a device, step by step against
    // the schema exported for each version.
    // room-testing's MigrationTestHelper builds a database at an old version from the exported
    // schema and holds the migrated one against the newer; the other three are the runner it needs.
    // room-testing moves with Room above. The rest of that source set is what else only a device
    // has: SQLCipher's native library and the Keystore, the shipped models on the phone's runtime,
    // a notification through the system. `check` compiles all of it and runs none (see above).
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
