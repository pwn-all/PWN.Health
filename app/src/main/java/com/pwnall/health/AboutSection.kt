package com.pwnall.health

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The developer's address the privacy policy publishes, and nothing else of theirs. The policy's own
 * address is [PRIVACY_POLICY_URL].
 */
private const val DEVELOPER_ADDRESS = "pub-android-dev@pwn-all.com"
private const val APP_NAME = "PWN.Health"

/**
 * Where the source of this build is to be had. The GPL asks whoever hands out a binary to say so
 * beside it, and of everything a store shows only the APK reaches the phone.
 */
private const val SOURCE_ADDRESS = "github.com/pwn-all/PWN.Health"

/**
 * What the build is made of besides its own code. Kept by hand against `app/build.gradle.kts` and
 * `assets/`; ThirdPartyTest fails for a library in the dependencies block, or an archive in
 * `app/libs`, that has no row here. Nothing checks the models and the data in the assets against
 * the rows; the licence texts among them are held to [LICENCE_TEXTS].
 */
internal class ThirdParty(val name: String, val licence: String, @StringRes val usedFor: Int)

internal val THIRD_PARTY = listOf(
    ThirdParty("ONNX Runtime", "MIT", R.string.about_use_onnx),
    // The licence asks to be credited where the person using the app can find it, and for its own
    // words to go with the binary: [LICENCE_TEXTS] carries them, as the library's repository words
    // them at the version built in — three clauses, which is what the identifier here says. SQLite
    // and LibTomCrypt, which it is built with, are in the public domain.
    ThirdParty("SQLCipher Community Edition, Zetetic LLC", "BSD-3-Clause", R.string.about_use_sqlcipher),
    ThirdParty("PaddleOCR PP-OCRv6 small and PP-OCRv5 East Slavic models", "Apache-2.0", R.string.about_use_paddle),
    ThirdParty(
        "Perception Encoder (PE-Core), Meta; the AIY food classifier's dish list, Google",
        "Apache-2.0",
        R.string.about_use_food_models,
    ),
    ThirdParty("Natural Earth 1:10m", "Public domain", R.string.about_use_natural_earth),
    // CC BY asks to be named wherever the work is shown: the map's towns stand on its points.
    ThirdParty(
        "GeoNames, geonames.org — the places and populations of the map’s towns",
        "CC BY 4.0",
        R.string.about_use_geonames,
    ),
    // CC BY asks for the source and the notice ESA words it with, wherever the forests it gave are shown.
    ThirdParty(
        "ESA WorldCover 2021 v200 — © ESA WorldCover project 2021, contains modified Copernicus " +
            "Sentinel data (2021) processed by the ESA WorldCover consortium",
        "CC BY 4.0",
        R.string.about_use_worldcover,
    ),
    ThirdParty(
        "ETOPO 2022, NOAA National Centers for Environmental Information",
        "Public domain",
        R.string.about_use_etopo,
    ),
    ThirdParty("USDA FoodData Central", "CC0 1.0", R.string.about_use_usda),
    ThirdParty("Compendium of Physical Activities 2024", "Citation", R.string.about_use_compendium),
    // The licence requires its notice to travel with anything that carries its content, and a lab
    // record stores its units as UCUM spells them. This row is that notice.
    ThirdParty(
        "UCUM 2.2, Regenstrief Institute, Inc. and the UCUM Organization",
        "UCUM licence 1.1",
        R.string.about_use_ucum,
    ),
    // The chat's reader: what ships of it and what it learned from, as assets/chat/NOTICES.md records
    // in full, with every snapshot's hash.
    ThirdParty(
        "Multilingual-MiniLM-L12-H384, Microsoft — the chat reader’s weights, fine-tuned and quantised",
        "MIT",
        R.string.about_use_chat_reader,
    ),
    ThirdParty(
        "XLM-R SentencePiece pieces, Meta (fairseq) — the chat reader’s tokenizer",
        "MIT",
        R.string.about_use_chat_tokenizer,
    ),
    ThirdParty("Unicode emoji data (UTS #51), Unicode, Inc.", "Unicode License v3", R.string.about_use_chat_emoji),
    // CC BY asks to be named wherever the work it covers is shown. The reader's training texts are
    // shown nowhere, but the model learned from them, and their authors are named here.
    ThirdParty(
        "Training texts of the chat reader: BRIGHTER (Muhammad et al., 2025), CLINC150 (Larson et al., " +
            "2019), MASSIVE 1.1 (Amazon), MedQuAD (Ben Abacha and Demner-Fushman, 2019), Tatoeba " +
            "(tatoeba.org), GoEmotions (Google Research), Aya (Cohere For AI), symptom_to_diagnosis (Gretel)",
        "CC BY, Apache-2.0",
        R.string.about_use_chat_training,
    ),
    // The same rule for the photo model as for the reader above: CC BY asks to be named. The sets that
    // allow research or non-commercial use only are named beside them, so that the row does not read
    // as a list of free ones; README's Licence section says what follows from them.
    ThirdParty(
        "Training photographs of the food photo model: CAFSD (Karabay et al., ISSAI, 2025), Thai-22 " +
            "(Boonpoor, Zenodo 17509401), VegNet (Suryawanshi et al.), Wikimedia Commons; through the model that " +
            "taught its product answers, MyFoodRepo (AIcrowd), Nutrition5k (Google Research), FruitNet " +
            "(Meshram and Patil). For research or non-commercial use only: ISIA Food-500, iFood-2019, " +
            "UEC FOOD 256, Food-101, MM-Food-100K, CAFD, THFOOD-50, food116",
        "CC BY; research only",
        R.string.about_use_photo_training,
    ),
    ThirdParty("AndroidX, Kotlin, Compose Material 3, Guava", "Apache-2.0", R.string.about_use_androidx),
)

/** One licence or notice the build carries in full: what the page heads it with, and its file in `assets/`. */
internal class LicenceText(val title: String, val asset: String)

/**
 * The words the licences above ask to travel with the binary, in the order the page behind
 * "Licence texts" shows them. A name in [THIRD_PARTY] is the credit; BSD and MIT also want their
 * notice and their conditions reproduced, which the two libraries that ship as native code do not
 * bring into the APK themselves, and the chat reader's notices are here so that a screen opens
 * them. Each file is the upstream one as it stands at the version built in, copied and not
 * retyped: SQLCipher's from the two repositories at the tag of the release, ONNX Runtime's from
 * the release's own package. Kept by hand like the list above; ThirdPartyTest fails for a file
 * named here that is not in the assets, for a notice in the assets that is not named here, and for
 * a library in the build with no text.
 *
 * The app's own licence comes first: the GPL has its text handed over with every copy, and the line
 * on About that names the licence has this page to show it. The file is `LICENSE` from the
 * repository's root, and the same test holds it to that byte for byte.
 */
internal val LICENCE_TEXTS = listOf(
    LicenceText("$APP_NAME — GNU General Public License, version 3", "licences/pwn-health-gpl-3.0.txt"),
    LicenceText("SQLCipher for Android, Zetetic LLC", "licences/sqlcipher-android.txt"),
    LicenceText("SQLCipher, Zetetic LLC", "licences/sqlcipher.txt"),
    LicenceText("ONNX Runtime, Microsoft Corporation", "licences/onnxruntime.txt"),
    LicenceText("ONNX Runtime — what it is built with", "licences/onnxruntime-third-party.txt"),
    LicenceText("The chat reader — what ships of it and what it learned from", "chat/NOTICES.md"),
    LicenceText("Multilingual-MiniLM, Microsoft", "chat/NOTICES/MIT-microsoft-unilm.txt"),
    LicenceText("XLM-R SentencePiece pieces, Meta (fairseq)", "chat/NOTICES/MIT-facebookresearch-fairseq.txt"),
    LicenceText("Unicode License v3", "chat/NOTICES/Unicode-3.0.txt"),
    LicenceText("Apache License 2.0", "chat/NOTICES/Apache-2.0.txt"),
)

/**
 * The page behind "About the app": what this build is, where to read and write, a report to paste
 * into a message, and what it ships that is not its own.
 *
 * Nothing here is remembered across visits, the count of taps on the version line included. Seven of
 * those open the sensor view: it is the only door a shipped build has to the raw feed and the CSV
 * under it, which are the only answer to "it counted the wrong number of steps". The version comes
 * from the package manager rather than BuildConfig: it is what is installed, whatever build put it there.
 */
@Composable
internal fun AboutSection(profile: UserProfile, lockEnabled: Boolean, onSensors: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val version = remember { appVersion(context) }
    var status by rememberStatusLine()
    // Not saveable: the page is composed one branch at a time, so leaving About — for the sensor
    // view or for anywhere else — takes the count with it and the next visit costs seven again.
    var taps by remember { mutableIntStateOf(0) }
    // The three cards are wrapped only so the confirmation can be laid over them: a "Copied" printed
    // as a line inside the second card would be clipped, since a PwnCard clips its content to its
    // rounded corners, so nothing centred over the page can be drawn from in there.
    // The inner Column keeps the 12dp the profile's own column already puts between these three
    // cards, so the page itself does not move.
    Box(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(PwnDimensions.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    // Seven taps, the way a phone's own build number opens its developer screen.
                    // Nothing announces it: a line that said so would be a feature, and this is a
                    // door for the one conversation that starts "it counted the wrong number".
                    // The strip is a finger's height even though the line is not, and the words
                    // stay centred in it.
                    Text(
                        stringResource(R.string.about_version, version.name, version.code),
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = PwnDimensions.minimumTarget)
                            .clickable {
                                taps++
                                if (taps == 7) {
                                    taps = 0
                                    onSensors()
                                }
                            }
                            .wrapContentHeight(Alignment.CenterVertically),
                        color = PwnMuted,
                    )
                    Text(
                        stringResource(R.string.about_permissions_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = PwnMuted,
                    )
                    // Text to select and not a link: nothing in the app opens a browser, and an
                    // address that can be neither tapped nor copied would have to be retyped.
                    SelectionContainer {
                        Text(
                            stringResource(R.string.about_free_software, SOURCE_ADDRESS),
                            style = MaterialTheme.typography.bodySmall,
                            color = PwnMuted,
                        )
                    }
                }
            }
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { context.showPrivacyPolicy() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.about_privacy_policy)) }
                    OutlinedButton(
                        onClick = { context.writeToDeveloper(version.name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.about_write_developer)) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = collectDiagnostics(context, profile, lockEnabled).render()
                                context.shareDiagnostics(report)
                                status = resources.getString(R.string.about_diagnostics_copied)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.about_copy_diagnostics)) }
                }
            }
            PwnCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.about_third_party), style = MaterialTheme.typography.titleMedium)
                    // Above the rows, not under the last of them: the list is longer than a screen,
                    // and the texts are what several of its licences ask to be findable.
                    OutlinedButton(
                        onClick = { context.showLicenceTexts() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.about_licence_texts)) }
                    THIRD_PARTY.forEach { component ->
                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(component.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(component.licence, color = PwnMuted, style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                stringResource(component.usedFor),
                                color = PwnMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        // matchParentSize, not the fillMaxSize PwnActionFlash asks for on its own: this Box is a
        // child of the profile's vertical scroll, so its height arrives unbounded, and fillMaxSize
        // under an unbounded height keeps the minimum it was given rather than filling anything —
        // the flash would wrap its own card and be placed at the section's top edge. Measured
        // against the three cards instead, the tick lands in the middle of what it confirms, and
        // it is outside all three PwnCards so no card's corner clips it.
        //
        // What that does not buy is the viewport. Three cards and eight licence rows are taller
        // than a phone shows at once, so this tick is centred on the section and rides the scroll
        // with it, unlike a tracker screen's, which hangs off a Box the scroll sits inside. The
        // screen-wide Box of that shape is at the root of ProfileScreen and already flashes that
        // screen's own status; sharing it means hoisting this section's status up to it.
        PwnActionFlash(status, Modifier.matchParentSize())
    }
}

internal class AppVersion(val name: String, val code: Long)

/** Read from the package rather than BuildConfig: what is installed, whatever build put it there. */
internal fun appVersion(context: Context): AppVersion {
    val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    return AppVersion(info.versionName.orEmpty(), info.longVersionCode)
}

// A phone with no mail app is a phone and not a crash.
private fun Context.writeToDeveloper(versionName: String) {
    val mail = Intent(Intent.ACTION_SENDTO, "mailto:$DEVELOPER_ADDRESS".toUri())
        .putExtra(Intent.EXTRA_SUBJECT, "$APP_NAME $versionName")
    runCatching { startActivity(mail) }
}

/**
 * On the clipboard first — the one place that always takes it — and then offered to a chooser. A mail
 * app picked there opens addressed to the developer, as the privacy policy's §9 says it does; any other
 * app ignores the address, and nothing is sent until the person sends it.
 */
private fun Context.shareDiagnostics(report: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(APP_NAME, report))
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_ADDRESS))
        .putExtra(Intent.EXTRA_SUBJECT, APP_NAME)
        .putExtra(Intent.EXTRA_TEXT, report)
    runCatching { startActivity(Intent.createChooser(send, getString(R.string.about_copy_diagnostics))) }
}

/** The build and the phone it runs on. */
internal data class DiagnosticsBuild(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val locale: String,
)

internal data class DiagnosticsSettings(
    val unitSystem: String,
    val lockOn: Boolean,
    val autoBackupOn: Boolean,
    val exportAgeDays: Long?,
)

internal data class DiagnosticsHealth(val status: String, val granted: Int, val requested: Int)

/** How big the database is — four counts, and nothing of what the rows say. */
internal data class DiagnosticsRows(val journal: Int, val stepDays: Int, val tracks: Int, val progressPhotos: Int)

/**
 * What a bug report needs and nothing a bug report must not carry: no entry, no coordinate, no
 * medication's name, no row id. The install id is a random name for this installation and names
 * nobody; it is here so that two reports from one phone can be told to be from one phone.
 */
internal data class Diagnostics(
    val build: DiagnosticsBuild,
    val settings: DiagnosticsSettings,
    val health: DiagnosticsHealth,
    val rows: DiagnosticsRows,
    val installId: String,
)

/** Plain lines for a message to the developer, in English whatever the phone speaks: the reader is fixed. */
internal fun Diagnostics.render(): String = listOf(
    "$APP_NAME ${build.versionName} (${build.versionCode})",
    "Android ${build.androidRelease} (SDK ${build.sdkInt}), ${build.manufacturer} ${build.model}",
    "Locale ${build.locale}, units ${settings.unitSystem}",
    "App lock: ${onOff(settings.lockOn)}",
    "Health Connect: ${health.status}, ${health.granted} of ${health.requested} permissions granted",
    "Last export: ${settings.exportAgeDays?.let { "$it days ago" } ?: "never"}; " +
        "automatic copy: ${onOff(settings.autoBackupOn)}",
    "Rows: ${rows.journal} journal entries, ${rows.stepDays} step days, ${rows.tracks} tracks, " +
        "${rows.progressPhotos} progress photos",
    "Install id: $installId",
).joinToString("\n")

private fun onOff(on: Boolean): String = if (on) "on" else "off"

/** Gathered off the main thread: four counts, a binder call to Health Connect, and the preferences. */
internal suspend fun collectDiagnostics(context: Context, profile: UserProfile, lockOn: Boolean): Diagnostics =
    withContext(Dispatchers.IO) {
        val version = appVersion(context)
        val dao = StepsDb.get(context).dao()
        // The same set the screens ask for, age filter included: a report reading "55 of 57" for
        // somebody who was only ever offered 55 describes a refusal that never happened.
        val requested = healthPermissionsKnownTo(context).let {
            if (sexLogOffered(profile.birthDate, LocalDate.now())) it else withoutIntimateTypes(it)
        }
        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        val granted = if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            runCatchingCancellable {
                HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        Diagnostics(
            build = DiagnosticsBuild(
                versionName = version.name,
                versionCode = version.code,
                androidRelease = Build.VERSION.RELEASE,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                locale = Locale.getDefault().toLanguageTag(),
            ),
            settings = DiagnosticsSettings(
                unitSystem = profile.unitSystem,
                lockOn = lockOn,
                autoBackupOn = AutoBackup.folder(context) != null,
                exportAgeDays = backupAgeDays(
                    AutoBackup.lastExportMs(context),
                    LocalDate.now(),
                    ZoneId.systemDefault(),
                ),
            ),
            health = DiagnosticsHealth(
                status = healthConnectStatusName(sdkStatus),
                granted = granted.count { it in requested },
                requested = requested.size,
            ),
            rows = DiagnosticsRows(
                journal = dao.journalCount(),
                stepDays = dao.stepDayCount(),
                tracks = dao.trackCount(),
                progressPhotos = dao.progressPhotoCount(),
            ),
            installId = installId(context),
        )
    }

private fun healthConnectStatusName(status: Int): String = when (status) {
    HealthConnectClient.SDK_AVAILABLE -> "available"
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "provider update required"
    else -> "unavailable"
}
