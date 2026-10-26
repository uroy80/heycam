package heycam.feature.document

import heycam.core.data.FolderRepository
import heycam.core.model.Folder
import heycam.core.model.FolderId
import kotlinx.coroutines.flow.first

/** A folder in the "Move to" picker; depth 0 = top level. */
internal data class FolderNode(val folder: Folder, val depth: Int)

/** Folders nest at most three deep (PLAN §2 item 21). */
private const val MAX_FOLDER_DEPTH = 3

/** Every folder, depth-first and sorted by name. */
internal suspend fun FolderRepository.tree(): List<FolderNode> {
    val out = ArrayList<FolderNode>()
    suspend fun walk(parent: FolderId?, depth: Int) {
        if (depth >= MAX_FOLDER_DEPTH) return
        for (f in observeFolders(parent).first().sortedBy { it.name.lowercase() }) {
            out += FolderNode(f, depth)
            walk(f.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}
