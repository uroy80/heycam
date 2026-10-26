package heycam.app.pick

import androidx.annotation.Keep
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import heycam.app.R
import heycam.core.data.DocumentRepository
import heycam.core.designsystem.DocumentRow
import heycam.core.designsystem.Heycam
import heycam.core.designsystem.LoadBar
import heycam.core.designsystem.SheetFrame
import heycam.core.designsystem.TextButton
import heycam.core.model.DocumentSummary
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

/**
 * The tools that start from a document (PLAN F9). @Keep: Navigation looks a route's enum up by class name, which R8
 * would otherwise rename.
 */
@Keep
enum class DocumentTool { COMPRESS, EXTRACT_TEXT, PRINT }

/** "Pick a document", then [tool] continues with it. */
@Serializable data class PickDocumentRoute(val tool: DocumentTool)

internal fun NavGraphBuilder.documentPicker(onPicked: (tool: DocumentTool, documentId: Long) -> Unit, onDismiss: () -> Unit) {
    dialog<PickDocumentRoute> { entry ->
        val tool = entry.toRoute<PickDocumentRoute>().tool
        val viewModel: DocumentPickerViewModel = hiltViewModel()
        val documents by viewModel.documents.collectAsStateWithLifecycle()
        DocumentPicker(documents = documents, onPick = { onPicked(tool, it) }, onDismiss = onDismiss)
    }
}

@HiltViewModel
class DocumentPickerViewModel @Inject constructor(repository: DocumentRepository) : ViewModel() {
    /** Most recently changed first, trash excluded; null while loading. */
    val documents: StateFlow<List<DocumentSummary>?> = repository.observeRecent(limit = LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialValue = null)

    private companion object {
        const val LIMIT = 200
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

@Composable
internal fun DocumentPicker(documents: List<DocumentSummary>?, onPick: (documentId: Long) -> Unit, onDismiss: () -> Unit) {
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }
    SheetFrame(title = stringResource(R.string.app_pick_document), showHandle = false) {
        when {
            documents == null -> LoadBar(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            documents.isEmpty() -> Text(
                text = stringResource(R.string.app_no_documents),
                style = Heycam.type.body,
                color = Heycam.colors.muted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.weight(1f, fill = false)) {
                items(documents, key = { it.id.value }) { document ->
                    DocumentRow(
                        title = document.title,
                        meta = dates.format(document.updatedAt),
                        pageCount = document.pageCount,
                        onClick = { onPick(document.id.value) },
                        thumbnail = document.coverThumbPath?.let { path ->
                            {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(stringResource(R.string.app_cancel), onDismiss)
        }
    }
}
