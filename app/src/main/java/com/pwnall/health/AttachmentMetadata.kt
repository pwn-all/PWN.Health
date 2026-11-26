package com.pwnall.health

import org.json.JSONArray
import org.json.JSONObject

/** A patient's description, never inferred from a filename or a MIME type. */
enum class AttachmentKind(val titleRes: Int) {
    UNKNOWN(R.string.attachment_kind_unknown),
    CT(R.string.attachment_kind_ct),
    MRI(R.string.attachment_kind_mri),
    ULTRASOUND(R.string.attachment_kind_ultrasound),
    XRAY(R.string.attachment_kind_xray),
    DOPPLER(R.string.attachment_kind_doppler),
    LAB(R.string.attachment_kind_lab),
    PRESCRIPTION(R.string.attachment_kind_prescription),
    OTHER(R.string.attachment_kind_other),
}

data class MedicalAttachment(
    val uri: String,
    val kind: AttachmentKind = AttachmentKind.UNKNOWN,
    val description: String = "",
    /** Optional ISO date of the examination, independent of the appointment's date. */
    val studyDay: String = "",
)

/** Reads both the original newline list and the metadata envelope without a database migration. */
internal fun medicalAttachments(stored: String): List<MedicalAttachment> {
    if (!stored.trimStart().startsWith('{')) {
        return stored.split('\n').filter { it.isNotBlank() }.map { MedicalAttachment(it) }
    }
    return runCatching {
        val files = JSONObject(stored).getJSONArray("files")
        (0 until files.length()).mapNotNull { index ->
            val file = files.getJSONObject(index)
            val uri = file.optString("uri")
            if (uri.isBlank()) return@mapNotNull null
            MedicalAttachment(
                uri = uri,
                kind = AttachmentKind.entries.firstOrNull { it.name == file.optString("kind") }
                    ?: AttachmentKind.UNKNOWN,
                description = file.optString("description"),
                studyDay = file.optString("studyDay"),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun storeMedicalAttachments(files: List<MedicalAttachment>): String {
    if (files.all { it.kind == AttachmentKind.UNKNOWN && it.description.isEmpty() && it.studyDay.isEmpty() }) {
        return storeAttachments(files.map { it.uri })
    }
    val rows = files.map {
        JSONObject().put("uri", it.uri).put("kind", it.kind.name)
            .put("description", it.description).put("studyDay", it.studyDay)
    }
    return JSONObject().put("schema", 1).put("files", JSONArray(rows)).toString()
}

/** Adding imported scans must not discard descriptions already entered for other attachments. */
internal fun addMedicalAttachments(stored: String, uris: List<String>): String {
    val existing = medicalAttachments(stored)
    return storeMedicalAttachments((existing + uris.map { MedicalAttachment(it) }).distinctBy { it.uri })
}
