package app.overbay.twitter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.overbay.twitter.model.TwitterModConfig

/**
 * Interactive mute list editor supporting keywords, #hashtags, domains, and validated /regex/flags.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MutePatternsSection(
    patterns: List<String>,
    onAddPattern: (String) -> Unit,
    onRemovePattern: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val validationError = remember(draft) {
        if (draft.isBlank()) null else TwitterModConfig.validateMutePattern(draft)
    }
    val canSubmit = draft.isNotBlank() && validationError == null

    fun submitDraft() {
        if (canSubmit) {
            onAddPattern(draft.trim())
            draft = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Enforced at the GraphQL layer across tweets, URLs, and nested quote-tweets. Supports plain text, @handles, domains, or /regex/i.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = validationError != null,
                placeholder = { Text("word, domain.com, or /pattern/i") },
                supportingText = validationError?.let { err -> { Text(err) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submitDraft() })
            )
            FilledTonalIconButton(
                onClick = { submitDraft() },
                enabled = canSubmit,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(AppIcons.Add, contentDescription = "Add mute pattern")
            }
        }

        if (patterns.isEmpty()) {
            Text(
                text = "No custom mute rules active.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                patterns.forEach { pattern ->
                    val isRegex = pattern.startsWith("/") && pattern.lastIndexOf('/') > 0
                    InputChip(
                        selected = true,
                        onClick = { onRemovePattern(pattern) },
                        label = {
                            Text(
                                text = pattern,
                                fontFamily = if (isRegex) FontFamily.Monospace else FontFamily.Default
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = "Remove $pattern",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}
