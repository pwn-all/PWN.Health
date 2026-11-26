package com.pwnall.health

// What clinicians usually look at next, as a library somebody opens rather than as a card that
// fires at them.
//
// The split is the whole design, and it is not editorial taste. FDA's General Wellness guidance
// carries an explicit safe harbour: a product stays a general wellness product "even if it includes
// a notification informing a user that evaluation by a healthcare professional may be helpful when
// outputs fall outside ranges appropriate for general wellness use" — provided such a notification
// does not name a disease, does not call the output abnormal or diagnostic, and carries no clinical
// threshold, diagnosis or treatment recommendation. In the EU the same sentence attached to
// somebody's own value is Annex VIII Rule 11(a), which is a regulated device.
//
// So: a value the person entered may trigger one neutral sentence and nothing more. Everything
// here — what gets looked at next, what the figures mean, which index is calculated how — is
// reference material, the same for every reader, reached by choosing to read it. It never quotes
// the person's own number back at them and never fires on its own.
//
// The articles are plain string resources per panel, with no content model and no database: eight
// articles that change when guidance changes, which is once every few years.

/**
 * The eight articles, in the order the panels are printed on a sheet.
 *
 * Each one is written to be true of anybody rather than of the reader: "a result outside the range
 * is usually repeated before anything else happens" is a fact about practice; "your result should
 * be repeated" is a recommendation about a person, and that is the line.
 */
internal val LAB_LIBRARY: List<LabArticle> = listOf(
    LabArticle(LabPanel.GLYCEMIA, R.string.lab_library_glycemia, R.string.lab_library_source_glycemia),
    LabArticle(LabPanel.LIPIDS, R.string.lab_library_lipids, R.string.lab_library_source_lipids),
    LabArticle(LabPanel.KIDNEY, R.string.lab_library_kidney, R.string.lab_library_source_kidney),
    LabArticle(LabPanel.LIVER, R.string.lab_library_liver, R.string.lab_library_source_liver),
    LabArticle(LabPanel.BLOOD, R.string.lab_library_blood, R.string.lab_library_source_blood),
    LabArticle(
        LabPanel.INFLAMMATION,
        R.string.lab_library_inflammation,
        R.string.lab_library_source_inflammation,
    ),
    LabArticle(LabPanel.THYROID, R.string.lab_library_thyroid, R.string.lab_library_source_thyroid),
    // The notes a vitamin band cannot carry: a target the Endocrine Society no longer sets, the
    // laboratory's own B12 lines, a folate that follows recent intake, a magnesium the serum barely
    // holds. Transferrin saturation's notes go here too, rather than in the blood-count article.
    LabArticle(
        LabPanel.MICRONUTRIENTS,
        R.string.lab_library_micronutrients,
        R.string.lab_library_source_micronutrients,
    ),
)

/** One panel's article: what the sheet holds, and what is usually looked at next. */
internal data class LabArticle(
    val panel: LabPanel,
    val bodyRes: Int,
    /** The bodies whose guidance the article is written from, named so it can be checked. */
    val sourceRes: Int,
)
