package com.hexnext.themer.theme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automatic backups: the user picks a folder once (any document provider: Downloads, Drive, a
 * memory card …), and after every successful apply Hex Next writes a [ThemeBackup] there as
 * `Hex Next backup (latest)` plus one dated copy per day, keeping the newest [KEEP_DATED]. Files
 * in a user folder outlive an uninstall or a factory reset, which app storage does not.
 *
 * The folder is a persisted tree permission; it means nothing on another phone, so its prefs file
 * (`hexnext_backup.xml`) is left out of cloud backup and device transfer.
 */
class AutoBackup private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class State(val folder: Uri? = null, val folderName: String? = null, val lastAt: Long = 0L, val lastError: String? = null)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Takes the folder the picker returned (OpenDocumentTree) and keeps the permission. */
    fun setFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { appContext.contentResolver.takePersistableUriPermission(tree, flags) }
        _state.value.folder?.takeIf { it != tree }?.let { old ->
            runCatching { appContext.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.edit().putString(KEY_FOLDER, tree.toString()).remove(KEY_ERROR).apply()
        _state.value = read()
    }

    /** Turns automatic backups off and gives the folder permission back. */
    fun clearFolder() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        _state.value.folder?.let { runCatching { appContext.contentResolver.releasePersistableUriPermission(it, flags) } }
        prefs.edit().remove(KEY_FOLDER).remove(KEY_ERROR).apply()
        _state.value = read()
    }

    /**
     * Writes the latest and today's dated backup into the folder with [write] and prunes old
     * dated copies. Blocking (call on IO). No-op without a folder. Returns false on failure,
     * which is also kept in [state] for the Tools page.
     */
    fun backUp(write: (java.io.OutputStream) -> Unit): Boolean {
        val tree = _state.value.folder ?: return true
        val result = runCatching {
            val resolver = appContext.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (name in listOf(ThemeBackup.fileName(dated = false), ThemeBackup.fileName(dated = true))) {
                val doc = findChild(tree, name) ?: DocumentsContract.createDocument(resolver, parent, ThemeBackup.MIME, name)
                    ?: error("The folder refused a new file")
                (resolver.openOutputStream(doc, "wt") ?: error("The backup file could not be opened")).use(write)
            }
            prune(tree)
        }
        prefs.edit().apply {
            if (result.isSuccess) putLong(KEY_LAST, System.currentTimeMillis()).remove(KEY_ERROR)
            else putString(KEY_ERROR, result.exceptionOrNull()?.message ?: "Backup failed")
        }.apply()
        _state.value = read()
        return result.isSuccess
    }

    private fun children(tree: Uri): List<Pair<String, Uri>> {
        val resolver = appContext.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<String, Uri>>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out.add(name to DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        return out
    }

    private fun findChild(tree: Uri, name: String): Uri? = children(tree).firstOrNull { it.first == name }?.second

    /** Keeps the newest [KEEP_DATED] dated backups (names sort by date). */
    private fun prune(tree: Uri) {
        val dated = children(tree).filter { DATED.matches(it.first) }.sortedByDescending { it.first }
        for ((_, uri) in dated.drop(KEEP_DATED)) runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
    }

    private fun read(): State {
        val folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        val stillGranted = folder != null && appContext.contentResolver.persistedUriPermissions.any { it.uri == folder && it.isWritePermission }
        return State(
            folder = folder.takeIf { stillGranted },
            folderName = folder?.takeIf { stillGranted }?.let { DocumentsContract.getTreeDocumentId(it).substringAfterLast(':').ifBlank { "Chosen folder" } },
            lastAt = prefs.getLong(KEY_LAST, 0L),
            lastError = prefs.getString(KEY_ERROR, null) ?: if (folder != null && !stillGranted) "Hex Next lost access to the backup folder. Choose it again." else null,
        )
    }

    companion object {
        private const val PREFS = "hexnext_backup"
        private const val KEY_FOLDER = "folder"
        private const val KEY_LAST = "last_at"
        private const val KEY_ERROR = "last_error"
        private const val KEY_DATED_SUFFIX = ThemeBackup.EXTENSION
        const val KEEP_DATED = 7
        private val DATED = Regex("^Hex Next backup \\d{4}-\\d{2}-\\d{2}\\.$KEY_DATED_SUFFIX$")

        @Volatile private var instance: AutoBackup? = null
        fun get(context: Context): AutoBackup = instance ?: synchronized(this) { instance ?: AutoBackup(context).also { instance = it } }
    }
}
