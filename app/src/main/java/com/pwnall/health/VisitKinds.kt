package com.pwnall.health

/**
 * Who an appointment is with, as the booking form's "Who" list offers it: one kind of doctor for
 * every kind of care in [CareType] that a doctor is booked for.
 *
 * [CareType.EMERGENCY] and [CareType.HOSPITAL] have no entry. They are kinds of place, not of
 * doctor: nobody books an emergency department, and a hospital is where many of these are seen.
 *
 * [stored] is what [Appointment.specialty] holds, and none of these words may ever be renamed:
 * rows written by every earlier build hold them. Five kinds are the doctor of a routine examination
 * and store that [Checkup]'s name, so booking a dentist is still the dentist check and marking it as
 * having happened still winds that clock. The ophthalmologist is the one whose name differs from
 * its examination's: it stores [Checkup.EYES], the eye examination's name, so an eye visit written
 * down under either name is the same visit. The other names
 * are the ones [DoctorSpecialty] already gives the same doctor, so the report and the diary say one
 * word for one doctor.
 *
 * [care] is the register's kind of care the place suggestions are filtered by; [careTypes] widens
 * it for the one doctor who has no department of their own.
 *
 * [searchRes] is the other words the same doctor goes by, which the list's search finds as well as
 * the name it shows: a person looking for a dentist types "дантист" or "dental", and the row says
 * "Стоматолог" or "Dentist". They are never printed.
 */
enum class VisitKind(
    val care: CareType,
    val titleRes: Int,
    val checkup: Checkup? = null,
    val searchRes: Int? = null,
) {
    DENTIST(CareType.DENTISTRY, R.string.checkup_dentist, Checkup.DENTIST, R.string.visit_kind_search_dentist),
    GYNECOLOGIST(
        CareType.GYNAECOLOGY,
        R.string.checkup_gynecologist,
        Checkup.GYNECOLOGIST,
        R.string.visit_kind_search_gynecologist,
    ),
    GP(CareType.PRIMARY_CARE, R.string.checkup_gp, Checkup.GP, R.string.visit_kind_search_gp),
    PAEDIATRICIAN(
        CareType.PAEDIATRICS,
        R.string.visit_kind_paediatrician,
        searchRes = R.string.visit_kind_search_paediatrician,
    ),
    CARDIOLOGIST(
        CareType.CARDIOLOGY,
        R.string.doctor_cardiologist,
        searchRes = R.string.visit_kind_search_cardiologist,
    ),
    DERMATOLOGIST(
        CareType.DERMATOLOGY,
        R.string.visit_kind_dermatologist,
        searchRes = R.string.visit_kind_search_dermatologist,
    ),
    OPHTHALMOLOGIST(
        CareType.OPHTHALMOLOGY,
        R.string.visit_kind_ophthalmologist,
        Checkup.EYES,
        R.string.visit_kind_search_ophthalmologist,
    ),
    ENT(CareType.ENT, R.string.visit_kind_ent, searchRes = R.string.visit_kind_search_ent),
    ORTHOPAEDIST(
        CareType.ORTHOPAEDICS,
        R.string.visit_kind_orthopaedist,
        searchRes = R.string.visit_kind_search_orthopaedist,
    ),
    NEUROLOGIST(CareType.NEUROLOGY, R.string.visit_kind_neurologist),
    PSYCHIATRIST(CareType.PSYCHIATRY, R.string.visit_kind_psychiatrist),
    PSYCHOLOGIST(
        CareType.PSYCHOLOGY,
        R.string.doctor_psychologist,
        searchRes = R.string.visit_kind_search_psychologist,
    ),
    UROLOGIST(CareType.UROLOGY, R.string.checkup_urologist, Checkup.UROLOGIST),
    ENDOCRINOLOGIST(CareType.ENDOCRINOLOGY, R.string.doctor_endocrinologist),
    GASTROENTEROLOGIST(
        CareType.GASTROENTEROLOGY,
        R.string.visit_kind_gastroenterologist,
        searchRes = R.string.visit_kind_search_gastroenterologist,
    ),
    LABORATORY(CareType.LABORATORY, R.string.visit_kind_laboratory, searchRes = R.string.visit_kind_search_laboratory),
    IMAGING(CareType.IMAGING, R.string.visit_kind_imaging, searchRes = R.string.visit_kind_search_imaging),
    REHABILITATION(
        CareType.REHABILITATION,
        R.string.visit_kind_rehabilitation,
        searchRes = R.string.visit_kind_search_rehabilitation,
    ),
    SURGEON(CareType.SURGERY, R.string.visit_kind_surgeon),
    ONCOLOGIST(CareType.ONCOLOGY, R.string.visit_kind_oncologist),
    ALLERGIST(CareType.ALLERGOLOGY, R.string.visit_kind_allergist),
    PULMONOLOGIST(CareType.PULMONOLOGY, R.string.visit_kind_pulmonologist),
    NEPHROLOGIST(CareType.NEPHROLOGY, R.string.visit_kind_nephrologist),
    RHEUMATOLOGIST(CareType.RHEUMATOLOGY, R.string.visit_kind_rheumatologist),
    INFECTIOUS_DISEASES(CareType.INFECTIOUS_DISEASES, R.string.visit_kind_infectious_diseases),
    DIETITIAN(CareType.NUTRITION, R.string.doctor_dietitian),
    SPORTS(CareType.SPORTS_MEDICINE, R.string.doctor_sports),
    SOMNOLOGIST(
        CareType.SLEEP_MEDICINE,
        R.string.doctor_somnologist,
        searchRes = R.string.visit_kind_search_somnologist,
    ),
    ;

    /** What [Appointment.specialty] holds for this kind: its examination's name where it has one. */
    val stored: String get() = checkup?.name ?: name

    /**
     * The kinds of care a place must offer to be suggested for this doctor. A sleep doctor is
     * rarely a department of their own — none of the three registers lists one today — and the work
     * is done in neurology and in pulmonology, so those count too: sleep medicine alone would answer
     * every "near me" with nothing.
     */
    val careTypes: Set<CareType>
        get() = if (this == SOMNOLOGIST) setOf(care, CareType.NEUROLOGY, CareType.PULMONOLOGY) else setOf(care)
}

/**
 * The kind an appointment's [specialty] names, or null for a screening, another doctor, or a word
 * from a newer build.
 */
fun visitKindOf(specialty: String): VisitKind? = VisitKind.entries.firstOrNull { it.stored == specialty }

/**
 * The examinations that are a test rather than a doctor — cervical screening, mammography, bowel
 * screening — which the "Who" list offers beside the doctors because booking one is booking a visit.
 */
val SCREENING_CHECKUPS: List<Checkup> =
    Checkup.entries.filter { checkup -> VisitKind.entries.none { it.checkup == checkup } }

/**
 * The "Who" list as stored values, in the order it is shown.
 *
 * First the examinations in [applicable] — the ones [checkupsFor] gives this person, in the order
 * it gives them — then every other kind of doctor in [order] of its
 * [label], then "another doctor". Every kind of doctor is offered to everybody: a woman sees a
 * urologist and a child a dentist, and only the examination clocks keep to an age and a sex. A
 * screening that does not apply is left out, except when it is [current]: a mammography
 * booked at seventy-four and opened at seventy-five still says what it was.
 *
 * A [current] that is not known here at all — a word from a newer build — is not added: the field
 * reads it as another doctor, which is what [specialtyTitleRes] calls it too, and leaves it stored
 * as it is until a choice is made.
 */
fun visitChoices(
    applicable: List<Checkup>,
    current: String,
    label: (String) -> String,
    order: Comparator<in String>,
): List<String> {
    val first = applicable.map(Checkup::name)
    val doctors = VisitKind.entries.map(VisitKind::stored).filter { it !in first }.sortedWith(compareBy(order, label))
    val kept = listOf(current).filter { it !in first && Checkup.entries.any { checkup -> checkup.name == it } }
    return (first + kept + doctors + VISIT_SPECIALTY_OTHER).distinct()
}

/**
 * The kind of a booking nobody has chosen yet. A new booking opens with it, so the "Who" list is a
 * question still to answer rather than one answered as [VISIT_SPECIALTY_OTHER] for the person, and
 * it can never be saved: [visitMissing] holds the booking until a kind is picked.
 */
const val VISIT_SPECIALTY_UNANSWERED = ""

/** What a booking still needs before it can be saved, in the order the form asks for it. */
enum class VisitMissing { PLACE, KIND, DOCTOR }

/**
 * Null when the visit can be saved; otherwise the first thing missing, the one the line under Save
 * names, as the pain page names its first unanswered question. Blank is missing: a field holding
 * only spaces is saved trimmed, as empty.
 *
 * A booking needs the place, the kind of doctor and the doctor. A visit being written down after it
 * happened ([recording]) needs only its kind, which is what winds an examination clock: a mammogram
 * from two years ago or a blood test seldom has a doctor's name to give, and a name made up to get
 * past Save would then print on the doctor's report as if it were true.
 */
fun visitMissing(place: String, specialty: String, doctor: String, recording: Boolean): VisitMissing? = when {
    !recording && place.isBlank() -> VisitMissing.PLACE
    specialty.isBlank() -> VisitMissing.KIND
    !recording && doctor.isBlank() -> VisitMissing.DOCTOR
    else -> null
}

/**
 * The doctors, or the places, already typed into an appointment — most recent first, once each.
 *
 * For a doctor, [atPlace] splits the list in two: the first holds the doctors seen at that place
 * before, the second everybody else. The registers name places and no doctors, so this is the one
 * honest answer to "who is at this place": the person's own visits there. The match is on the text
 * with case and spacing ignored, because the same clinic typed twice is seldom typed alike.
 */
fun typedBefore(
    appointments: List<Appointment>,
    place: Boolean,
    atPlace: String = "",
): Pair<List<String>, List<String>> {
    val recent = appointments.sortedByDescending(Appointment::atMs)
    val all = recent.map { if (place) it.place.trim() else it.doctor.trim() }.filter(String::isNotEmpty).distinct()
    val here = placeKey(atPlace)
    if (place || here.isEmpty()) return emptyList<String>() to all
    val seenHere = recent.filter { placeKey(it.place) == here }
        .map { it.doctor.trim() }
        .filter(String::isNotEmpty)
        .distinct()
    return seenHere to all.filter { it !in seenHere }
}

private fun placeKey(place: String): String = place.trim().lowercase().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("$SPACE+")
