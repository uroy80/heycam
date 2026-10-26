package heycam.core.data

import androidx.room3.withWriteTransaction
import heycam.core.common.Clock
import heycam.core.common.DispatcherProvider
import heycam.core.data.db.FolderEntity
import heycam.core.data.db.HeycamDatabase
import heycam.core.model.Folder
import heycam.core.model.FolderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
internal class RoomFolderRepository @Inject constructor(
    private val db: HeycamDatabase,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : FolderRepository {

    private val folderDao = db.folderDao()
    private val docDao = db.documentDao()

    override fun observeFolders(parentId: FolderId?): Flow<List<Folder>> =
        folderDao.observeAll()
            .map { rows ->
                rows.filter { it.parentId == parentId?.value }.map(Mappers::folder).sortedWith(Mappers.byName { it.name })
            }
            .distinctUntilChanged()

    override suspend fun create(name: String, parentId: FolderId?): FolderId = withContext(dispatchers.io) {
        val clean = Names.clean(name, Names.MAX_FOLDER)
        db.withWriteTransaction {
            val all = folderDao.all().associateBy { it.id }
            if (parentId != null) {
                if (parentId.value !in all) throw NoSuchElementException("folder ${parentId.value}")
                if (depth(all, parentId.value) >= FolderRepository.MAX_DEPTH) throw FolderTooDeepException()
            }
            requireFreeName(all.values, parentId?.value, clean, except = null)
            FolderId(folderDao.insert(FolderEntity(name = clean, parentId = parentId?.value, createdAt = clock.now().toEpochMilli())))
        }
    }

    override suspend fun rename(id: FolderId, name: String) = withContext(dispatchers.io) {
        val clean = Names.clean(name, Names.MAX_FOLDER)
        db.withWriteTransaction {
            val all = folderDao.all()
            val folder = all.firstOrNull { it.id == id.value }
            if (folder != null) {
                requireFreeName(all, folder.parentId, clean, except = folder.id)
                folderDao.rename(id.value, clean)
            }
        }
        Unit
    }

    /** Folders side by side may not share a name, ignoring case; [except] is the folder being renamed. */
    private fun requireFreeName(all: Collection<FolderEntity>, parentId: Long?, clean: String, except: Long?) {
        val key = folderNameKey(clean)
        if (all.any { it.id != except && it.parentId == parentId && folderNameKey(it.name) == key }) throw FolderNameInUseException()
    }

    override suspend fun delete(id: FolderId, moveDocumentsTo: FolderId?) = withContext(dispatchers.io) {
        db.withWriteTransaction {
            val folder = folderDao.get(id.value) ?: return@withWriteTransaction
            val target = moveDocumentsTo?.value?.takeIf { it != folder.id && folderDao.get(it) != null }
            docDao.moveAllFromFolder(folder.id, target)
            folderDao.reparentChildren(folder.id, folder.parentId)
            folderDao.delete(folder.id)
        }
    }

    /** Top-level folders are depth 1. */
    private fun depth(all: Map<Long, FolderEntity>, id: Long): Int =
        generateSequence(all[id]) { it.parentId?.let(all::get) }.take(FolderRepository.MAX_DEPTH + 1).count()
}
