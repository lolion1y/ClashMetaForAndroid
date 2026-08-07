package com.github.kr328.clash.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import com.github.kr328.clash.common.constants.Authorities
import java.io.File
import java.io.FileNotFoundException

class PrivateStorageProvider : DocumentsProvider() {
    private lateinit var rootDocumentId: String
    private lateinit var appName: String
    private var appIcon: Int = 0
    private val rootDirectories = linkedMapOf<String, File>()
    private var deferredRevocations: MutableList<String>? = null

    private val defaultRootProjection = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
    )

    private val defaultDocumentProjection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        COLUMN_MT_PATH,
        COLUMN_MT_EXTRAS,
    )

    override fun onCreate() = true

    override fun attachInfo(context: Context, info: ProviderInfo?) {
        super.attachInfo(context, info)

        rootDocumentId = context.packageName

        val appInfo = context.applicationInfo
        appName = appInfo.loadLabel(context.packageManager).toString()
        appIcon = appInfo.icon

        context.filesDir.parentFile?.let {
            rootDirectories[LABEL_DATA_DIR] = it
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext().filesDir.parentFile?.let {
                rootDirectories[LABEL_DE_DATA_DIR] = it
            }
        }
        context.getExternalFilesDir(null)?.parentFile?.let {
            rootDirectories[LABEL_EX_DATA_DIR] = it
        }
        context.obbDir?.let {
            rootDirectories[LABEL_OBB_DIR] = it
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: defaultRootProjection)

        if (!PrivateStorageAccess.isEnabled(context!!))
            return cursor

        return cursor.apply {
            newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, rootDocumentId)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, rootDocumentId)
                add(DocumentsContract.Root.COLUMN_SUMMARY, rootDocumentId)
                add(
                    DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                            DocumentsContract.Root.FLAG_LOCAL_ONLY or
                            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
                )
                add(DocumentsContract.Root.COLUMN_TITLE, appName)
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                add(DocumentsContract.Root.COLUMN_ICON, appIcon)
            }
        }
    }

    @Synchronized
    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?
    ): Cursor {
        enforceAccess()

        return MatrixCursor(projection ?: defaultDocumentProjection)
            .appendFileInfo(documentId)
    }

    @Synchronized
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceAccess()

        val cursor = MatrixCursor(projection ?: defaultDocumentProjection)

        if (parentDocumentId == rootDocumentId) {
            rootDirectories.forEach { (label, file) ->
                if (file.isDirectory)
                    cursor.appendFileInfo(rootDocumentId.appendChild(label), file)
            }
        } else {
            val directory = retrieveDirectory(parentDocumentId)
            val files = directory.listFiles()
                ?: throw FileNotFoundException("$parentDocumentId not found")

            if (files.size > MAX_DIRECTORY_ENTRIES)
                throw FileNotFoundException("Too many files in $parentDocumentId")

            files.forEach {
                cursor.appendFileInfo(parentDocumentId.appendChild(it.name), it)
            }
        }

        return cursor
    }

    @Synchronized
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        enforceAccess()

        val file = retrieveFile(documentId, followLinks = true)
            ?: throw FileNotFoundException("$documentId not found")
        ensureNotSymbolicLink(file, documentId)

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    @Synchronized
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        enforceAccess()
        validateName(displayName)

        val directory = retrieveDirectory(parentDocumentId)

        var file = File(directory, displayName)
        var index = 2

        while (file.existsWithoutFollowingLinks)
            file = File(directory, "$displayName (${index++})")

        val documentId = parentDocumentId.appendChild(file.name)
        validateDocumentId(documentId)

        val created = try {
            if (DocumentsContract.Document.MIME_TYPE_DIR == mimeType)
                file.mkdirs()
            else
                file.createNewFile()
        } catch (_: Exception) {
            false
        }

        if (!created)
            throw FileNotFoundException(
                "Failed to create document in $parentDocumentId with name $displayName"
            )

        return documentId
    }

    @Synchronized
    override fun removeDocument(documentId: String, parentDocumentId: String) {
        enforceAccess()
        enforceDirectParent(documentId, parentDocumentId)
        deleteDocument(documentId, revokeRootPermission = true)
    }

    @Synchronized
    override fun deleteDocument(documentId: String) {
        enforceAccess()

        deleteDocument(documentId, revokeRootPermission = false)
    }

    private fun deleteDocument(documentId: String, revokeRootPermission: Boolean) {
        enforceAccess()

        val file = retrieveFile(documentId)
            ?: throw FileNotFoundException("$documentId not found")

        ensureMutable(file, documentId)
        file.collectDocumentIds(documentId)

        if (!file.deleteRecursivelyWithoutFollowingLinks(
                documentId,
                revokeRootPermission,
            )
        )
            throw FileNotFoundException("Failed to delete document $documentId")
    }

    @Synchronized
    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String
    ): String {
        enforceAccess()

        enforceDirectParent(sourceDocumentId, sourceParentDocumentId)

        val source = retrieveFile(sourceDocumentId)
            ?: throw FileNotFoundException("$sourceDocumentId not found")
        val targetDirectory = retrieveDirectory(targetParentDocumentId)

        ensureMutable(source, sourceDocumentId)

        val staleDocumentIds = source.collectDocumentIds(sourceDocumentId)
        val target = File(targetDirectory, source.name)
        val targetDocumentId = targetParentDocumentId.appendChild(target.name)
        validateDocumentId(targetDocumentId)

        if (target.existsWithoutFollowingLinks || !source.renameTo(target))
            throw FileNotFoundException(
                "Failed to move document $sourceDocumentId to $targetParentDocumentId"
            )

        deferRevocations(staleDocumentIds)

        return targetDocumentId
    }

    @Synchronized
    override fun renameDocument(documentId: String, displayName: String): String {
        enforceAccess()
        validateName(displayName)

        val file = retrieveFile(documentId)
            ?: throw FileNotFoundException("$documentId not found")

        ensureMutable(file, documentId)

        val parent = file.parentFile
            ?: throw FileNotFoundException("Failed to rename document $documentId")
        val staleDocumentIds = file.collectDocumentIds(documentId)
        val target = File(parent, displayName)
        val targetDocumentId = documentId.substringBeforeLast('/').appendChild(displayName)
        validateDocumentId(targetDocumentId)

        if (target.existsWithoutFollowingLinks || !file.renameTo(target))
            throw FileNotFoundException(
                "Failed to rename document $documentId with name $displayName"
            )

        staleDocumentIds.drop(1).forEach(::revokePermission)

        return targetDocumentId
    }

    @Synchronized
    override fun getDocumentType(documentId: String): String {
        enforceAccess()
        validateDocumentIdSyntax(documentId)
        enforceReadPermission(documentId)

        val file = retrieveFile(documentId)
            ?: return DocumentsContract.Document.MIME_TYPE_DIR
        val stat = try {
            Os.lstat(file.path)
        } catch (_: Exception) {
            return file.mimeType
        }

        if (OsConstants.S_ISLNK(stat.st_mode))
            return file.mimeType

        val accessibleFile = try {
            retrieveFile(documentId, followLinks = true)
        } catch (_: Exception) {
            null
        }

        return if (accessibleFile?.isDirectory == true)
            DocumentsContract.Document.MIME_TYPE_DIR
        else
            file.mimeType
    }

    @Synchronized
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        enforceAccess()

        if (parentDocumentId == documentId)
            return false

        return try {
            retrieveFile(parentDocumentId, check = false)
            retrieveFile(documentId, check = false)
            documentId.startsWith("$parentDocumentId/")
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        enforceAccess()
        validateFrameworkCall(method, extras)

        deferredRevocations = mutableListOf()
        val superResult = try {
            super.call(method, arg, extras)
        } finally {
            val revocations = deferredRevocations.orEmpty()
            deferredRevocations = null
            revocations.forEach(::revokePermission)
        }
        if (superResult != null || !method.startsWith("mt:") || extras == null)
            return superResult

        val uri = extras.documentUri ?: return result(
            false,
            "not found documentId",
        )
        val documentId = resolveDocumentId(uri)

        return when (method) {
            "mt:setPermissions" -> try {
                val file = retrieveFile(documentId, followLinks = true)
                    ?: return result(false)
                ensureNotSymbolicLink(file, documentId)

                Os.chmod(file.path, extras.getInt("permissions"))
                result(true)
            } catch (e: Exception) {
                result(false, e.message)
            }

            "mt:createSymlink" -> try {
                val file = retrieveFile(documentId, check = false)
                    ?: return result(false)

                Os.symlink(extras.getString("path"), file.path)
                result(true)
            } catch (e: Exception) {
                result(false, e.message)
            }

            "mt:setLastModified" -> try {
                val file = retrieveFile(documentId, followLinks = true)
                    ?: return result(false)
                ensureNotSymbolicLink(file, documentId)

                result(file.setLastModified(extras.getLong("time")))
            } catch (e: Exception) {
                result(false, e.message)
            }

            else -> result(false, "Unsupported method: $method")
        }
    }

    private fun enforceAccess() {
        if (!PrivateStorageAccess.isEnabled(context!!))
            throw SecurityException("Private storage access is disabled")
    }

    private fun enforceWritePermission(uri: Uri) {
        val permission = context!!.checkCallingOrSelfUriPermission(
            uri,
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )

        if (permission != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Write permission is required")
    }

    private fun enforceReadPermission(documentId: String) {
        val currentContext = context!!
        if (Binder.getCallingUid() == Process.myUid() ||
            currentContext.checkCallingOrSelfPermission(
                Manifest.permission.MANAGE_DOCUMENTS
            ) == PackageManager.PERMISSION_GRANTED
        ) return

        val readPermission = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val documentUri = DocumentsContract.buildDocumentUri(
            Authorities.MT_DATA_FILES_PROVIDER,
            documentId,
        )
        if (currentContext.checkCallingOrSelfUriPermission(
                documentUri,
                readPermission,
            ) == PackageManager.PERMISSION_GRANTED
        ) return

        var treeDocumentId = documentId
        while (true) {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                Authorities.MT_DATA_FILES_PROVIDER,
                treeDocumentId,
            )
            val treeDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                documentId,
            )
            if (currentContext.checkCallingOrSelfUriPermission(
                    treeUri,
                    readPermission,
                ) == PackageManager.PERMISSION_GRANTED ||
                currentContext.checkCallingOrSelfUriPermission(
                    treeDocumentUri,
                    readPermission,
                ) == PackageManager.PERMISSION_GRANTED
            ) return

            if (treeDocumentId == rootDocumentId)
                break

            treeDocumentId = treeDocumentId.substringBeforeLast('/')
        }

        throw SecurityException("Read permission is required")
    }

    private fun resolveDocumentId(uri: Uri): String {
        if (uri.authority != Authorities.MT_DATA_FILES_PROVIDER)
            throw SecurityException("Invalid document authority")

        enforceWritePermission(uri)

        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                throw SecurityException("Invalid document URI")
            }
        }

        if (DocumentsContract.isTreeUri(uri)) {
            val treeDocumentId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                throw SecurityException("Invalid tree URI")
            }

            if (documentId != treeDocumentId &&
                !documentId.startsWith("$treeDocumentId/")
            ) throw SecurityException("Document is outside the granted tree")
        }

        return documentId
    }

    private fun validateFrameworkCall(method: String, extras: Bundle?) {
        val keys = when (method) {
            METHOD_RENAME_DOCUMENT -> arrayOf(EXTRA_URI)
            METHOD_MOVE_DOCUMENT -> arrayOf(EXTRA_URI, EXTRA_PARENT_URI, EXTRA_TARGET_URI)
            METHOD_REMOVE_DOCUMENT -> arrayOf(EXTRA_URI, EXTRA_PARENT_URI)
            else -> return
        }
        val arguments = extras
            ?: throw SecurityException("Missing document URIs")

        val uris = keys.map { key ->
            arguments.uri(key)
                ?: throw SecurityException("Missing document URI")
        }
        val documentIds = uris.map(::validateDocumentUri)

        if (method == METHOD_RENAME_DOCUMENT && DocumentsContract.isTreeUri(uris.first())) {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uris.first())

            if (documentIds.first() == treeDocumentId)
                throw SecurityException("Unable to rename the granted tree root")
        }

        if (method == METHOD_MOVE_DOCUMENT && DocumentsContract.isTreeUri(uris.first())) {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uris.first())
            val targetParentDocumentId = documentIds.last()

            if (targetParentDocumentId != treeDocumentId &&
                !targetParentDocumentId.startsWith("$treeDocumentId/")
            ) throw SecurityException("Target is outside the source tree")
        }
    }

    private fun validateDocumentUri(uri: Uri): String {
        if (uri.scheme != "content" ||
            uri.authority != Authorities.MT_DATA_FILES_PROVIDER
        ) throw SecurityException("Invalid document URI")

        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: Exception) {
            throw SecurityException("Invalid document URI")
        }
        validateDocumentIdSyntax(documentId)

        if (DocumentsContract.isTreeUri(uri)) {
            val treeDocumentId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (_: Exception) {
                throw SecurityException("Invalid tree URI")
            }
            validateDocumentIdSyntax(treeDocumentId)

            if (documentId != treeDocumentId &&
                !documentId.startsWith("$treeDocumentId/")
            ) throw SecurityException("Document is outside the granted tree")
        }

        return documentId
    }

    private fun validateDocumentId(documentId: String) {
        retrieveFile(documentId, check = false)
    }

    private fun validateDocumentIdSyntax(documentId: String) {
        if (documentId.length > MAX_DOCUMENT_ID_LENGTH)
            throw FileNotFoundException("$documentId not found")

        if (documentId == rootDocumentId)
            return

        val path = documentId.removePrefix("$rootDocumentId/")
        if (path == documentId)
            throw FileNotFoundException("$documentId not found")

        val parts = path.split('/')
        if (parts.size > MAX_DOCUMENT_DEPTH + 1 ||
            parts.any { !it.isValidPathSegment } ||
            parts.first() !in rootDirectories
        ) throw FileNotFoundException("$documentId not found")
    }

    private fun retrieveDirectory(documentId: String): File {
        val directory = retrieveFile(documentId)
            ?: throw FileNotFoundException("$documentId not found")
        val stat = try {
            Os.lstat(directory.path)
        } catch (_: Exception) {
            throw FileNotFoundException("$documentId not found")
        }

        if (!OsConstants.S_ISDIR(stat.st_mode))
            throw FileNotFoundException("$documentId not found")

        return directory
    }

    private fun enforceDirectParent(documentId: String, parentDocumentId: String) {
        retrieveDirectory(parentDocumentId)

        if (documentId.substringBeforeLast('/', "") != parentDocumentId)
            throw FileNotFoundException("$documentId is not a child of $parentDocumentId")
    }

    private fun retrieveFile(
        documentId: String,
        check: Boolean = true,
        followLinks: Boolean = false,
    ): File? {
        validateDocumentIdSyntax(documentId)

        if (documentId == rootDocumentId)
            return null

        val path = documentId.removePrefix("$rootDocumentId/")
        val parts = path.split('/')
        val root = rootDirectories.getValue(parts.first())
        val file = parts.drop(1).fold(root) { current, name ->
            File(current, name)
        }

        ensureParentsWithoutSymbolicLinks(root, parts.drop(1).dropLast(1))
        ensureContained(root, file, followLinks)

        if (check) try {
            Os.lstat(file.path)
        } catch (_: Exception) {
            throw FileNotFoundException("$documentId not found")
        }

        return file
    }

    private fun ensureParentsWithoutSymbolicLinks(root: File, names: List<String>) {
        var file = root

        names.forEach { name ->
            file = File(file, name)

            val stat = try {
                Os.lstat(file.path)
            } catch (_: Exception) {
                throw FileNotFoundException("${file.path} not found")
            }

            if (OsConstants.S_ISLNK(stat.st_mode))
                throw FileNotFoundException("${file.path} not found")
        }
    }

    private fun ensureContained(root: File, file: File, followLinks: Boolean) {
        val canonicalRoot = root.canonicalFile
        if (file.absolutePath == root.absolutePath)
            return

        val candidate = if (followLinks)
            file.canonicalFile
        else
            file.parentFile?.canonicalFile
                ?: throw FileNotFoundException("${file.path} not found")

        if (candidate.path != canonicalRoot.path &&
            !candidate.path.startsWith(canonicalRoot.path + File.separator)
        ) throw FileNotFoundException("${file.path} not found")
    }

    private fun ensureMutable(file: File, documentId: String) {
        if (rootDirectories.values.any { it.absolutePath == file.absolutePath })
            throw FileNotFoundException("Unable to modify root $documentId")
    }

    private fun ensureNotSymbolicLink(file: File, documentId: String) {
        val stat = try {
            Os.lstat(file.path)
        } catch (_: Exception) {
            throw FileNotFoundException("$documentId not found")
        }

        if (OsConstants.S_ISLNK(stat.st_mode))
            throw FileNotFoundException("Unable to follow symbolic link $documentId")
    }

    private fun validateName(name: String) {
        if (!name.isValidPathSegment)
            throw FileNotFoundException("Invalid name $name")
    }

    private fun MatrixCursor.appendFileInfo(
        documentId: String,
        file: File? = null,
    ): MatrixCursor {
        if (file == null && documentId == rootDocumentId) {
            newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, rootDocumentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, rootDocumentId)
                add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                add(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                )
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L)
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            }

            return this
        }

        validateDocumentId(documentId)

        val finalFile = file ?: retrieveFile(documentId)
            ?: throw FileNotFoundException("$documentId not found")
        val stat = try {
            Os.lstat(finalFile.path)
        } catch (_: Exception) {
            throw FileNotFoundException("$documentId not found")
        }
        val accessibleFile = try {
            retrieveFile(documentId, followLinks = true)
        } catch (_: Exception) {
            null
        }
        val isSymbolicLink = OsConstants.S_ISLNK(stat.st_mode)
        val isDirectory = !isSymbolicLink &&
                accessibleFile?.isDirectory == true
        val isRootDirectory = rootDirectories.values.any {
            it.absolutePath == finalFile.absolutePath
        }

        var flags = if (isDirectory && accessibleFile?.canWrite() == true)
            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        else if (!isDirectory && !isSymbolicLink && accessibleFile?.canWrite() == true)
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        else
            0

        if (!isRootDirectory && finalFile.parentFile?.canWrite() == true) {
            flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME or
                    DocumentsContract.Document.FLAG_SUPPORTS_REMOVE or
                    DocumentsContract.Document.FLAG_SUPPORTS_MOVE
        }

        val name = rootDirectories.entries.firstOrNull {
            it.value.absolutePath == finalFile.absolutePath
        }?.key ?: finalFile.name

        newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(DocumentsContract.Document.COLUMN_SIZE, stat.st_size)
            add(
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                if (isDirectory)
                    DocumentsContract.Document.MIME_TYPE_DIR
                else
                    finalFile.mimeType,
            )
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, stat.st_mtime * 1000L)
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(COLUMN_MT_PATH, finalFile.absolutePath)

            if (!isRootDirectory) try {
                buildString {
                    append(stat.st_mode)
                    append('|')
                    append(stat.st_uid)
                    append('|')
                    append(stat.st_gid)
                    if (OsConstants.S_ISLNK(stat.st_mode)) {
                        append('|')
                        append(Os.readlink(finalFile.path))
                    }
                }.let { add(COLUMN_MT_EXTRAS, it) }
            } catch (_: Exception) {
            }
        }

        return this
    }

    private fun File.deleteRecursivelyWithoutFollowingLinks(
        documentId: String,
        revokeRootPermission: Boolean,
        depth: Int = 0,
        entries: IntArray = intArrayOf(0),
    ): Boolean {
        if (depth > MAX_DOCUMENT_DEPTH || ++entries[0] > MAX_DIRECTORY_ENTRIES)
            return false

        val stat = try {
            Os.lstat(path)
        } catch (_: Exception) {
            return false
        }

        if (OsConstants.S_ISDIR(stat.st_mode)) {
            val children = listFiles() ?: return false
            if (!children.all {
                    it.deleteRecursivelyWithoutFollowingLinks(
                        documentId.appendChild(it.name),
                        true,
                        depth + 1,
                        entries,
                    )
                }
            ) return false
        }

        if (!delete())
            return false

        if (revokeRootPermission)
            revokePermission(documentId)

        return true
    }

    private fun File.collectDocumentIds(
        documentId: String,
        depth: Int = 0,
        entries: IntArray = intArrayOf(0),
        documents: MutableList<String> = mutableListOf(),
    ): List<String> {
        if (depth > MAX_DOCUMENT_DEPTH || ++entries[0] > MAX_DIRECTORY_ENTRIES)
            throw FileNotFoundException("Too many files in $documentId")

        val stat = try {
            Os.lstat(path)
        } catch (_: Exception) {
            throw FileNotFoundException("$documentId not found")
        }

        documents += documentId

        if (OsConstants.S_ISDIR(stat.st_mode)) {
            val children = listFiles()
                ?: throw FileNotFoundException("$documentId not found")

            children.forEach {
                it.collectDocumentIds(
                    documentId.appendChild(it.name),
                    depth + 1,
                    entries,
                    documents,
                )
            }
        }

        return documents
    }

    private fun revokePermission(documentId: String) {
        try {
            revokeDocumentPermission(documentId)
        } catch (_: Exception) {
        }
    }

    private fun deferRevocations(documentIds: List<String>) {
        val revocations = deferredRevocations
        if (revocations == null)
            documentIds.forEach(::revokePermission)
        else
            revocations += documentIds
    }

    private fun result(success: Boolean, message: String? = null) = Bundle().apply {
        putBoolean("result", success)
        if (message != null)
            putString("message", message)
    }

    @Suppress("DEPRECATION")
    private val Bundle.documentUri: Uri?
        get() = uri(EXTRA_URI)

    @Suppress("DEPRECATION")
    private fun Bundle.uri(key: String): Uri? = getParcelable(key)

    companion object {
        private const val LABEL_DATA_DIR = "data"
        private const val LABEL_DE_DATA_DIR = "user_de_data"
        private const val LABEL_EX_DATA_DIR = "android_data"
        private const val LABEL_OBB_DIR = "android_obb"

        private const val COLUMN_MT_PATH = "mt_path"
        private const val COLUMN_MT_EXTRAS = "mt_extras"

        private const val METHOD_RENAME_DOCUMENT = "android:renameDocument"
        private const val METHOD_MOVE_DOCUMENT = "android:moveDocument"
        private const val METHOD_REMOVE_DOCUMENT = "android:removeDocument"

        private const val EXTRA_URI = "uri"
        private const val EXTRA_PARENT_URI = "parentUri"
        private const val EXTRA_TARGET_URI = "targetUri"

        private const val MAX_DOCUMENT_DEPTH = 64
        private const val MAX_DIRECTORY_ENTRIES = 10_000
        private const val MAX_DOCUMENT_ID_LENGTH = 4_096

        private fun String.appendChild(name: String) =
            if (endsWith('/')) "$this$name" else "$this/$name"

        private val String.isValidPathSegment: Boolean
            get() = isNotEmpty() && this != "." && this != ".." &&
                    !contains('/') && !contains('\u0000')

        private val File.mimeType: String
            get() = extension.takeIf { it.isNotEmpty() }?.let {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase())
            } ?: "application/octet-stream"

        private val File.existsWithoutFollowingLinks: Boolean
            get() = try {
                Os.lstat(path)
                true
            } catch (_: Exception) {
                false
            }
    }
}
