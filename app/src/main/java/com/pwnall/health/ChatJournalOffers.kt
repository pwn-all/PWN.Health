package com.pwnall.health

import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The record types the chat offers a form for to [profile] on [today] ([ChatSetting.journal]): never a
 * symptom's, which is this chat itself; alcohol and smoking only from the age their pages open at; the
 * sex life's only where its page is offered; the cycle's only to a woman, the one profile its page is for.
 */
internal fun journalTypesOffered(profile: UserProfile, today: LocalDate): Set<ChatJournalType> =
    ChatJournalType.entries.filterTo(mutableSetOf()) { type ->
        when (type) {
            ChatJournalType.SYMPTOM -> false

            ChatJournalType.ALCOHOL_LOG, ChatJournalType.SMOKING_LOG -> habitsOffered(profile.birthDate, today)

            ChatJournalType.PERIOD_START, ChatJournalType.PERIOD_END, ChatJournalType.CYCLE_NOTE ->
                profile.sex == SexAudience.FEMALE.name

            ChatJournalType.SEX_LOG -> sexLogOffered(profile.birthDate, today)

            ChatJournalType.MORNING_CHECK -> sexLogOffered(profile.birthDate, today) && morningCheckOffered(profile.sex)

            else -> true
        }
    }

/**
 * The records [message] could become, of the types [offered]: what a reading the chat may route by
 * proposed in it ([ChatRead.routes]: never the shadow's), its words still where the reading found
 * them, the person's own and affirmed ([destination]). Each carries the message as its source. A
 * proposal whose words overlap an earlier one of the same type is that one read twice — «съел 200 г
 * гречки» comes back as a meal at «съел» and a meal at «200 г гречки» — and is one card, not two.
 */
internal fun journalProposals(message: ChatTurn.Wrote, offered: Set<ChatJournalType>): List<ChatJournalEvent> =
    message.read?.takeIf { it.routes }?.journal.orEmpty()
        .filter { it.type in offered && it.groundedIn(message.text) && it.destination() != null }
        .fold(emptyList<ChatJournalEvent>()) { kept, event ->
            if (kept.any { it.type == event.type && it.wordsOverlap(event) }) kept else kept + event
        }
        .map { it.copy(sourceText = message.text) }

/** Whether the words of [this] and [other] — anchor and arguments, end to end — share a character. */
private fun ChatJournalEvent.wordsOverlap(other: ChatJournalEvent): Boolean {
    val (start, end) = wordEnds()
    val (otherStart, otherEnd) = other.wordEnds()
    return start < otherEnd && otherStart < end
}

/** Where the words of [this] begin and end: its anchor and arguments together. */
private fun ChatJournalEvent.wordEnds(): Pair<Int, Int> {
    val spans = listOf(anchor) + arguments.map { it.span }
    return spans.minOf { it.start } to spans.maxOf { it.end }
}

/** [journalProposals] of [message], the message at [ordinal] of the thread [thread] (its id), as the answer's cards. */
internal fun journalCards(
    message: ChatTurn?,
    thread: Int,
    ordinal: Int,
    offered: Set<ChatJournalType>,
): List<BotPiece.Journal> = (message as? ChatTurn.Wrote)?.let { journalProposals(it, offered) }.orEmpty()
    .map { BotPiece.Journal(it, thread, ordinal) }

/** The records a chat's own threads ask about: beside a topic the chat takes up, they are no card of their own. */
internal val CHAT_OWN_JOURNAL = setOf(
    ChatJournalType.SYMPTOM,
    ChatJournalType.PAIN,
    ChatJournalType.MOOD,
    ChatJournalType.DOSE,
    ChatJournalType.COURSE,
)

/**
 * What the person changed on a record card before Add (the day by a picker,
 * the amount typed, the meal's food searched for and picked as in the food page): null is what the
 * message said. [food] is a catalogue food picked, its figures per 100 g; [grams] its portion.
 */
internal data class JournalDraft(
    val day: LocalDate? = null,
    val millilitres: String? = null,
    val grams: String? = null,
    val query: String? = null,
    val food: ChatFoodDraft? = null,
)

/**
 * What a record card does in a live chat: its [draft][draftOf] and its changes; Add — saved from the
 * card where it holds the whole record ([cardRow]), the record's own form opened otherwise; Cancel;
 * and the form again for a card whose form was opened.
 */
internal class JournalActions(
    val draftOf: (BotPiece.Journal) -> JournalDraft,
    val onDraft: (BotPiece.Journal, JournalDraft) -> Unit,
    val onAdd: (BotPiece.Journal, JournalDraft) -> Unit,
    val onDecline: (BotPiece.Journal) -> Unit,
    val onReopen: (BotPiece.Journal) -> Unit,
)

/**
 * A record the person's words could become, as the bot's answer to them:
 * "New record"; the record's drawing and name over the words it was read from, and the
 * amount; the fields its record takes — a meal's food and grams, a drink's millilitres, the day — each
 * changed in place and the card following it; Cancel beside Add. Add saves water and a meal with a
 * catalogue food from the card, centre flash and all, and opens any other record's own form filled
 * in. Once done the card folds to its one line: not saved, opened in its form, or saved with Open. A
 * past chat, or a host that opens nothing, draws it with no fields and no buttons.
 */
@Composable
internal fun JournalCard(piece: BotPiece.Journal, inputs: TurnInputs) {
    val mark = inputs.log.markOf(piece)
    val actions = inputs.offers?.journal
    val draft = actions?.draftOf?.invoke(piece) ?: JournalDraft()
    val live = actions != null && mark == null
    Surface(shape = BOT_SHAPE, color = PwnSurfaceElevated, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(PwnDimensions.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mark == null) {
                Text(
                    stringResource(R.string.editor_new_title),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleSmall,
                    color = PwnMuted,
                )
            }
            JournalCardItem(piece.event, draft, mark)
            if (mark != null) {
                JournalCardDone(piece, mark, actions, inputs)
                return@Column
            }
            if (live) JournalCardFields(piece, draft, checkNotNull(actions))
            // A chat read back has no offers, and its cards keep the day it was held on.
            val base = cardBaseDay(inputs.setting.past || inputs.offers == null, inputs.setting.day, inputs.today)
            journalDay(piece.event, draft, base, inputs.today)?.let { day ->
                JournalCardDay(day, inputs.today, enabled = live && !inputs.busy) { picked ->
                    actions?.onDraft?.invoke(piece, draft.copy(day = picked))
                }
            }
            if (live) JournalCardButtons(piece, draft, checkNotNull(actions), busy = inputs.busy)
        }
    }
}

/** The card's one line: the drawing, the record's name over the words it came from, and the amount on the end. */
@Composable
private fun JournalCardItem(event: ChatJournalEvent, draft: JournalDraft, mark: CardMark?) {
    val locale = LocalLocale.current.platformLocale
    val saved = mark as? CardMark.Added
    val food = draft.food?.takeIf { event.type == ChatJournalType.FOOD_LOG && saved == null }
    val grams = draft.grams ?: food?.state?.serving.orEmpty()
    val drink = event.prefillDrink()
    val title = saved?.title?.takeIf { it.isNotBlank() } ?: food?.state?.title
        ?: stringResource(drink?.titleRes ?: event.type.titleRes)
    val cupKcal = drink?.takeIf { mark == null }?.let { cup ->
        event.millilitres(draft)?.let { nutrientsForCup(cup, it).kcal }
    }
    val detail = food?.let { stringResource(R.string.entry_energy, it.state.copy(serving = grams).portionCalories) }
        ?: cupKcal?.let { stringResource(R.string.entry_energy, it) }
        ?: event.phrase()
    val status = when (mark) {
        CardMark.Declined -> stringResource(R.string.symptom_chat_record_not_saved)

        // "Saved", and the day where it is not today: the moment the row was written stands for it.
        is CardMark.Added -> listOfNotNull(
            stringResource(R.string.work_flash_saved),
            mark.day().takeIf { it != LocalDate.now() }?.let { journalDayText(it, LocalDate.now(), locale) },
        ).joinToString(" · ")

        CardMark.Opened, null -> null
    }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(themePainterResource(event.type.artRes), null, Modifier.size(JOURNAL_ART))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PwnMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PwnMuted) }
        }
        // In the accent while it is offered; a card done with rests in grey with the rest of it.
        event.amountText(draft, saved)?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (mark == null) PwnIndigo else PwnMuted,
            )
        }
    }
}

/**
 * The fields of a card that saves its record itself: a drink's millilitres — water, or a cup whose
 * drink its words named; a meal's food, searched for in the catalogue as the food page does it and
 * picked, and its grams. Any other record's card changes only its day, its form taking the rest.
 */
@Composable
private fun JournalCardFields(piece: BotPiece.Journal, draft: JournalDraft, actions: JournalActions) {
    val event = piece.event
    val drink = event.prefillDrink()
    when {
        event.type == ChatJournalType.WATER_LOG || drink != null -> NumberField(
            value = draft.millilitres ?: event.initialMillilitres().orEmpty(),
            onValueChange = { actions.onDraft(piece, draft.copy(millilitres = it.digitsOnly())) },
            label = stringResource(drink?.titleRes ?: R.string.water_title),
            suffix = stringResource(R.string.unit_ml),
        )

        event.type == ChatJournalType.FOOD_LOG && event.prefillFoods().size <= 1 ->
            JournalCardFood(piece, draft, actions)
    }
}

/** A meal's food: the catalogue's search over the words the meal was read from, then the food picked, and its grams. */
@Composable
private fun JournalCardFood(piece: BotPiece.Journal, draft: JournalDraft, actions: JournalActions) {
    val locale = LocalLocale.current.platformLocale
    val said = piece.event.prefillFoods().singleOrNull()?.state
    val grams = draft.grams ?: said?.serving.orEmpty()
    val picked = draft.food
    if (picked == null) {
        val query = draft.query ?: said?.title ?: piece.event.anchor.text
        val results =
            if (query.trim().length < MIN_QUERY) emptyList() else rememberFoodSearch(query, limit = SUGGESTIONS)
        FoodSearchField(query, { actions.onDraft(piece, draft.copy(query = it)) }, FoodKind.MEAL, onPhoto = null)
        if (results.isNotEmpty()) {
            CatalogInlineSuggestions(results) { food ->
                val state = FoodState(serving = grams).withJournalCatalogFood(food, locale)
                actions.onDraft(piece, draft.copy(food = ChatFoodDraft(state, food.id), grams = grams))
            }
        }
    } else {
        SelectedFoodCard(
            picked.state.copy(serving = grams),
            onClick = { actions.onDraft(piece, draft.copy(food = null)) },
            onClear = { actions.onDraft(piece, draft.copy(food = null, query = "")) },
        )
    }
    val start = picked?.catalogId?.let(TOP_FOODS_BY_ID::get)?.defaultPortion?.grams ?: DEFAULT_GRAMS
    PortionStepper(grams, start, stringResource(R.string.editor_portion_weight)) {
        actions.onDraft(piece, draft.copy(grams = it.decimalInput()))
    }
}

/**
 * The day the record would be filed under, in a panel of its own; a tap picks another, today or
 * earlier, where the card is live.
 */
@Composable
private fun JournalCardDay(day: LocalDate, today: LocalDate, enabled: Boolean, onPick: (LocalDate) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    var picking by rememberSaveable { mutableStateOf(false) }
    val change = stringResource(R.string.common_edit)
    Surface(
        onClick = { picking = true },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics {
            role = Role.Button
            onClick(label = change) {
                picking = true
                true
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.CalendarToday, null, Modifier.size(18.dp), tint = PwnMuted)
            Text(journalDayText(day, today, locale), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (enabled) Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp), tint = PwnMuted)
        }
    }
    if (picking) {
        PastDateDialog(initial = day, onDismiss = { picking = false }) {
            picking = false
            onPick(it)
        }
    }
}

/**
 * Cancel and Add, half the card each. The labels wrap rather than end in an ellipsis and the buttons
 * have a floor rather than a height, as the appointment's pair does; nothing is done while the turn
 * waits for a reading ([TurnInputs.busy]), nor is a card Added that does not hold a whole record.
 */
@Composable
private fun JournalCardButtons(piece: BotPiece.Journal, draft: JournalDraft, actions: JournalActions, busy: Boolean) {
    val title = stringResource(piece.event.type.titleRes)
    val addLabel = stringResource(R.string.symptom_chat_record_open_label, title)
    val half = Modifier.fillMaxHeight().heightIn(min = PwnDimensions.mediumButtonHeight)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PwnOutlinedButton(
            onClick = { actions.onDecline(piece) },
            modifier = half.weight(1f),
            enabled = !busy,
            contentPadding = JOURNAL_BUTTON_PADDING,
        ) { Text(stringResource(R.string.common_cancel), textAlign = TextAlign.Center) }
        Button(
            onClick = { actions.onAdd(piece, draft) },
            modifier = half.weight(1f).semantics {
                onClick(label = addLabel) {
                    actions.onAdd(piece, draft)
                    true
                }
            },
            enabled = !busy && piece.event.addable(draft),
            contentPadding = JOURNAL_BUTTON_PADDING,
        ) { Text(stringResource(R.string.common_add), textAlign = TextAlign.Center) }
    }
}

/** A card done with: Open for the record it saved, or for the form it opened, while the chat is live. */
@Composable
private fun JournalCardDone(piece: BotPiece.Journal, mark: CardMark, actions: JournalActions?, inputs: TurnInputs) {
    val open: (() -> Unit)? = when (mark) {
        is CardMark.Added -> inputs.open?.let { open -> { open(ChatRecordRef(mark.kind, mark.at, mark.id)) } }
        CardMark.Opened -> actions?.let { { it.onReopen(piece) } }
        CardMark.Declined -> null
    }
    if (open == null) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        PwnOutlinedButton(onClick = open, enabled = !inputs.busy) { Text(stringResource(R.string.history_open)) }
    }
}

/**
 * The day a card's words are counted from — its "today", its "yesterday", and the day it is filed under
 * where they name none: the chat's own day in a chat for an earlier day and in one read back ([past]),
 * and [today] in one being had now. Such a chat's page keeps the day it was begun on, so one begun
 * before midnight and gone on with after it would otherwise file "today I drank…" under yesterday,
 * while its threads' own records go by the moment ([ChatFiling]).
 *
 * Limitation: a message keeps no moment of its own, so a card left unanswered over midnight counts
 * from the new day as well, its day standing on it to be changed.
 */
internal fun cardBaseDay(past: Boolean, chatDay: LocalDate, today: LocalDate): LocalDate = if (past) chatDay else today

/**
 * The day [event] would be filed under, changed or as the message said it counted from [base]
 * ([cardBaseDay]), never after [today]; none for a record whose form files under today whatever the
 * card would say ([DAY_FORMS]) — a cup the card saves itself has one, the caffeine dialog none.
 */
internal fun journalDay(
    event: ChatJournalEvent,
    draft: JournalDraft,
    base: LocalDate,
    today: LocalDate,
): LocalDate? {
    val kept = event.type in DAY_FORMS || event.prefillDrink() != null
    return if (kept) minOf(draft.day ?: event.prefillDay(base) ?: base, today) else null
}

/** Whether Add can go on: a drink's whole millilitres, a picked food's grams; any other form takes the rest. */
internal fun ChatJournalEvent.addable(draft: JournalDraft): Boolean = when (type) {
    ChatJournalType.WATER_LOG -> millilitres(draft) != null
    ChatJournalType.CAFFEINE_LOG -> prefillDrink() == null || millilitres(draft) != null
    ChatJournalType.FOOD_LOG -> draft.food == null || (draft.grams ?: draft.food.state.serving).number() > 0
    else -> true
}

/** The drink [draft] would save: whole millilitres, from one to [MAX_MILLILITRES], a cup to [MAX_CUP_MILLILITRES]. */
internal fun ChatJournalEvent.millilitres(draft: JournalDraft): Int? {
    val most = if (type == ChatJournalType.CAFFEINE_LOG) MAX_CUP_MILLILITRES else MAX_MILLILITRES
    return (draft.millilitres ?: initialMillilitres())?.toIntOrNull()?.takeIf { it in 1..most }
}

/**
 * The millilitres the message said, whole, as the field shows them; for a cup that said none, the
 * standard cup the caffeine dialog starts on.
 */
private fun ChatJournalEvent.initialMillilitres(): String? =
    (prefillWaterMl()?.roundToInt() ?: STANDARD_CUP_ML.takeIf { prefillDrink() != null })?.toString()

/**
 * The row Add saves from the card itself, or null where the card does not hold the whole record and
 * the record's own form is opened instead: water ([water] builds it as the water widget does), a cup
 * of the drink its words named ([cup] builds it as the caffeine dialog does, [cupEntry]), and a meal
 * of the one catalogue food picked, its columns as the food page writes them ([mealEntry]), noted
 * with the message as the form notes it.
 */
internal fun cardRow(
    event: ChatJournalEvent,
    draft: JournalDraft,
    day: LocalDate,
    water: (Int, String) -> JournalEntry,
    cup: (CaffeineDrink, Int, String) -> JournalEntry,
): JournalEntry? = when (event.type) {
    ChatJournalType.WATER_LOG -> event.millilitres(draft)?.let { water(it, day.toString()) }

    ChatJournalType.CAFFEINE_LOG -> event.prefillDrink()?.let { drink ->
        event.millilitres(draft)?.let { cup(drink, it, day.toString()) }
    }

    ChatJournalType.FOOD_LOG -> draft.food?.let { food ->
        listOf(food.copy(state = food.state.copy(serving = draft.grams ?: food.state.serving)))
            .takeIf { foods -> foods.all(ChatFoodDraft::valid) }
            ?.mealEntry(day.toString(), event.prefillNote())
    }

    else -> null
}

/**
 * A meal of [this] chat foods as the food page saves one ([EntryEditorScreen]'s commitSave, its EAT
 * columns): the foods' combined figures for the portion eaten, the drafts' payload and the catalogue
 * source, filed on [day]. The moment is the caller's.
 */
internal fun List<ChatFoodDraft>.mealEntry(day: String, note: String): JournalEntry {
    val food = combinedFood()
    return JournalEntry(
        day = day,
        occurredAt = 0L,
        kind = KIND_EAT,
        title = food.title.trim(),
        calories = food.portionCalories,
        subtitle = food.subtitle.trim(),
        proteinG = food.portionProtein,
        fatG = food.portionFat,
        carbsG = food.portionCarbs,
        servingG = food.servingG,
        fiberG = food.portionExtra(ExtraNutrient.FIBER),
        sugarG = food.portionExtra(ExtraNutrient.SUGAR),
        saturatedFatG = food.portionExtra(ExtraNutrient.SATURATED_FAT),
        sodiumMg = food.portionExtra(ExtraNutrient.SODIUM),
        cholesterolMg = food.portionExtra(ExtraNutrient.CHOLESTEROL),
        enteredPer100g = food.basis.per100,
        note = note.trim(),
        measurementPayloadJson = foodDraftPayload(null),
        foodSource = combinedFoodSource(),
    )
}

/** "Today", "Yesterday" in the language showing, and an earlier day by its date. */
internal fun journalDayText(day: LocalDate, today: LocalDate, locale: Locale): String {
    val relative = when (day) {
        today -> RelativeDateTimeFormatter.Direction.THIS
        today.minusDays(1) -> RelativeDateTimeFormatter.Direction.LAST
        else -> return painDayText("dMMMM", locale, day)
    }
    return RelativeDateTimeFormatter.getInstance(
        ULocale.forLocale(locale),
        null,
        RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
    ).format(relative, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
}

/** The words of the message the record was read from: from the first of its spans to the end of the last. */
internal fun ChatJournalEvent.phrase(): String? {
    val (start, end) = wordEnds()
    return sourceText?.takeIf { start in 0..<end && end <= it.length }?.substring(start, end)?.trim()?.ifEmpty { null }
}

/** The amount on the card's end, as the card stands or as it was [saved]: a drink's millilitres, one food's grams. */
@Composable
private fun ChatJournalEvent.amountText(draft: JournalDraft, saved: CardMark.Added?): String? {
    val locale = LocalLocale.current.platformLocale
    val amount = saved?.amount ?: when (type) {
        ChatJournalType.WATER_LOG -> draft.millilitres?.toDoubleOrNull() ?: prefillWaterMl()
        ChatJournalType.CAFFEINE_LOG -> (draft.millilitres ?: initialMillilitres())?.toDoubleOrNull()
        ChatJournalType.FOOD_LOG -> draft.grams?.number() ?: prefillFoods().singleOrNull()?.state?.servingG
        else -> null
    }
    val unit = when (type) {
        ChatJournalType.WATER_LOG, ChatJournalType.CAFFEINE_LOG -> R.string.unit_ml
        ChatJournalType.FOOD_LOG -> R.string.unit_grams
        else -> null
    }
    return if (amount == null || amount <= 0 ||
        unit == null
    ) {
        null
    } else {
        "${amount.asWeightText(locale)} ${stringResource(unit)}"
    }
}

/** The day of the row [this] card saved, in the phone's own time zone. */
private fun CardMark.Added.day(): LocalDate =
    java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

/**
 * The records whose form files them under the day the card shows ([MainActivity]'s openJournal hands
 * it on), and the two the card saves itself: every other form files under today, so its card names no
 * day it would not keep.
 */
private val DAY_FORMS = setOf(
    ChatJournalType.FOOD_LOG,
    ChatJournalType.WORKOUT_LOG,
    ChatJournalType.WORKOUT_PLAN,
    ChatJournalType.SLEEP_LOG,
    ChatJournalType.WEIGHT_LOG,
    ChatJournalType.HEIGHT_LOG,
    ChatJournalType.HEART_RATE_LOG,
    ChatJournalType.GIRTH_LOG,
    ChatJournalType.WATER_LOG,
)

/** [drafts] by card ([journalDraftKey]), for the Bundle a rotation and the page's return keep. */
internal fun encodeJournalDrafts(drafts: Map<String, JournalDraft>): String = JSONObject().apply {
    drafts.forEach { (key, draft) ->
        put(
            key,
            JSONObject().apply {
                draft.day?.let { put("day", it.toString()) }
                draft.millilitres?.let { put("ml", it) }
                draft.grams?.let { put("g", it) }
                draft.query?.let { put("q", it) }
                draft.food?.let { put("food", JSONArray(listOf(it).encodeChatFoods())) }
            },
        )
    }
}.toString()

/**
 * [encodeJournalDrafts] read back: text that is no JSON is none, and so is a draft that is no object
 * or a field of another type. A food is the one field read strictly ([decodeChatFoods]): one that does
 * not read throws, which what this build wrote itself never does.
 */
internal fun decodeJournalDrafts(text: String): Map<String, JournalDraft> {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
    return json.keys().asSequence().mapNotNull { key ->
        json.optJSONObject(key)?.let { draft ->
            key to JournalDraft(
                day = (draft.opt("day") as? String)?.let { it.toLocalDateOrNull() },
                millilitres = draft.opt("ml") as? String,
                grams = draft.opt("g") as? String,
                query = draft.opt("q") as? String,
                food = draft.optJSONArray("food")?.toString()?.decodeChatFoods()?.singleOrNull(),
            )
        }
    }.toMap()
}

/** The page's record card drafts across a rotation and a return: [encodeJournalDrafts]. */
internal val JOURNAL_DRAFTS_SAVER: Saver<Map<String, JournalDraft>, String> = Saver(
    save = { encodeJournalDrafts(it) },
    restore = { decodeJournalDrafts(it) },
)

/** A card's own key among the drafts of its chat: its thread, message, type and words. */
internal fun journalDraftKey(card: BotPiece.Journal): String =
    with(card.key) { "${card.thread}:$message:${type.name}:$start:$end" }

private val JOURNAL_ART = 44.dp
private val JOURNAL_BUTTON_PADDING = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

/** The catalogue's matches a card lists: the food page's inline three. */
private const val SUGGESTIONS = 3
private const val MIN_QUERY = 2

/** Where + starts an empty grams field with no catalogue food to suggest a portion. */
private const val DEFAULT_GRAMS = 100.0

/** The most water one card saves: a typed slip past a few litres is no drink. */
private const val MAX_MILLILITRES = 5_000

/** The most one cup saves: twice the caffeine dialog's largest. */
private const val MAX_CUP_MILLILITRES = 1_000
