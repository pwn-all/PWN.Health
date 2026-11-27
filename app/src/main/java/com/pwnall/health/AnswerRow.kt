package com.pwnall.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * An answer already in, on a page that asks one question at a time: what was asked, what was said,
 * and — when [onEdit] is given — the pencil that opens the question again in its place.
 *
 * The cycle page's day asks this way, and the pain page copies it; both draw their answers with
 * this one row so the two read as the same control. Each passes its own [container]: the cycle page
 * wears its own skin; the pencil wears the app's resting icon ink. Without [onEdit] the answer is
 * one the page will not reopen — carried over from an earlier entry — and the row is plain text,
 * with no pencil to promise otherwise; such a row can drop the [question] when its [answer] says on
 * its own where it came from.
 */
@Composable
internal fun PwnAnswerRow(
    question: String?,
    answer: String,
    container: Color,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    val label = stringResource(R.string.common_edit)
    Row(
        modifier.fillMaxWidth()
            // Clipped before the tap so the pressed and focused layer keeps the row's corners
            // rather than drawing a rectangle over them.
            .clip(ANSWER_ROW_SHAPE)
            .background(container)
            .then(
                if (onEdit == null) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onEdit)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            question?.let { stringResource(R.string.cycle_answer_row, it, answer) } ?: answer,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (onEdit != null) {
            // Silent: the row already names its action, and a pencil with a name of its own would be
            // read out as a second "Edit" inside the row's label.
            Icon(
                Icons.Outlined.Edit,
                contentDescription = null,
                tint = PwnMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Material's medium corner, the one the app's choice and tick fields are drawn with too. */
private val ANSWER_ROW_SHAPE = RoundedCornerShape(PwnCorners.medium)
