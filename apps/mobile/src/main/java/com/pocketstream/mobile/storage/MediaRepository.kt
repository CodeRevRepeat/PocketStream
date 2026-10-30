package com.pocketstream.mobile.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.pocketstream.protocol.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LocalMedia(
    val id: String,
    val uri: Uri,
    val title: String,
    val sizeBytes: Long,
    val mimeType: String,
    var durationMs: Long = 0L,
    var width: Int = 0,
    var height: Int = 0,
    var videoCodec: String = "",
    var audioCodec: String = "",
    var is10Bit: Boolean = false
)

object MediaRepository {
    private const val TAG = "MediaRepository"
    private const val PREFS_NAME = "pocketstream_storage_prefs"
    private const val KEY_PERSISTED_TREES = "persisted_tree_uris"
    private const val KEY_PERSISTED_FILES = "persisted_file_uris"
    private const val KEY_EXCLUDED_IDS = "excluded_media_ids"

    private val approvedTreeUris = java.util.Collections.synchronizedSet(mutableSetOf<Uri>())
    private val approvedFileUris = java.util.Collections.synchronizedSet(mutableSetOf<Uri>())
    private val mediaCache = ConcurrentHashMap<String, LocalMedia>()
    private val excludedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    var onLibraryUpdated: (() -> Unit)? = null
    var onScanStatus: ((String) -> Unit)? = null

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedTrees = prefs.getStringSet(KEY_PERSISTED_TREES, emptySet()) ?: emptySet()
        val savedFiles = prefs.getStringSet(KEY_PERSISTED_FILES, emptySet()) ?: emptySet()
        val savedExcludes = prefs.getStringSet(KEY_EXCLUDED_IDS, emptySet()) ?: emptySet()

        excludedIds.clear()
        excludedIds.addAll(savedExcludes)

        val resolver = context.contentResolver
        val activePermissions = resolver.persistedUriPermissions.map { it.uri }.toSet()

        approvedTreeUris.clear()
        for (uriStr in savedTrees) {
            val uri = Uri.parse(uriStr)
            if (activePermissions.contains(uri)) {
                approvedTreeUris.add(uri)
                scanFolderInternal(context, uri)
            }
        }

        approvedFileUris.clear()
        for (uriStr in savedFiles) {
            val uri = Uri.parse(uriStr)
            // Restore persisted single files
            val item = quickProbe(context, uri)
            if (item != null && !excludedIds.contains(item.id)) {
                approvedFileUris.add(uri)
                mediaCache[item.id] = item
            }
        }

        withContext(Dispatchers.Main) {
            onLibraryUpdated?.invoke()
        }
    }

    suspend fun addFolderTree(context: Context, treeUri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed taking persistable tree permission: ${e.message}")
        }

        approvedTreeUris.add(treeUri)
        savePersistedState(context)

        scanFolderInternal(context, treeUri)

        withContext(Dispatchers.Main) {
            onLibraryUpdated?.invoke()
        }
    }

    suspend fun addSingleFile(context: Context, fileUri: Uri): LocalMedia? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                fileUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed taking persistable file permission: ${e.message}")
        }

        val item = quickProbe(context, fileUri)
        if (item != null) {
            excludedIds.remove(item.id)
            approvedFileUris.add(fileUri)
            savePersistedState(context)
            mediaCache[item.id] = item
            withContext(Dispatchers.Main) {
                onLibraryUpdated?.invoke()
            }
        }
        item
    }

    fun removeMediaItems(context: Context, ids: Set<String>) {
        for (id in ids) {
            val item = mediaCache.remove(id)
            if (item != null) {
                approvedFileUris.remove(item.uri)
            }
            excludedIds.add(id)
        }
        savePersistedState(context)
        onLibraryUpdated?.invoke()
    }

    fun getMediaById(id: String): LocalMedia? {
        if (excludedIds.contains(id)) return null
        return mediaCache[id]
    }

    fun getAllMediaItems(): List<MediaItem> {
        return mediaCache.values
            .filter { !excludedIds.contains(it.id) }
            .map { local ->
                MediaItem(
                    id = local.id,
                    title = local.title,
                    sizeBytes = local.sizeBytes,
                    mimeType = local.mimeType,
                    durationMs = local.durationMs,
                    width = local.width,
                    height = local.height,
                    videoCodec = if (local.is10Bit) "HEVC 10-bit" else local.videoCodec,
                    audioCodec = local.audioCodec,
                    streamPath = "/stream/${local.id}"
                )
            }.sortedByDescending { it.sizeBytes }
    }

    private fun scanFolderInternal(context: Context, treeUri: Uri) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
            scanDocumentRecursive(context, rootDoc, 0)
        } catch (t: Throwable) {
            Log.e(TAG, "Error scanning folder: ${t.message}", t)
        }
    }

    private fun scanDocumentRecursive(context: Context, doc: DocumentFile, depth: Int) {
        if (!doc.exists() || depth > 8) return

        if (doc.isDirectory) {
            val children = try {
                doc.listFiles()
            } catch (_: Throwable) {
                emptyArray()
            }
            for (child in children) {
                scanDocumentRecursive(context, child, depth + 1)
            }
        } else if (doc.isFile) {
            val name = doc.name?.lowercase() ?: ""
            val type = doc.type ?: ""
            val isVideo = type.startsWith("video/") ||
                    name.endsWith(".mp4") ||
                    name.endsWith(".mkv") ||
                    name.endsWith(".avi") ||
                    name.endsWith(".mov") ||
                    name.endsWith(".webm") ||
                    name.endsWith(".ts")

            if (isVideo) {
                val media = quickProbe(context, doc.uri, doc.name, doc.length())
                if (media != null && !excludedIds.contains(media.id)) {
                    mediaCache[media.id] = media
                }
            }
        }
    }

    private fun quickProbe(
        context: Context,
        uri: Uri,
        knownName: String? = null,
        knownSize: Long = 0L
    ): LocalMedia? {
        var title = knownName
        var size = knownSize
        val resolver = context.contentResolver

        if (title == null || size <= 0L) {
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(uri, null, null, null, null)
                if (cursor != null && cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) title = cursor.getString(nameIndex)
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed reading document metadata: ${t.message}")
            } finally {
                cursor?.close()
            }
        }

        if (title == null) title = "Video_${System.currentTimeMillis()}"
        val mimeType = try {
            resolver.getType(uri)
        } catch (_: Exception) { null } ?: when {
            title.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            title.endsWith(".webm", ignoreCase = true) -> "video/webm"
            else -> "video/mp4"
        }

        var is10Bit = title.contains("10bit", ignoreCase = true) ||
                      title.contains("10-bit", ignoreCase = true) ||
                      title.contains("hdr", ignoreCase = true)

        var durationMs = 0L
        var width = 0
        var height = 0
        var detectedVideoCodec = ""
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val colorTransfer = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull() ?: 0
                val colorStandard = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull() ?: 0
                if (colorTransfer == 6 || colorTransfer == 7 || colorStandard == 6) {
                    is10Bit = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for $uri: ${e.message}")
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        var extractor: android.media.MediaExtractor? = null
        try {
            extractor = android.media.MediaExtractor()
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("video/")) {
                    detectedVideoCodec = trackMime
                    if (width <= 0 && format.containsKey(android.media.MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(android.media.MediaFormat.KEY_WIDTH)
                    }
                    if (height <= 0 && format.containsKey(android.media.MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(android.media.MediaFormat.KEY_HEIGHT)
                    }
                    if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) {
                        val profile = format.getInteger(android.media.MediaFormat.KEY_PROFILE)
                        if (profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                            profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                            profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus) {
                            is10Bit = true
                        }
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
        }

        val id = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString().substring(0, 8)
        return LocalMedia(
            id = id,
            uri = uri,
            title = title,
            sizeBytes = size,
            mimeType = mimeType,
            durationMs = durationMs,
            width = width,
            height = height,
            videoCodec = detectedVideoCodec,
            is10Bit = is10Bit
        )
    }

    private fun savePersistedState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_PERSISTED_TREES, approvedTreeUris.map { it.toString() }.toSet())
            .putStringSet(KEY_PERSISTED_FILES, approvedFileUris.map { it.toString() }.toSet())
            .putStringSet(KEY_EXCLUDED_IDS, excludedIds)
            .apply()
    }
}