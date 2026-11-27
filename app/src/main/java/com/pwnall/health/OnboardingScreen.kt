package com.pwnall.health

import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** The side margin every page of onboarding keeps, and the one thing on page one that escapes it. */
private val ONBOARDING_SIDE_PADDING = 28.dp

@Composable
fun OnboardingScreen(onComplete: suspend (UserProfile) -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // The birth date starts blank and nothing past its page opens while it still is; so does the
    // sex, which on "male" would answer for anybody who tapped straight through. Height and weight
    // are the exception a ruler always is — one tick is always under the needle — so they start on
    // 170 cm and 70 kg: a round adult figure, not the middle of their ranges, and what the profile
    // starts from for anybody who taps straight through. Whether each ruler was moved is kept beside
    // its figure: one nobody set is not a measurement, and [logInitialBody] is handed none for it.
    var sex by rememberSaveable { mutableStateOf("") }
    var weightKg by rememberSaveable { mutableDoubleStateOf(70.0) }
    var heightCm by rememberSaveable { mutableIntStateOf(170) }
    var weightSet by rememberSaveable { mutableStateOf(false) }
    var heightSet by rememberSaveable { mutableStateOf(false) }
    var birthDate by rememberSaveable { mutableStateOf<String?>(null) }
    // Null until the steps page is reached, which then starts on the suggestion for the age given.
    var stepGoal by rememberSaveable { mutableStateOf<Long?>(null) }
    // Saveable like the numbers they explain: a prefill lost to a rotation makes values Health
    // Connect supplied look typed by hand, and they would then be published back as this app's own.
    var prefillWeightKg by rememberSaveable { mutableStateOf<Double?>(null) }
    var prefillHeightCm by rememberSaveable { mutableStateOf<Int?>(null) }
    // Stored the way the column stores it, so the page and the profile cannot disagree about what
    // "never tested" is: see [allergyAnswerOf].
    var allergies by rememberSaveable { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kilograms or pounds starts from the phone, and the height and weight pages carry the switch:
    // with the guess as the only answer, a phone set to the wrong country would give feet and pounds
    // and no way out of them until the profile.
    val locale = LocalLocale.current.platformLocale
    var unitSystem by rememberSaveable {
        mutableStateOf(localeUnitSystem(locale.country, measureSetting(locale)))
    }
    val imperial = unitSystem == "IMPERIAL"
    val onImperial: (Boolean) -> Unit = { unitSystem = if (it) "IMPERIAL" else "METRIC" }
    val today = rememberToday()
    // The profile the answers add up to so far: what the steps page suggests from and what the
    // finish reads its figures out of, built by the same code that will save it.
    val draft = UserProfile(
        sex = sex.ifEmpty { "FEMALE" },
        weightKg = weightKg,
        heightCm = heightCm,
        birthDate = birthDate.orEmpty(),
        unitSystem = unitSystem,
        allergies = allergies,
    )
    val suggestedSteps = recommendedStepGoal(draft, today.year)
    // Asked for after both wheels rather than before them, so this is not a prefill: the
    // answers are already given. What it is for is the finish — it decides whether the values
    // about to be recorded are news to Health Connect or its own numbers handed back to it, and it
    // is what gives the first exchange anything to carry. The birth date settles one question in
    // the request itself: sexual activity is not asked for by an app that will not show it.
    val requestHealthAccess = rememberHealthConnectPrefill(birthDate) { body ->
        prefillWeightKg = body?.weightKg
        prefillHeightCm = body?.heightCm
        page = READY_PAGE
    }
    val facts = onboardingFacts(
        page = page,
        birthDate = birthDate,
        sex = sex,
        heightCm = heightCm,
        weightKg = weightKg,
        stepGoal = stepGoal,
        imperial = imperial,
        today = today,
    )

    androidx.activity.compose.BackHandler(enabled = page > 0 && !finishing) { page = pageBefore(page) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The bars and a display cutout both: edge to edge, a landscape window reaches under a side
        // cutout, and the back button stands close to that edge. Not safeDrawing, which would add
        // the keyboard to a window that already resizes for it.
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            val asking = page in 1 until READY_PAGE
            val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
            // The header stands outside the pages, so its line is seen to move rather than being
            // carried off with one page and brought back with the next.
            androidx.compose.animation.AnimatedVisibility(
                visible = asking,
                enter = fadeIn(PwnMotion.effects()) + slideInVertically(PwnMotion.spatial()) { -it / 2 },
                exit = fadeOut(PwnMotion.effects()),
                label = "onboarding header",
            ) {
                Column {
                    // The arrow outlives its page: it is still under the finger while this header fades
                    // out over the greeting, so what it does is clamped — see [pageBefore].
                    OnboardingHeader(
                        step = (page - 1).coerceIn(0, ONBOARDING_STEPS - 1),
                        onBack = { page = pageBefore(page) },
                    )
                    OnboardingFacts(facts, Modifier.padding(top = 10.dp))
                }
            }
            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    // Material's shared X axis: forward comes in from the end and leaves to the
                    // start, Back the other way round, so the direction of travel says which way the
                    // person went. Offsets are a fraction of the width, not all of it: the page
                    // arrives rather than being pushed across the whole screen.
                    val forward = targetState > initialState
                    val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                    val sign = if (forward != rtl) 1 else -1
                    (
                        slideInHorizontally(PwnMotion.spatial()) { sign * it / 5 } +
                            fadeIn(tween(PAGE_FADE_MS, delayMillis = PAGE_FADE_MS / 2))
                        ).togetherWith(
                        slideOutHorizontally(PwnMotion.spatial()) { -sign * it / 5 } + fadeOut(tween(PAGE_FADE_MS / 2)),
                    ).using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "onboarding page",
            ) { shownPage ->
                androidx.compose.runtime.CompositionLocalProvider(LocalRulerActive provides (shownPage == page)) {
                    when (shownPage) {
                        0 -> HelloScreen(onNext = { page = 1 })

                        1 -> PermissionsAheadScreen(onNext = { page = 2 })

                        2 -> AgeScreen(
                            birthDate = birthDate,
                            onBirthDate = { birthDate = it },
                            onNext = { page = 3 },
                        )

                        3 -> SexScreen(sex = sex, onSex = { sex = it }, onNext = { page = 4 })

                        4 -> HeightScreen(
                            heightCm = heightCm,
                            imperial = imperial,
                            onImperial = onImperial,
                            onHeightCm = {
                                heightCm = it
                                heightSet = true
                            },
                            onNext = { page = 5 },
                        )

                        5 -> WeightScreen(
                            weightKg = weightKg,
                            heightCm = heightCm,
                            birthDate = birthDate,
                            imperial = imperial,
                            onImperial = onImperial,
                            onWeightKg = {
                                weightKg = it
                                weightSet = true
                            },
                            onNext = { page = 6 },
                        )

                        6 -> AllergyScreen(
                            answer = allergyAnswerOf(allergies),
                            onAnswer = { allergies = it.stored() },
                            onNext = { page = 7 },
                        )

                        7 -> StepGoalScreen(
                            goal = stepGoal ?: suggestedSteps,
                            suggested = suggestedSteps,
                            heightCm = heightCm,
                            imperial = imperial,
                            onGoal = { stepGoal = it },
                            onNext = {
                                if (stepGoal == null) stepGoal = suggestedSteps
                                page = 8
                            },
                        )

                        8 -> OnboardingAccessScreen(onNext = requestHealthAccess)

                        READY_PAGE -> OnboardingReadyScreen(
                            summary = readySummary(draft.copy(dailyStepGoal = stepGoal ?: suggestedSteps), today),
                            onBack = { page = READY_PAGE - 1 },
                            onStart = {
                                // Once: the screen stays up until the saved profile emits, and a second tap
                                // would record the body measurement all over again.
                                if (finishing) return@OnboardingReadyScreen
                                // The age page is the only road here and it does not open until a date is
                                // entered, so this is unreachable — and it is the profile's one required
                                // field, which makes "unreachable" the wrong thing to prove with a default.
                                val born = birthDate ?: return@OnboardingReadyScreen
                                finishing = true
                                // The goal the person chose on the steps page, which starts on the age
                                // band's suggestion: a goal they set themselves is the one the ENGAGE
                                // trial found people keep walking to.
                                val profile = draft.copy(
                                    sex = sex,
                                    birthDate = born,
                                    dailyStepGoal = stepGoal ?: suggestedSteps,
                                )
                                val weight = weightKg.takeIf { weightSet }
                                val height = heightCm.takeIf { heightSet }
                                val prefill = HealthConnectBody(prefillWeightKg, prefillHeightCm)
                                scope.launch {
                                    finishOnboarding(
                                        saveProfile = { onComplete(profile) },
                                        logBody = { logInitialBody(context, weight, height, prefill) },
                                    )
                                }
                            },
                        )

                        // The finish is the page that saves, so it is drawn for its own number and for
                        // no other: a number nobody meant lands on the greeting, where nothing is kept.
                        else -> HelloScreen(onNext = { page = 1 })
                    }
                }
            }
        }
    }
}

/**
 * What the answers come to, as the finish reads it out: the step goal the person chose, the resting
 * energy Mifflin–St Jeor gives for the body they described, and where that body stands on WHO's adult
 * line. Every figure is the one the app is about to use, worked out by the code that will use it.
 */
internal data class ReadySummary(
    val stepGoal: Long,
    val restingKcal: Int,
    val bmi: Double,
    val bmiLabel: Int,
)

internal fun readySummary(profile: UserProfile, today: LocalDate): ReadySummary {
    val index = bmi(profile.weightKg, profile.heightCm)
    return ReadySummary(
        stepGoal = profile.dailyStepGoal,
        restingKcal = restingCaloriesPerDay(profile, today.year).roundToInt().coerceAtLeast(0),
        bmi = index,
        bmiLabel = bmiLabelRes(index, profile.birthDate, today),
    )
}

/**
 * The finish's two writes: the profile, then the body measurement the answers came to.
 *
 * The profile first. Until it is saved the app opens on these questions again, so a measurement
 * recorded ahead of it would stay behind when the save failed, and the next run through the
 * questions would record it a second time. Saved first, a failure leaves nothing to repeat, and one
 * after it loses only a measurement the profile itself still holds. NonCancellable because the
 * saved profile swaps this screen out and cancels the scope that called this, and a rotation
 * cancels it too: stopping between the two writes would lose the measurement.
 */
internal suspend fun finishOnboarding(saveProfile: suspend () -> Unit, logBody: suspend () -> Unit) {
    withContext(NonCancellable) {
        saveProfile()
        logBody()
    }
}

/** The finish: the one page after the questions, which the progress line does not count. */
private const val READY_PAGE = ONBOARDING_STEPS + 1

/**
 * The page Back leads to, and never one before the greeting. The header's arrow can still be tapped
 * while the header fades out over the greeting: counted down unchecked, a second tap there would make
 * page −1, which is drawn as the finish — Start, with none of the questions asked.
 */
internal fun pageBefore(page: Int): Int = (page - 1).coerceAtLeast(0)

/** How long a page takes to fade in; it fades out in half of it, and fades in after that half. */
private const val PAGE_FADE_MS = 360

/**
 * The answers already behind the person, as the tags under the progress line show them: each one
 * appears once its page has been left forwards, in the units the person chose, and nothing that has
 * not been answered is shown.
 */
@Composable
private fun onboardingFacts(
    page: Int,
    birthDate: String?,
    sex: String,
    heightCm: Int,
    weightKg: Double,
    stepGoal: Long?,
    imperial: Boolean,
    today: LocalDate,
): List<Pair<String, String>> {
    val locale = LocalLocale.current.platformLocale
    val resources = LocalResources.current
    return buildList {
        if (page > 2 && birthDate != null) {
            val age = ageOn(today.toString(), birthDate)
            add("age" to resources.getQuantityString(R.plurals.labs_years, age, age))
        }
        if (page > 3 && sex.isNotEmpty()) {
            add("sex" to resources.getString(if (sex == "MALE") R.string.sex_male else R.string.sex_female))
        }
        if (page > 4) {
            val inches = (heightCm / CENTIMETRES_IN_AN_INCH).roundToInt()
            add(
                "height" to if (imperial) {
                    "${inches / 12}′ ${inches % 12}″"
                } else {
                    "${String.format(locale, "%d", heightCm)} ${resources.getString(R.string.unit_cm)}"
                },
            )
        }
        if (page > 5) {
            val shown = if (imperial) weightKg * POUNDS_IN_A_KILOGRAM else weightKg
            val unit = resources.getString(if (imperial) R.string.unit_lb else R.string.unit_kg)
            add("weight" to "${String.format(locale, "%.1f", shown)} $unit")
        }
        if (page > 7 && stepGoal != null) {
            val count = stepGoal.toInt()
            add("steps" to resources.getQuantityString(R.plurals.track_steps, count, count))
        }
    }
}

/**
 * Records the body values the questionnaire produced, unless Health Connect is where they came from.
 *
 * The profile row is the app's current state, not a log: only journal events are mirrored outwards,
 * so a weight typed during onboarding and kept only there would stay inside the app forever.
 * Writing it as an ordinary measurement puts it in the timeline the rest of the app already reads
 * and exports.
 *
 * Which is why a value is null here when its ruler was never moved. The 70 kg and 170 cm the rulers
 * open on are only where a ruler has to stand; logged, they would be a measurement nobody took, in the
 * journal, the charts and the doctor's report, and published from there to Health Connect under
 * this app's name. The profile still starts from them, and the first real measurement replaces them.
 */
private suspend fun logInitialBody(
    context: Context,
    weightKg: Double?,
    heightCm: Int?,
    prefill: HealthConnectBody?,
) {
    val (newWeight, newHeight) = bodyWorthRecording(weightKg, heightCm, prefill) ?: return
    val db = StepsDb.get(context)
    val now = System.currentTimeMillis()
    db.saveJournalEvent(
        JournalEntry(
            day = LocalDate.now().toString(),
            occurredAt = now,
            kind = KIND_MEASUREMENT,
            title = context.getString(R.string.measurement_log_title),
            calories = 0.0,
            measurementWeightKg = newWeight,
            measurementHeightCm = newHeight,
            measurementSource = "MANUAL",
        ),
    )
    writeLocalHealthIfAvailable(context, db)
}

/**
 * Which of the two body values the questionnaire produced are news to Health Connect.
 *
 * A value it never held is news; so is one it held and the person then corrected. Only a value that
 * came from there and was left alone is not — recording that would publish a copy of somebody
 * else's record under this app's name. A value that is null was never given — its ruler stands
 * where it opened — and is news to nobody. Null means there is nothing to record at all.
 */
internal fun bodyWorthRecording(
    weightKg: Double?,
    heightCm: Int?,
    prefill: HealthConnectBody?,
): HealthConnectBody? {
    val weight = weightKg?.takeIf { !samePickerWeight(it, prefill?.weightKg) }
    val height = heightCm?.takeIf { it != prefill?.heightCm }
    return if (weight == null && height == null) null else HealthConnectBody(weight, height)
}

/**
 * Left alone, as the wheel sees it. The picker quantises to a tenth of a kilogram or of a pound,
 * so an untouched prefill comes back a hair off its exact value; comparing bit-for-bit would call
 * that a correction and publish Health Connect's own number back to it.
 */
private fun samePickerWeight(weightKg: Double, prefillKg: Double?): Boolean {
    if (prefillKg == null) return false
    return (weightKg * 10).roundToInt() == (prefillKg * 10).roundToInt() ||
        (weightKg * POUNDS_IN_A_KILOGRAM * 10).roundToInt() == (prefillKg * POUNDS_IN_A_KILOGRAM * 10).roundToInt()
}

/** How long one greeting stands still before the next takes its place. */
private const val GREETING_HOLD_MS = 1_500L

/**
 * Hello in every language the app is translated into.
 *
 * Not a translated string: the point is that all sixteen are shown, to everybody, one after
 * another, so a reader in any of them sees their own greeting go past and the fifteen they cannot
 * read go past with it. The device's own language is rotated to the front, because the first word
 * an app says should be one its reader can answer to.
 */
private val GREETINGS = listOf(
    "en" to "Hello",
    "ru" to "Привет",
    "es" to "Hola",
    "fr" to "Bonjour",
    "de" to "Hallo",
    "it" to "Ciao",
    "pt" to "Olá",
    "pl" to "Cześć",
    "cs" to "Ahoj",
    "tr" to "Merhaba",
    "ar" to "مرحبا",
    "hi" to "नमस्ते",
    "id" to "Halo",
    "zh" to "你好",
    "ja" to "こんにちは",
    "ko" to "안녕하세요",
)

/**
 * The sixteen greetings with the one in [language] — a [Locale.getLanguage] — first, or English
 * where the phone speaks none of them.
 *
 * Each code is read through [Locale] before it is compared, because Indonesian has two: "in", which
 * the resource folders and older runtimes use, and "id", which newer ones return. Compared as
 * written, one of the two runtimes would never find its greeting and open on "Hello".
 */
internal fun greetingsFor(language: String): List<String> {
    val own = GREETINGS.indexOfFirst { Locale.forLanguageTag(it.first).language == language }.coerceAtLeast(0)
    return (GREETINGS.drop(own) + GREETINGS.take(own)).map { it.second }
}

/**
 * The first screen: one word, changing, and nothing else on the page.
 *
 * The word does not cut to the next one — it rises and fades out while the next rises into the
 * place it left, which is the whole of the animation and the reason there is no button, no
 * illustration and no paragraph anywhere near it. It keeps going round all sixteen for as long as
 * the page is open — a welcome, decorative and carrying no figure. A tap anywhere goes on.
 *
 * One thing may stand over it, on a phone whose keystore would not answer for the key of an
 * encrypted record ([SetAside.OWN]). The record was set aside whole and the app began an empty
 * one, which opens here: a greeting and a first start's questions, to somebody whose months of
 * entries are a file away. The card that says so stands on the profile, past those questions, and
 * here too, with the one answer that brings the record back and without the one that deletes it.
 * Why the last start could not open it is under the card, as it is under the switch.
 *
 * A record set aside for good is named here as well, one no key here opens or a damaged one, with
 * no answer: an import and a copy saved out are the profile's, and both can wait for the questions.
 * Unsaid, somebody whose months of entries had been set aside would meet a greeting and the first
 * start's questions as though there had never been any.
 */
@Composable
private fun HelloScreen(onNext: () -> Unit) {
    val activity = LocalActivity.current
    val language = LocalLocale.current.platformLocale.language
    val greetings = remember(language) { greetingsFor(language) }
    var index by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(greetings) {
        // With animations switched off in the system settings the word does not change at all.
        if (animationsOff(context)) return@LaunchedEffect
        while (true) {
            delay(GREETING_HOLD_MS)
            index = (index + 1) % greetings.size
        }
    }
    val hint = stringResource(R.string.onboarding_tap_to_continue)
    // Read once, as the profile reads it: the files are looked for, not watched.
    val waiting = remember(context) { DatabaseEncryption.setAside(context) }
    val lastError = remember(context) { DatabaseEncryption.lastError(context) }

    Box(
        Modifier.fillMaxSize()
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onNext)
            .padding(horizontal = ONBOARDING_SIDE_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        if (waiting != null) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // A wish for the next start, as on the profile: the two files change places before
                // anything holds either open, so the app closes. The other kinds are answered on the
                // profile, and only named here.
                val bringBack: () -> Unit = {
                    DatabaseEncryption.requestReturn(context)
                    activity?.let(::closeForDatabaseChange)
                }
                SetAsideCard(
                    kind = waiting,
                    onAction = bringBack.takeIf { waiting == SetAside.OWN },
                    onForget = null,
                )
                lastError?.let {
                    Text(
                        stringResource(R.string.db_encrypt_error, it),
                        color = PwnWarn,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        AnimatedContent(
            targetState = index,
            // A word every second and a half is a word a screen reader would talk over for ever,
            // and none of the sixteen is information anybody needs. The tap is what the screen is
            // for, and the clickable above carries its label.
            modifier = Modifier.clearAndSetSemantics {},
            // The rise is movement and springs; the fade is an effect and settles without overshoot.
            transitionSpec = {
                (fadeIn(PwnMotion.effects()) + slideInVertically(PwnMotion.spatial()) { it / 4 })
                    .togetherWith(
                        fadeOut(PwnMotion.effects()) +
                            slideOutVertically(PwnMotion.spatial()) { -it / 4 },
                    )
            },
            label = "greeting",
        ) { shown ->
            Text(
                greetings[shown],
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = PwnAccentInk,
                textAlign = TextAlign.Center,
                // One word with no space in it cannot wrap, and the longest of the sixteen —
                // Merhaba, こんにちは, 안녕하세요 — is wider than a 360dp screen at displayLarge. It
                // shrinks to fit instead of being cut off in the middle.
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 57.sp),
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PolicyLine(R.string.onboarding_policy_consent)
        }
    }
}

/** Whether the system's animator duration scale is off, which is how "remove animations" is set. */
private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * A sentence with the privacy policy's name inside it, where the name is the only part drawn as a
 * link and the line is the only thing on the greeting that does not go on to the next page.
 *
 * The whole line is the target rather than the two words inside it: the greeting is one
 * screen-wide clickable, and a span the width of a phrase and the height of one line is a thing
 * somebody misses and turns the page instead. The name is a placeholder rather than a second Text
 * because word order and case both move in sixteen languages.
 */
@Composable
private fun PolicyLine(@StringRes sentence: Int) {
    val context = LocalContext.current
    val label = stringResource(R.string.onboarding_policy_link)
    val text = stringResource(sentence, label)
    val linkInk = PwnBlueInk
    val line = remember(text, label, linkInk) {
        buildAnnotatedString {
            append(text)
            val at = text.indexOf(label)
            if (at >= 0) {
                addStyle(
                    SpanStyle(color = linkInk, textDecoration = TextDecoration.Underline),
                    at,
                    at + label.length,
                )
            }
        }
    }
    Text(
        line,
        // The 48dp a finger is measured against, which a bodySmall line is half of, and a role and
        // a label so the one target inside another target is announced as its own.
        modifier = Modifier.minimumInteractiveComponentSize()
            .clickable(role = Role.Button, onClickLabel = label) { context.showPrivacyPolicy() },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** The three questions the wheels ask, each beside the one thing its answer is used for. */
private val PERMISSIONS_AHEAD = listOf(
    Triple(R.drawable.ic_art_birth_date, R.string.age_title, R.string.onboarding_ahead_age_why),
    Triple(R.drawable.ic_art_height, R.string.height, R.string.onboarding_ahead_height_why),
    Triple(R.drawable.ic_art_weight, R.string.weight, R.string.onboarding_ahead_weight_why),
)

/**
 * What the questions are for, said before they are asked rather than after.
 *
 * It is written the way the app works rather than the way a consent page usually reads. Nothing
 * here is filled in from somewhere else for you to confirm: Health Connect keeps no date of birth
 * and no sex, so only a height and a weight could ever have come back, and they are asked for after
 * the wheels rather than before them — not to prefill them but to decide whether the figure just
 * typed is news. The rows are a list, so a question that changes is a line and not a screen.
 */
@Composable
private fun PermissionsAheadScreen(onNext: () -> Unit) {
    OnboardingPage(
        title = stringResource(R.string.onboarding_ahead_title),
        lead = stringResource(R.string.onboarding_ahead_body),
        action = {
            OnboardingAction(
                stringResource(R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                onNext,
            )
        },
    ) {
        PERMISSIONS_AHEAD.forEach { (art, name, why) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(themePainterResource(art), contentDescription = null, modifier = Modifier.size(40.dp))
                Column {
                    Text(stringResource(name), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(why),
                        style = MaterialTheme.typography.bodyMedium,
                        color = PwnMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_ahead_more),
            style = MaterialTheme.typography.bodyMedium,
            color = PwnMuted,
        )
        Spacer(Modifier.height(14.dp))
        PolicyLine(R.string.onboarding_ahead_policy)
    }
}

@Composable
private fun AgeScreen(
    birthDate: String?,
    onBirthDate: (String?) -> Unit,
    onNext: () -> Unit,
) {
    val today = rememberToday()
    val locale = LocalLocale.current.platformLocale
    // Every wheel opens on its blank row unless a date was already given on this page. Opened on a
    // date with Next lit, they would let tapping straight through record an adult and open
    // everything the age gate closes — the populated age screen Play's families review names. The
    // answer has to be the person's own, all three parts of it.
    val initial = remember { birthDate?.let(LocalDate::parse) }
    var year by rememberSaveable { mutableIntStateOf(initial?.year ?: BIRTH_UNSET) }
    var month by rememberSaveable { mutableIntStateOf(initial?.monthValue ?: BIRTH_UNSET) }
    var day by rememberSaveable { mutableIntStateOf(initial?.dayOfMonth ?: BIRTH_UNSET) }
    val lastMonth = lastBirthMonth(year, today)
    val validMonth = month.coerceAtMost(lastMonth)
    val lastDay = lastBirthDay(year, validMonth, today)
    val validDay = day.coerceAtMost(lastDay)
    val picked = shownBirthDate(year, month, day, today)
    // One tick for the three wheels, held by the page: the day and month wheels are rebuilt while
    // another wheel turns, and each would build a SoundPool of its own every time it was.
    val tick = rememberRulerTick()
    OnboardingPage(
        title = stringResource(R.string.age_title),
        lead = stringResource(R.string.age_body),
        action = {
            // Next waits for a whole date, as it waits for a card on the sex page.
            OnboardingAction(
                stringResource(R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                {
                    if (picked != null) {
                        onBirthDate(picked.toString())
                        onNext()
                    }
                },
                enabled = picked != null,
            )
        },
    ) {
        // The date the wheels add up to, spoken rather than printed. A wheel shows its choice by
        // typography alone, and none of the three announces the row under the highlight, so without
        // this the page would have nothing that said what Next would commit.
        // Not merged: the three wheels stay reachable and turnable, this only labels the group.
        // While a wheel is still blank there is no date to say, and the group says that instead.
        val spokenFormat = remember(locale) { localizedDatePattern(locale, "yMMMMd", "d MMMM yyyy") }
        val spoken = picked?.format(spokenFormat)
            ?: stringResource(R.string.visit_not_set)
        val order = remember(locale) { birthWheelOrder(locale) }
        Surface(
            Modifier.semantics { contentDescription = spoken },
            shape = RoundedCornerShape(PwnCorners.extraLarge),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Recreate a shortened wheel so February cannot retain a centred 31st. The key is also
                // the last row [keptBirthRow] is told about, so a wheel never outlives the one it knows.
                order.forEach { field ->
                    when (field) {
                        'd' -> Box(Modifier.weight(0.8f)) {
                            androidx.compose.runtime.key(lastDay) {
                                Wheel(
                                    listOf(BIRTH_UNSET) + (1..lastDay),
                                    validDay,
                                    { if (it == BIRTH_UNSET) BIRTH_UNSET_ROW else it.toString() },
                                    { day = keptBirthRow(it, day, lastDay) },
                                    tick,
                                )
                            }
                        }

                        'M' -> Box(Modifier.weight(1.2f)) {
                            androidx.compose.runtime.key(lastMonth) {
                                Wheel(
                                    listOf(BIRTH_UNSET) + (1..lastMonth),
                                    validMonth,
                                    {
                                        if (it == BIRTH_UNSET) {
                                            BIRTH_UNSET_ROW
                                        } else {
                                            java.time.Month.of(it)
                                                .getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, locale)
                                        }
                                    },
                                    { month = keptBirthRow(it, month, lastMonth) },
                                    tick,
                                )
                            }
                        }

                        else -> Box(Modifier.weight(1f)) {
                            Wheel(
                                birthYears(today),
                                year,
                                { if (it == BIRTH_UNSET) BIRTH_UNSET_ROW else it.toString() },
                                { year = it },
                                tick,
                            )
                        }
                    }
                }
            }
        }
        // The age the wheels add up to, rolling as they turn: the one figure on the page the person
        // can check at a glance, and the one the rest of the app is going to read. Not drawn until
        // they add up to a date: an age under blank wheels would be an answer nobody has given.
        if (picked != null) {
            val age = ageOn(today.toString(), picked.toString())
            Spacer(Modifier.height(20.dp))
            RollingFigure(
                pluralStringResource(R.plurals.labs_years, age, age),
                age.toDouble(),
                MaterialTheme.typography.headlineMedium
                    .copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * The order the day ('d'), month ('M') and year ('y') wheels stand in: the order [pattern], the
 * language's own way of writing a date, names them in — the month first in American English, the
 * year first in Japanese, Chinese and Korean — as the platform's own date picker orders its spinners.
 * Quoted words are not fields: Spanish writes
 * "d 'de' MMM 'de' y". A pattern that does not name all three leaves the day-first order.
 */
internal fun birthWheelOrder(pattern: String): List<Char> =
    pattern.replace(Regex("'[^']*'"), "")
        .map { if (it == 'L') 'M' else it }
        .filter { it in "dMy" }
        .distinct()
        .takeIf { it.size == 3 }
        ?: listOf('d', 'M', 'y')

/** The same order for [locale], read from the date pattern the platform gives it. */
internal fun birthWheelOrder(locale: Locale): List<Char> =
    birthWheelOrder(
        runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd") }.getOrDefault(""),
    )

/** As far back as the field will go. Nobody using a step counter was born before it. */
private const val EARLIEST_BIRTH_YEAR = 1900

/** The row every birth-date wheel opens on, where no day, month or year has been turned to yet. */
internal const val BIRTH_UNSET = 0

/** What that row prints: the dash the app shows wherever a value is missing. */
private const val BIRTH_UNSET_ROW = "—"

/**
 * The year wheel's rows: every year back to [EARLIEST_BIRTH_YEAR], then the blank one it opens on.
 * The blank stands after this year rather than before the first, so the rows a careless flick lands
 * on are the youngest ages — the ones the age gate opens nothing for.
 */
internal fun birthYears(today: LocalDate): List<Int> = (EARLIEST_BIRTH_YEAR..today.year) + BIRTH_UNSET

/**
 * The last day the day wheel offers: today's in the month that is still running, since nobody was
 * born tomorrow, else the month's own length. A blank month offers all thirty-one and a blank year
 * counts as a leap year, so the wheels can be turned in any order and the 29th of February is there
 * before its year is.
 */
internal fun lastBirthDay(year: Int, month: Int, today: LocalDate): Int = when {
    month == BIRTH_UNSET -> 31
    year == today.year && month == today.monthValue -> today.dayOfMonth
    else -> java.time.YearMonth.of(if (year == BIRTH_UNSET) LEAP_YEAR else year, month).lengthOfMonth()
}

/** Any leap year: what a blank year wheel counts as when February's length is asked for. */
private const val LEAP_YEAR = 2000

/**
 * What a day or month wheel's report makes of [kept], the row the person last turned it to.
 *
 * A wheel cut short — February has no 31st, this year no month after this one — is put on its
 * [last] row and reports that row, and the year wheel passes this year on its way up from the blank
 * one. Taking that report would trim a 25th of December to today's date before the year the person
 * was turning to came up. So a report of the row the wheel was put on changes nothing, and the
 * longer wheel, when it returns, stands where it was left; a row turned to by hand is always taken.
 */
internal fun keptBirthRow(reported: Int, kept: Int, last: Int): Int =
    if (reported == kept.coerceAtMost(last)) kept else reported

/**
 * The date the three wheels add up to, or null while any of them still stands on its blank row.
 * Null is what keeps Next unlit: no date is recorded that the person did not turn to themselves.
 */
internal fun pickedBirthDate(year: Int, month: Int, day: Int): LocalDate? =
    if (year == BIRTH_UNSET || month == BIRTH_UNSET || day == BIRTH_UNSET) null else LocalDate.of(year, month, day)

/** The last month the month wheel offers: this one in the year still running, else December. */
internal fun lastBirthMonth(year: Int, today: LocalDate): Int = if (year == today.year) today.monthValue else 12

/**
 * The date Next commits: the rows the wheels were turned to, each cut back to the last row its wheel
 * offers now, as the wheels themselves show them — so a 31st kept from December reads as today while
 * the year wheel stands on this one, and as the 29th of February in a leap year.
 */
internal fun shownBirthDate(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? {
    val shownMonth = month.coerceAtMost(lastBirthMonth(year, today))
    return pickedBirthDate(year, shownMonth, day.coerceAtMost(lastBirthDay(year, shownMonth, today)))
}

/**
 * Which side of the wheel the profile starts on: the phone's measurement setting ([measure], the
 * locale's `ms` value) where it has one, else the country it is set to — four places weigh people
 * in pounds and measure them in feet. Only a starting point: the height and weight pages carry the
 * switch.
 */
internal fun localeUnitSystem(country: String, measure: String? = null): String = when (measure) {
    "metric" -> "METRIC"
    "ussystem", "uksystem" -> "IMPERIAL"
    else -> if (country.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) "IMPERIAL" else "METRIC"
}

/**
 * The measurement system set in the phone's regional preferences, carried as the locale's `ms`
 * extension: the system locale's, which a per-app language can leave without it, else the app's.
 */
private fun measureSetting(app: Locale): String? =
    Resources.getSystem().configuration.locales[0]?.getUnicodeLocaleType("ms")
        ?: app.getUnicodeLocaleType("ms")

private val IMPERIAL_COUNTRIES = setOf("US", "GB", "LR", "MM")

@Composable
private fun SexScreen(sex: String, onSex: (String) -> Unit, onNext: () -> Unit) {
    val male = stringResource(R.string.sex_male)
    val female = stringResource(R.string.sex_female)
    OnboardingPage(
        title = stringResource(R.string.sex),
        action = {
            // Nothing is chosen for the person: the page opens with both cards empty and Next waits.
            OnboardingAction(
                stringResource(R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                onNext,
                enabled = sex.isNotEmpty(),
            )
        },
    ) {
        // Two answers of which one is chosen: a radio group to a screen reader, which then says which
        // of the two it is on and that it is selected.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SEXES.forEach { choice ->
                val chosen = sex == choice
                Surface(
                    onClick = { onSex(choice) },
                    modifier = Modifier.weight(1f).semantics {
                        selected = chosen
                        role = Role.RadioButton
                    },
                    shape = RoundedCornerShape(PwnCorners.extraLarge),
                    color = if (chosen) PwnIndigoSoft else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(2.dp, choiceEdge(chosen)),
                ) {
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Icon(
                            if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            null,
                            Modifier.align(Alignment.End),
                            tint = if (chosen) PwnIndigo else PwnMuted,
                        )
                        Icon(
                            if (choice == "MALE") Icons.Outlined.Male else Icons.Outlined.Female,
                            null,
                            Modifier.size(80.dp),
                            // The chosen card's symbol wears the accent, the other the resting ink,
                            // as the navigation bar's stops do.
                            tint = if (chosen) PwnIndigo else PwnMuted,
                        )
                        Text(
                            if (choice == "MALE") male else female,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** The two supported profile values, in the order the cards show them. */
private val SEXES = listOf("MALE", "FEMALE")

/**
 * The one question here that is not about a body measurement: what the person knows about their own
 * allergies.
 *
 * Three answers, and the middle one is why the page exists. "I have never been checked" is not a
 * shorter way of saying "I have none" — it is the state most people are actually in, it is what a
 * doctor needs told, and it is what lets the check-ups page offer the test later. Ticking nothing
 * and walking past is allowed and means exactly the first of those: the page can be skipped, and a
 * skipped page is recorded as unanswered rather than as an absence of allergies.
 *
 * The list is the fourteen a food label has to declare. It is not the whole of what a person can
 * react to — a medicine or a bee sting is not on it — and the line under the list says so rather
 * than letting the fourteen stand for everything.
 */
@Composable
private fun AllergyScreen(
    answer: AllergyAnswer,
    onAnswer: (AllergyAnswer) -> Unit,
    onNext: () -> Unit,
) {
    OnboardingPage(
        title = stringResource(R.string.allergy_title),
        lead = stringResource(R.string.allergy_body),
        action = {
            OnboardingAction(
                stringResource(if (answer == AllergyAnswer.Unanswered) R.string.common_skip else R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                onNext,
            )
        },
    ) {
        // The two answers that are not a list, and only while the list is empty. Once somebody has
        // named an allergen, "none that I know of" and "never been tested" are answers to a
        // question they have already answered otherwise — and tapping one would silently throw the
        // names away. Unticking the last one brings them back.
        if (answer.allergens().isEmpty()) {
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AllergyChoiceCard(
                    label = stringResource(R.string.allergy_not_tested),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    chosen = answer == AllergyAnswer.NotTested,
                    modifier = Modifier.weight(1f),
                ) { onAnswer(AllergyAnswer.NotTested) }
                // Not a tick: a tick is what a chosen card wears, and this answer wearing one while
                // unchosen would look as if it had already been picked.
                AllergyChoiceCard(
                    label = stringResource(R.string.allergy_none_known),
                    icon = Icons.Outlined.Block,
                    chosen = answer == AllergyAnswer.NoneKnown,
                    modifier = Modifier.weight(1f),
                ) { onAnswer(AllergyAnswer.NoneKnown) }
            }
            Spacer(Modifier.height(18.dp))
        }
        var picking by rememberSaveable { mutableStateOf(false) }
        PwnCard(Modifier.fillMaxWidth()) {
            AllergyPickerRow(answer) { picking = true }
        }
        if (picking) {
            AllergyPickerSheet(answer = answer, onAnswer = onAnswer, onDismiss = { picking = false })
        }
    }
}

/**
 * One of the two answers that is not a list: a card that reads as chosen or not at a glance. The
 * mark in its corner is the sex cards' own, so the choice is shown by a shape and not by two tints.
 */
@Composable
private fun AllergyChoiceCard(
    label: String,
    icon: ImageVector,
    chosen: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            selected = chosen
            role = Role.RadioButton
        },
        shape = RoundedCornerShape(PwnCorners.largeIncreased),
        color = if (chosen) PwnIndigoSoft else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(2.dp, choiceEdge(chosen)),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                null,
                Modifier.align(Alignment.End),
                tint = if (chosen) PwnIndigo else PwnMuted,
            )
            Icon(icon, null, Modifier.size(34.dp), tint = if (chosen) PwnIndigo else PwnMuted)
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The edge of an answer card. Unchosen it is the outline role, Material's colour for the boundary of
 * something that can be tapped; the outline-variant role is meant for decoration and stands under
 * the 3:1 a control's edge owes the page.
 */
@Composable
private fun choiceEdge(chosen: Boolean): Color = if (chosen) PwnIndigo else PwnControlEdge

@Composable
private fun HeightScreen(
    heightCm: Int,
    imperial: Boolean,
    onImperial: (Boolean) -> Unit,
    onHeightCm: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val unit = stringResource(R.string.unit_cm)
    // Rounded, not truncated: truncating both directions would make 67 in convert back to the same
    // 170 cm it came from, which would pin the imperial ruler to one value no matter the drag.
    val value = if (imperial) (heightCm / CENTIMETRES_IN_AN_INCH).roundToInt() else heightCm
    val tick: (Int) -> String = { if (imperial) "${it / 12}′ ${it % 12}″" else it.toString() }
    OnboardingPage(
        title = stringResource(R.string.height),
        action = {
            OnboardingAction(
                stringResource(R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                onNext,
            )
        },
    ) {
        // The app's own control, not a second one that measures the same thing differently: the
        // vertical tick ruler the height card in the editor is built on, dragged the same way and
        // reading the same numbers. Vertical because height is.
        UnitSwitch(imperial, onImperial)
        RulerReadout(if (imperial) tick(value) else "$value $unit")
        VerticalRulerPicker(
            // Remembered, not rebuilt: 200 boxed Ints, and the ruler recomposes on every frame of
            // a drag. Only the unit system can change what is in the list.
            values = remember(imperial) { (if (imperial) 20..98 else 50..250).toList() },
            value = value,
            onValue = {
                onHeightCm((if (imperial) (it * CENTIMETRES_IN_AN_INCH).roundToInt() else it).coerceIn(50, 250))
            },
            label = tick,
            // Said with the value: TalkBack reaches the ruler on its own, after the heading has gone by.
            name = stringResource(R.string.height),
        )
    }
}

@Composable
private fun WeightScreen(
    weightKg: Double,
    heightCm: Int,
    birthDate: String?,
    imperial: Boolean,
    onImperial: (Boolean) -> Unit,
    onWeightKg: (Double) -> Unit,
    onNext: () -> Unit,
) {
    val unit = stringResource(if (imperial) R.string.unit_lb else R.string.unit_kg)
    val locale = LocalLocale.current.platformLocale
    // Tenths, on the ruler as in the profile: a whole kilogram is too coarse a step for a number
    // people watch, and the ruler counts in whichever unit the page is printed in.
    val value = if (imperial) (weightKg * POUNDS_IN_A_KILOGRAM * 10).roundToInt() else (weightKg * 10).roundToInt()
    OnboardingPage(
        title = stringResource(R.string.weight),
        action = {
            OnboardingAction(
                stringResource(R.string.common_next),
                Icons.AutoMirrored.Outlined.ArrowForward,
                onNext,
            )
        },
    ) {
        UnitSwitch(imperial, onImperial)
        RulerReadout("${String.format(locale, "%.1f", value / 10.0)} $unit")
        RulerPicker(
            values = remember(imperial) { (if (imperial) 441..8_818 else 200..4_000).toList() },
            value = value,
            onValue = {
                onWeightKg((if (imperial) it / 10.0 / POUNDS_IN_A_KILOGRAM else it / 10.0).coerceIn(PLAUSIBLE_KG))
            },
            label = { String.format(locale, "%.1f", it / 10.0) },
            // The page's whole question, so the ruler stands at the size the work ruler does.
            height = ONBOARDING_RULER_HEIGHT,
            spacing = ONBOARDING_TICK_SPACING,
            name = stringResource(R.string.weight),
        )
        Spacer(Modifier.height(36.dp))
        val today = rememberToday()
        val index = bmi(weightKg, heightCm)
        val label = bmiLabelRes(index, birthDate.orEmpty(), today)
        BmiScale(
            bmi = index,
            labelRes = label,
            graded = label in GRADED_BMI_LABELS,
        )
    }
}

/** The four verdicts that are a place on WHO's adult line; anything else is no verdict at all. */
private val GRADED_BMI_LABELS = setOf(R.string.bmi_under, R.string.bmi_normal, R.string.bmi_over, R.string.bmi_high)

/**
 * The daily step goal, chosen rather than handed out.
 *
 * In the ENGAGE trial (JAMA Cardiology 2021, 500 adults) the one arm that walked more, and kept
 * walking more after the trial ended, was the one whose goal people picked themselves and that
 * started at once. So the page starts on the age band's suggestion, and the ruler is the person's
 * to move. Beside the goal stands what it means at their own stride, worked out from the height
 * they just gave.
 */
@Composable
private fun StepGoalScreen(
    goal: Long,
    suggested: Long,
    heightCm: Int,
    imperial: Boolean,
    onGoal: (Long) -> Unit,
    onNext: () -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    val grouped = { steps: Long -> String.format(locale, "%,d", steps) }
    OnboardingPage(
        title = stringResource(R.string.onboarding_steps_title),
        lead = stringResource(R.string.onboarding_steps_body),
        action = {
            OnboardingAction(stringResource(R.string.common_next), Icons.AutoMirrored.Outlined.ArrowForward, onNext)
        },
    ) {
        RollingFigure(
            grouped(goal),
            goal.toDouble(),
            MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        )
        val distance = formatDistance(LocalResources.current, goal * strideLengthM(heightCm), imperial)
        Text(
            stringResource(R.string.onboarding_steps_distance, distance),
            style = MaterialTheme.typography.bodyLarge,
            color = PwnMuted,
        )
        Spacer(Modifier.height(16.dp))
        RulerPicker(
            values = remember { (STEP_GOAL_MIN..STEP_GOAL_MAX step STEP_GOAL_STEP).toList() },
            value = goal.toInt().coerceIn(STEP_GOAL_MIN, STEP_GOAL_MAX),
            onValue = { onGoal(it.toLong()) },
            label = { grouped(it.toLong()) },
            height = ONBOARDING_RULER_HEIGHT,
            spacing = ONBOARDING_TICK_SPACING,
            name = stringResource(R.string.onboarding_steps_title),
        )
        Spacer(Modifier.height(20.dp))
        // The suggestion stays in sight once the ruler has left it, with a way straight back to it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.onboarding_steps_suggested, grouped(suggested)),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = PwnMuted,
            )
            if (goal != suggested) {
                androidx.compose.material3.TextButton(onClick = { onGoal(suggested) }) {
                    Text(stringResource(R.string.onboarding_steps_use_suggested))
                }
            }
        }
    }
}

/** A ruler that is a page's whole question, at the size the work ruler stands at. */
private val ONBOARDING_RULER_HEIGHT = 96.dp
private val ONBOARDING_TICK_SPACING = 16.dp

private const val STEP_GOAL_MIN = 2_000
private const val STEP_GOAL_MAX = 25_000
private const val STEP_GOAL_STEP = 250

/** The profile's own measurement switch, over the ruler: the choice goes into the profile it starts. */
@Composable
private fun UnitSwitch(imperial: Boolean, onImperial: (Boolean) -> Unit) {
    PwnSegmentedControl(
        options = listOf(
            stringResource(R.string.measurement_metric) to null,
            stringResource(R.string.measurement_imperial) to null,
        ),
        selected = if (imperial) 1 else 0,
        onSelected = { onImperial(it == 1) },
    )
    Spacer(Modifier.height(20.dp))
}

/** The number over a ruler, large: the ruler is where the value is chosen, this only reads it out. */
@Composable
private fun RulerReadout(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
}

@Composable
internal fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
    decimal: Boolean = false,
) {
    val separator = rememberDecimalSeparator()
    val text = rememberSyncedText(value, onValueChange)
    androidx.compose.material3.OutlinedTextField(
        state = text.state,
        inputTransformation = text.handOff,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(suffix) },
        // A decimal field stores the dot [decimalInput] folds every separator to, and shown as stored would
        // read "7.5" in the minutes of a set beside "3,5" in the rest of a Russian screen.
        outputTransformation = if (decimal) separator else null,
        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.SingleLine,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (decimal) {
                androidx.compose.ui.text.input.KeyboardType.Decimal
            } else {
                androidx.compose.ui.text.input.KeyboardType.Number
            },
        ),
    )
}

internal fun String.digitsOnly(): String = filter(Char::isDigit).take(4)

/**
 * Every character a keyboard this app is translated for sends when the person means "and now the
 * fraction". A non-ASCII digit is folded to ASCII here, and the separator beside it has to be too:
 * on the Arabic keyboard — `values-ar/` ships with the app — a dropped U+066B would turn 1٫5 into
 * 15, an order of magnitude out, with nothing refused and nothing shown. The two fullwidth forms
 * come from the same CJK keyboards that send the fullwidth digits.
 */
internal const val DECIMAL_SEPARATORS = ",.\u066B\uFF0E\uFF0C"

/**
 * The gram field's filter: what survives a keystroke is exactly what [String.toDoubleOrNull] — the
 * one thing every consumer of these fields reads them back with — will accept.
 *
 * A digit that is not ASCII is rewritten into the ASCII one it stands for rather than dropped. An
 * Arabic, Devanagari or fullwidth keyboard sends ٧٢ on a Decimal field, and Double.parseDouble
 * takes ASCII alone, so keeping the keystroke as typed would leave the save path reading a filled
 * field as nothing at all — a routine would become ten minutes and a load be discarded — while dropping it
 * would leave the field looking dead in three locales this app ships translations for.
 */
internal fun String.decimalInput(): String {
    val normalized = map { char ->
        if (char in DECIMAL_SEPARATORS) '.' else char.digitToIntOrNull()?.let { '0' + it } ?: char
    }
        .filter { it in '0'..'9' || it == '.' }
        .joinToString("")
    val dot = normalized.indexOf('.')
    return if (dot < 0) {
        normalized.take(7)
    } else {
        normalized.substring(0, dot).take(7) + "." + normalized.substring(dot + 1).replace(".", "").take(2)
    }
}
