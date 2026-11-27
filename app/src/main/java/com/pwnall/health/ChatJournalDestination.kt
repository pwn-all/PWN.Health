package com.pwnall.health

internal enum class ChatJournalDestination {
    SYMPTOM,
    PAIN,
    MOOD,
    MEDICINE,
    FOOD,
    WORKOUT,
    WORKOUT_PLAN,
    WORK,
    MEASUREMENT,
    SLEEP,
    BREATHING,
    WATER,
    CAFFEINE,
    ALCOHOL,
    SMOKING,
    BATHROOM,
    CYCLE,
    INTIMATE,
    LABS,
    PROFILE,
    DOCUMENT,
}

/** The tap opens the existing confirmation form; it never calls a writer or a timer command. */
internal fun ChatJournalEvent.destination(): ChatJournalDestination? {
    if (!canOfferForm) return null
    val plannedForms = setOf(
        ChatJournalType.WORKOUT_PLAN,
        ChatJournalType.APPOINTMENT_LOG,
        ChatJournalType.COURSE,
        ChatJournalType.ATTACHMENT,
    )
    if (attributes["phase"] == "PLANNED" && type !in plannedForms) return null
    return JOURNAL_DESTINATIONS.getValue(type)
}

private val JOURNAL_DESTINATIONS = mapOf(
    ChatJournalType.SYMPTOM to ChatJournalDestination.SYMPTOM,
    ChatJournalType.PAIN to ChatJournalDestination.PAIN,
    ChatJournalType.MOOD to ChatJournalDestination.MOOD,
    ChatJournalType.DOSE to ChatJournalDestination.MEDICINE,
    ChatJournalType.COURSE to ChatJournalDestination.MEDICINE,
    ChatJournalType.APPOINTMENT_LOG to ChatJournalDestination.MEDICINE,
    ChatJournalType.FOOD_LOG to ChatJournalDestination.FOOD,
    ChatJournalType.WORKOUT_LOG to ChatJournalDestination.WORKOUT,
    ChatJournalType.WORKOUT_PLAN to ChatJournalDestination.WORKOUT_PLAN,
    ChatJournalType.WORK_LOG to ChatJournalDestination.WORK,
    ChatJournalType.WORK_TIMER to ChatJournalDestination.WORK,
    ChatJournalType.SLEEP_LOG to ChatJournalDestination.SLEEP,
    ChatJournalType.WEIGHT_LOG to ChatJournalDestination.MEASUREMENT,
    ChatJournalType.HEIGHT_LOG to ChatJournalDestination.MEASUREMENT,
    ChatJournalType.HEART_RATE_LOG to ChatJournalDestination.MEASUREMENT,
    ChatJournalType.GIRTH_LOG to ChatJournalDestination.MEASUREMENT,
    ChatJournalType.BREATHING_LOG to ChatJournalDestination.BREATHING,
    ChatJournalType.WATER_LOG to ChatJournalDestination.WATER,
    ChatJournalType.CAFFEINE_LOG to ChatJournalDestination.CAFFEINE,
    ChatJournalType.ALCOHOL_LOG to ChatJournalDestination.ALCOHOL,
    ChatJournalType.SMOKING_LOG to ChatJournalDestination.SMOKING,
    ChatJournalType.STOOL_LOG to ChatJournalDestination.BATHROOM,
    ChatJournalType.URINATION_LOG to ChatJournalDestination.BATHROOM,
    ChatJournalType.PERIOD_START to ChatJournalDestination.CYCLE,
    ChatJournalType.PERIOD_END to ChatJournalDestination.CYCLE,
    ChatJournalType.CYCLE_NOTE to ChatJournalDestination.CYCLE,
    ChatJournalType.SEX_LOG to ChatJournalDestination.INTIMATE,
    ChatJournalType.MORNING_CHECK to ChatJournalDestination.INTIMATE,
    ChatJournalType.LAB_RESULT_LOG to ChatJournalDestination.LABS,
    ChatJournalType.ATTACHMENT to ChatJournalDestination.DOCUMENT,
    ChatJournalType.MEDICAL_HISTORY_LOG to ChatJournalDestination.PROFILE,
    ChatJournalType.ALLERGY_UPDATE to ChatJournalDestination.PROFILE,
)
