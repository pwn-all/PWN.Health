package com.pwnall.health

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/** Configurable shortcuts open an entry menu; dedicated one-tap widgets keep their own actions. */
enum class WidgetSlot(val labelRes: Int, val iconRes: Int, val route: String) {
    WATER(R.string.water_title, R.drawable.ic_art_water, WidgetRoute.WATER),
    FOOD(R.string.add_meal, R.drawable.ic_art_food, WidgetRoute.FOOD),
    EXERCISE(R.string.add_section_activity, R.drawable.ic_art_sport, WidgetRoute.EXERCISE),
    CIGARETTE(R.string.smoking_title, R.drawable.ic_art_smoking, WidgetRoute.SMOKING),
    MOOD(R.string.mood_title, R.drawable.ic_art_mood, WidgetRoute.MOOD),
    URINATION(R.string.bathroom_title, R.drawable.ic_art_toilet, WidgetRoute.BATHROOM),
    CAFFEINE(R.string.caffeine_title, R.drawable.ic_art_coffee, WidgetRoute.CAFFEINE),
    ALCOHOL(R.string.alcohol_title, R.drawable.ic_art_alcohol, WidgetRoute.ALCOHOL),
    INTIMATE(R.string.sexual_title, R.drawable.ic_art_sex, WidgetRoute.INTIMATE),
    CYCLE(R.string.cycle_title, R.drawable.ic_art_cycle, WidgetRoute.CYCLE),
    BREATHING(R.string.breathing_title, R.drawable.ic_art_breathing, WidgetRoute.BREATHING),
}

val DEFAULT_WIDGET_SLOTS = listOf(WidgetSlot.WATER, WidgetSlot.FOOD, WidgetSlot.EXERCISE)
const val WIDGET_SLOT_COUNT = 6

internal fun offeredWidgetSlots(profile: UserProfile?, today: LocalDate): List<WidgetSlot> =
    WidgetSlot.entries.filter {
        when (it) {
            WidgetSlot.CIGARETTE, WidgetSlot.ALCOHOL -> habitsOffered(profile?.birthDate, today)
            WidgetSlot.INTIMATE -> sexLogOffered(profile?.birthDate, today)
            WidgetSlot.CYCLE -> profile?.sex == "FEMALE"
            else -> true
        }
    }

/**
 * The per-widget configuration.
 *
 * Keyed by the widget id rather than stored once for the app: two multi widgets on two home
 * screens are two different questions — one for the desk, one for the gym — and sharing a row
 * between them would make the second one useless.
 */
object WidgetSlots {
    private const val PREFS = "widget_slots"

    fun slots(context: Context, widgetId: Int, offered: List<WidgetSlot>): List<WidgetSlot> =
        widgetSlotsOf(prefs(context).getString(key(widgetId), null), offered)

    fun save(context: Context, widgetId: Int, slots: List<WidgetSlot>) {
        prefs(context).edit { putString(key(widgetId), slots.joinToString(",") { it.name }) }
    }

    /** Called when a widget is removed, so its row does not outlive it in the preferences. */
    fun forget(context: Context, widgetIds: IntArray) {
        prefs(context).edit { widgetIds.forEach { remove(key(it)) } }
    }

    private fun key(widgetId: Int) = "slots_$widgetId"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The stored row as the profile now allows it: the chosen count, order and repeats are kept, a
 * name this build does not know is dropped, so is a slot this age is not [offered] — a restored
 * backup can change whose profile this is under a widget already placed — and a row with nothing
 * left falls back to the defaults, which every age is offered.
 */
internal fun widgetSlotsOf(stored: String?, offered: List<WidgetSlot> = WidgetSlot.entries): List<WidgetSlot> = stored
    ?.split(',')
    ?.mapNotNull { name -> WidgetSlot.entries.firstOrNull { it.name == name } }
    ?.filter { it in offered }
    ?.take(WIDGET_SLOT_COUNT)
    ?.takeIf { it.isNotEmpty() }
    ?: DEFAULT_WIDGET_SLOTS

/**
 * One tap on the smoking widget, one cigarette — through the app's own button's guard
 * ([StepsDb.logSmoking]), so a tap that lands twice on a home screen is swallowed exactly as it would
 * be inside the app, and redrawn once it is written, as the app's is. Says whether anything was
 * written. A copy of the guard here could ask after any tobacco row rather than a cigarette, and drop
 * the widget's cigarette a second after a heated stick logged in the app.
 *
 * The age check is the last line of defence rather than the only one: the widget's provider is
 * switched off entirely for a profile too young for it. This catches the widget that was already
 * on the home screen when a restored backup changed whose profile this is.
 */
suspend fun logWidgetCigarette(context: Context): Boolean {
    val db = StepsDb.get(context)
    val today = LocalDate.now()
    if (!habitsOffered(db.dao().profileNow()?.birthDate, today)) return false
    return db.logSmoking(smokingEntry(context.resources, TobaccoProduct.CIGARETTE, today))
}
