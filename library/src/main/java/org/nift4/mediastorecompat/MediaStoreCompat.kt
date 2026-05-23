/*
 * Copyright (C) 2026 nift4
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nift4.mediastorecompat

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.UriPermission
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ext.SdkExtensions
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.system.Os
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.DeprecatedSinceApi
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.provider.DocumentsContractCompat
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_AUDIO
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_DOCUMENT
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_IMAGE
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_NONE
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_PLAYLIST
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_SUBTITLE
import org.nift4.mediastorecompat.MediaStoreCompat.MEDIA_TYPE_VIDEO
import org.nift4.mediastorecompat.MediaStoreCompat.PERMISSION_DELETE
import org.nift4.mediastorecompat.MediaStoreCompat.PERMISSION_UPDATE_SQL
import org.nift4.mediastorecompat.MediaStoreCompat.PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT
import org.nift4.mediastorecompat.MediaStoreCompat.create
import org.nift4.mediastorecompat.MediaStoreCompat.createDeleteRequest
import org.nift4.mediastorecompat.MediaStoreCompat.createFavoriteRequest
import org.nift4.mediastorecompat.MediaStoreCompat.createTrashRequest
import org.nift4.mediastorecompat.MediaStoreCompat.createWriteRequest
import org.nift4.mediastorecompat.MediaStoreCompat.delete
import org.nift4.mediastorecompat.MediaStoreCompat.disableCanBecomeManager
import org.nift4.mediastorecompat.MediaStoreCompat.efficientMove
import org.nift4.mediastorecompat.MediaStoreCompat.finishCreate
import org.nift4.mediastorecompat.MediaStoreCompat.getBaseUriForMediaType
import org.nift4.mediastorecompat.MediaStoreCompat.getDocumentUri
import org.nift4.mediastorecompat.MediaStoreCompat.getDocumentUriEx
import org.nift4.mediastorecompat.MediaStoreCompat.markIsFavoriteStatus
import org.nift4.mediastorecompat.MediaStoreCompat.markIsTrashedStatus
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestBytesWrite
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestCreate
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestDelete
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestEfficientMove
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestFavorite
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestSqlUpdate
import org.nift4.mediastorecompat.MediaStoreCompat.needRequestTrash
import org.nift4.mediastorecompat.MediaStoreCompat.openFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.text.substringAfterLast

/**
 * Backport of media batch permission request and Storage Access Framework (SAF) interoperability
 * APIs from the reworked [MediaStore] in Android 11 to earlier versions.
 *
 * Generally, your app has to follow Android 11's best practices for media access to use this
 * library. That is,
 * - only use MediaStore for either document files you own, or media files
 * - when using MediaStore, put all files in the appropriate folders
 * - when working with media files you don't own, use [MediaStore.createWriteRequest] and
 *   [MediaStore.createDeleteRequest] to show a UI to the user where they can decide what to do
 * - request [Manifest.permission.MANAGE_EXTERNAL_STORAGE] if you're a file manager app
 *
 * If you develop code to follow these practices, it will be easy to adapt to this library, gaining
 * free interoperability with older Android versions. This means this library can be used to create
 * a multimedia app (say, a music player), or a full file manager app, removing legacy code
 * complications. However, this library can't be used to replace SAF (on any Android version) for
 * use cases that would require SAF on Android 11 and later in order to keep this library
 * maintainable and the API focused (you are encouraged to develop your own abstraction on top if
 * you need this). This means this library is not suitable for accessing files 1. on private storage
 * (USB drive, non-adoptable SD card) 2. on cloud storage (DocumentsProvider) 3. that are not media
 * files, unless you request and get granted [Manifest.permission.MANAGE_EXTERNAL_STORAGE].
 * Basically, this library is a convenient replacement for the MediaStore and File API in their
 * Android 11 states, but it works on Android 5 or later. While this library uses SAF to fulfill
 * this goal in some cases, any kind of SAF-focused API would be out-of-scope, and this library
 * explicitly does NOT use SAF on Android 11 and later in any case. You can always combine this
 * library with your own abstractions if so desired. A useful method for an app that relies on SAF a
 * lot is [getDocumentUri]/[getDocumentUriEx] which allows you to process [Uri]s obtained from
 * external sources, such as an [Intent], using SAF.
 *
 * Android's Storage Access Framework is used internally for:
 * - Any kind of SD write access on Android 5, 6, 7 and 9
 * - Efficient move of any kind of file on SD card for Android 8
 * - Efficient move of non-(audio/image/video/download) files on SD card for Android 10
 * - Accessing playlist and subtitle files we don't own on Android 11
 * - Creating files in folders that don't exist yet _and_ aren't part of a well-defined top level
 *   folder (such as Music, Movies, DCIM, Download, Documents, etc.) on SD card on Android 10
 * - As fallback for all kinds of write access for OEM customized systems on SD cards for Android 8
 * - As fallback for OEM customized systems, when writing/deleting files from SD card on Android 10
 *
 * This library always strives to provide optimal user experience. Hence, we want to show the least
 * possible amount of permission prompts. If we have to anyway, variants with good user experience
 * (such as a yes-no dialog box for granting write access to the entire SD card) are preferred over
 * variants that are confusing (the SAF folder picker, which could allow finer-grained picking, but
 * is confusing to many users), if applicable.
 *
 * The library also supports non-media files in APIs. This is of course needed if you are developing
 * a file manager using [Manifest.permission.MANAGE_EXTERNAL_STORAGE], but it's also a benefit for
 * files which were not recognized as media files until Android 11, such as subtitle or lyric files.
 * Note that on Android 11, you need [Manifest.permission.MANAGE_EXTERNAL_STORAGE] for non-media
 * files, while on earlier versions, you can use the methods provided by this library for all kinds
 * of files on these versions without special permissions.
 *
 * Unlike Android 11, due to the nature of the older Android API, creating files may also need a
 * permission request, for example on Android 10 (for non-media files on secondary storage) or
 * earlier (for secondary storage). See [needRequestCreate] and [createWriteRequest] for more
 * information.
 *
 * Caution: this library will not work if [ContentResolver.takePersistableUriPermission] is called
 * on a `content://media` [Uri] that refers to a table or volume (that is, it doesn't end with an ID
 * number) and more than 122 other [Uri] grants are persisted after that. Please do not persist
 * table Uri permissions.
 *
 * If your app uses multiple processes, the customization function [disableCanBecomeManager] has to
 * be consistently called or not called in every process that will be used for library functions!
 */
// TODO: file change listener (caution: https://issuetracker.google.com/issues/37017033) that can
//  correlate scan change callback with inotify (is there a 8k file limit though? that would make it
//  actually impossible to do?)
// TODO: document that R+ FUSE always shows us every folder except Android/{data,media,obb} somewhere
// TODO: report AOSP bug about being unable to move folder via FUSE even if having WR
// TODO: the sdk-extensions assumptions might be wrong because sdk extensions were backport branches
//  and there's no way to check as relevant branches were never released publicly
object MediaStoreCompat {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
        ) // since R, this library does not need hidden API
            ReflectionUtil.enableReflection()
    }

    private const val TAG = "MediaStoreCompat"

    /**
     * MediaStore volume name that provides a view of all content across the "external" storage of
     * the device.
     *
     * This MediaStore volume provides a merged view of all media across all currently attached
     * external storage devices.
     *
     * Since Android 10, this is a synthetic volume, which means you can't insert new content into
     * this volume. Instead, you can insert content into a specific storage volume obtained from
     * [MediaStore.getExternalVolumeNames], such as [MediaStore.VOLUME_EXTERNAL_PRIMARY].
     *
     * Before Android 10, you can insert content into this volume, and it will be stored in the
     * primary [StorageVolume].
     */
    @SuppressLint("InlinedApi") // Valid since at least Files.getContentUri() was added
    const val VOLUME_EXTERNAL = MediaStore.VOLUME_EXTERNAL

    /** @see MediaStore.Files.FileColumns.MEDIA_TYPE_NONE */
    const val MEDIA_TYPE_NONE = MediaStore.Files.FileColumns.MEDIA_TYPE_NONE

    /** @see MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE */
    const val MEDIA_TYPE_IMAGE = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE

    /** @see MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO */
    const val MEDIA_TYPE_VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    /**
     * This is used for playlist formats (m3u, m3u8, xspf, wpl, smpl).
     *
     * Please use this type even though the AOSP version is deprecated.
     *
     * @see MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST
     */
    @Suppress("deprecation")
    const val MEDIA_TYPE_PLAYLIST = MediaStore.Files.FileColumns.MEDIA_TYPE_PLAYLIST

    /** @see MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO */
    const val MEDIA_TYPE_AUDIO = MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO

    /**
     * This is used for subtitles (ttml, srt, vtt, sub, cap, dxfp, smi, smil) and lyrics (ttml, lrc)
     * formats.
     *
     * Be careful to not use this constant with platform MediaStore APIs on old Android versions, it
     * was added in Android 11.
     *
     * @see MediaStore.Files.FileColumns.MEDIA_TYPE_SUBTITLE
     */
    @SuppressLint("InlinedApi")
    const val MEDIA_TYPE_SUBTITLE = MediaStore.Files.FileColumns.MEDIA_TYPE_SUBTITLE

    /**
     * Be careful to not use this constant with platform MediaStore APIs on old Android versions, it
     * was added in Android 11.
     *
     * @see MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT
     */
    @SuppressLint("InlinedApi")
    const val MEDIA_TYPE_DOCUMENT = MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT

    /**
     * Convenience field, similar to [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI] but for the
     * [MediaStore.Files] table.
     */
    @JvmField val FILES_EXTERNAL_CONTENT_URI = MediaStore.Files.getContentUri(VOLUME_EXTERNAL)!!

    /** @see ClipDescription.MIMETYPE_UNKNOWN */
    @JvmField val MIMETYPE_UNKNOWN = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        ClipDescription.MIMETYPE_UNKNOWN
    } else {
        "application/octet-stream"
    }

    private var canBecomeManagerCache: Boolean? = null
    private var canGetReadExternalStorageCache: Boolean? = null
    private var canGetWriteExternalStorageCache: Boolean? = null
    private var canGetReadAudioCache: Boolean? = null
    private var canGetReadImagesCache: Boolean? = null
    private var canGetReadVideosCache: Boolean? = null
    private fun loadPermissions(context: Context) {
        val perms = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions!!
        canGetReadExternalStorageCache = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            perms.contains(Manifest.permission.READ_EXTERNAL_STORAGE)
        canGetWriteExternalStorageCache = Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                perms.contains(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                perms.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (canBecomeManagerCache == null) // cf disableCanBecomeManager
            canBecomeManagerCache = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    perms.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
        canGetReadVideosCache = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                perms.contains(Manifest.permission.READ_MEDIA_VIDEO)
        canGetReadAudioCache = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                perms.contains(Manifest.permission.READ_MEDIA_AUDIO)
        canGetReadImagesCache = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                perms.contains(Manifest.permission.READ_MEDIA_IMAGES)
    }
    @RequiresApi(Build.VERSION_CODES.R)
    private fun canBecomeManager(context: Context): Boolean {
        canBecomeManagerCache?.let { return it }
        loadPermissions(context)
        return canBecomeManagerCache!!
    }
    /**
     * Use this if the declaration of [Manifest.permission.MANAGE_EXTERNAL_STORAGE] in manifest
     * should be ignored by the library code. In this case, the library will neither attempt to use
     * those rights nor request them if not granted.
     *
     * Please call this method at most once, and only before calling other methods in this library.
     *
     * If your app uses multiple processes, it has to be called in every one of them that will be
     * used for other library functions!
     */
    @JvmStatic fun disableCanBecomeManager() {
        if (canBecomeManagerCache != null) {
            throw IllegalArgumentException("please call this method at most once, and only before" +
                    " calling other methods in this library")
        }
        canBecomeManagerCache = false
    }
    private fun canGetReadExternalStorage(context: Context): Boolean {
        canGetReadExternalStorageCache?.let { return it }
        loadPermissions(context)
        return canGetReadExternalStorageCache!!
    }
    private fun canGetWriteExternalStorage(context: Context): Boolean {
        canGetWriteExternalStorageCache?.let { return it }
        loadPermissions(context)
        return canGetWriteExternalStorageCache!!
    }
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    private fun canGetReadVideos(context: Context): Boolean {
        canGetReadVideosCache?.let { return it }
        loadPermissions(context)
        return canGetReadVideosCache!!
    }
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    private fun canGetReadAudio(context: Context): Boolean {
        canGetReadAudioCache?.let { return it }
        loadPermissions(context)
        return canGetReadAudioCache!!
    }
    @ChecksSdkIntAtLeast(Build.VERSION_CODES.TIRAMISU)
    private fun canGetReadImages(context: Context): Boolean {
        canGetReadImagesCache?.let { return it }
        loadPermissions(context)
        return canGetReadImagesCache!!
    }

    @RequiresApi(Build.VERSION_CODES.R)
    internal fun supportsWriteRequestForSidecar() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isAffectedByMoveGenericVolumeBug() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) < 22)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isAffectedByPlaylistMimeReset() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Get a media [Uri] for a file, scanning the file if none is found. This can fail for the
     * reasons described in [scanFile], but it does a best-effort at working around the Android 10
     * specific failure reasons and hence is generally expected to always succeed if storage
     * permission is granted.
     */
    @SuppressLint("SdCardPath")
    fun getMediaUriForFile(context: Context, file: String): Uri {
        val file = if (file.startsWith("/sdcard", ignoreCase = true)) StorageManagerCompat
            .getStorageVolumes(context).first { it.isPrimary || it.isEmulated }
            .requireCanonicalDirectory().path + file.substring("/sdcard".length)
        else file
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.contentResolver.query(
                FILES_EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.IS_DOWNLOAD,
                    MediaStore.Files.FileColumns.VOLUME_NAME,
                ),
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns.DATA} = ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(file))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                },
                null
            )
        } else {
            context.contentResolver.query(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    @Suppress("deprecation")
                    MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI)
                else FILES_EXTERNAL_CONTENT_URI,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.IS_DOWNLOAD,
                        MediaStore.Files.FileColumns.VOLUME_NAME,
                    )
                else arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
                ),
                "${MediaStore.Files.FileColumns.DATA} = ?",
                arrayOf(file),
                null
            )
        }
        cursor.use { _ ->
            // Index might be missing (Q-).
            if (cursor != null && cursor.moveToFirst()) {
                val id = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns._ID
                    )
                )
                val mediaType = cursor.getInt(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.MEDIA_TYPE
                    )
                )
                val isDownload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getInt(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.Files.FileColumns.IS_DOWNLOAD
                    )
                ) == 1 else false
                val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.VOLUME_NAME
                        )
                    ) else null
                // Once/if we can ever deprecate Audio.Playlists Uri usage here entirely, we should
                // look into rewriting these Uris on the modern Android versions here as we wouldn't
                // want to poison the app with deprecated Uris if not needed.
                return ContentUris.withAppendedId(
                    getBaseUriForMediaType(
                        volumeName,
                        mediaType, if (mediaType == MEDIA_TYPE_NONE ||
                            mediaType == MEDIA_TYPE_DOCUMENT
                        ) isDownload else false
                    ), id
                )
            }
        }
        scanFileOrThrow(context, file)?.let { return it }
        if (!File(file).exists())
            throw IllegalArgumentException("File doesn't exist: $file")
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.Q)
            throw IllegalStateException("Scan returned null but didn't time out and this isn't Q")
        if (!hasWriteExternalStorage(context))
            throw IllegalArgumentException("This file is invalid/hidden and storage permission is" +
                    " not granted, so we can't work around this: $file")
        val volumes = StorageManagerCompat.getStorageVolumes(context)
        val volume = StorageManagerCompat.getVolumeForPath(volumes, File(file))
        val isHidden = file.contains("/.") || run {
            var current: File? = File(file).parentFile
            while (current != null && current != volume.requireCanonicalDirectory()) {
                if (current.resolve(".nomedia").exists())
                    return@run true
                current = current.parentFile
            }
            false
        }
        val fileRelativeIn = File(file).relativeTo(volume
            .requireCanonicalDirectory())
        val mediaType = if (!isHidden) getMediaTypeForMime(
            guessMimeTypeFromFileName(file)) else MEDIA_TYPE_NONE
        val (fileRelative, mimeType) = computeFileAndMime(fileRelativeIn, null)
        val baseUri = getBaseUriForMediaType(volume.mediaStoreVolumeName, mediaType)
        context.checkGrantSelfUriPermission(baseUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        // This doesn't create the file yet, that is done in openFileDescriptor()
        return context.contentResolver.insert(baseUri, ContentValues().apply {
            put(MediaStore.Files.FileColumns.DATA, volume.requireCanonicalDirectory()
                .resolve(fileRelative).absolutePath)
            put(MediaStore.Files.FileColumns.MIME_TYPE, mimeType)
        }) ?: throw IllegalStateException("insert() returned null Uri, did MediaProvider crash?")
    }

    /**
     * Returns the media [Uri] with the volume resolved to the proper value.
     *
     * This is required to work around a bug in Android 10-15 regarding trashing and moving files.
     *
     * In case of any issues, the error is logged and the original [Uri] is returned unmodified.
     */
    @JvmStatic
    fun resolveMediaUriVolume(context: Context, uri: Uri): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            MediaStore.getVolumeName(uri) == VOLUME_EXTERNAL) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.VOLUME_NAME), null,
                    null, null
                ).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        val volumeName = cursor.getString(cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.VOLUME_NAME))
                        (MediaStore.AUTHORITY_URI.buildUpon()
                            .appendPath(volumeName).build().toString() + uri
                            .toString().substring(
                                MediaStore.AUTHORITY_URI.buildUpon()
                                    .appendPath(MediaStore.getVolumeName(uri))
                                    .build().toString().length
                            )).toUri()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed to resolve volume", e)
            }
        }
        return uri
    }

    /**
     * Similar to the original method, this backport performs a permission check to ensure the
     * returned Uri is accessible using a persisted grant. If this is not relevant, consider using
     * [getDocumentUriEx] instead and set the resolvePermissions mode to [ResolvePermissions.Never].
     *
     * Requires read access to the media [Uri]. Throws if the media [Uri] does not resolve to a
     * valid file on disk.
     *
     * @see MediaStore.getDocumentUri
     */
    @JvmStatic
    fun getDocumentUri(context: Context, mediaUri: Uri): Uri {
        val result = getDocumentUriEx(context, mediaUri, null,
            null)
        if (result is Uri)
            return result
        else // result is String
            throw SecurityException("caller has no access to $result")
    }

    /**
     * This is a more flexible version of [getDocumentUri] that can be used for advanced use-cases
     * and in situations where performance is important.
     *
     * Caution: the [mediaFile] provided must be the canonical form (use [File.getCanonicalFile]).
     *
     * If a [mediaUri] is provided, then it must be readable. A [mediaFile] though could be
     * unreadable due to missing permission or other issues.
     *
     * Differences to [getDocumentUri]:
     * - [mediaUri] is optional (though one of [mediaUri] or [mediaFile] has to be provided)
     * - Optional [mediaFile] and [mediaStoreVolumeName] arguments that can be provided to improve
     *   performance, if the file path and/or [MediaStore] volume name (on Android 10 and later
     *   only) of this media [Uri] are known to the caller. If an optional parameter is null, the
     *   method will automatically detect its value if needed.
     * - Two optional parameters offering advanced control over permission check behavior (whether
     *   read or write should be checked, whether to perform permission checks at all, etc.)
     * - Two optional parameters containing caches that can improve performance if this method is
     *   repeatedly called to process a batch of [Uri]s.
     * - The return value of this method is [Any]. Normally, it returns an [Uri]. However, if there
     *   are insufficient permissions, instead of throwing [SecurityException], the return value
     *   will be a [String], which is the document ID of the relevant media [Uri].
     */
    @SuppressLint("SdCardPath")
    @JvmStatic
    @JvmOverloads
    fun getDocumentUriEx(context: Context, mediaUri: Uri?, mediaFile: File?,
                         mediaStoreVolumeName: String? = null,
                         /**
                          * By default, [getDocumentUri] verifies that the caller has access to each of the returned
                          * [Uri]s as a persisted [Uri]. It is also possible to check for non-persisted [Uri]s, but
                          * this requires multiple Binder calls which significantly reduces the method's performance.
                          * If permissions checks are not or only partially required for the caller,
                          * this enum can be used to reduce or disable these checks, increasing the method's
                          * performance. Refer to the enum member's documentation for details. Similarly, if access
                          * should be detected even if it's a non-persisted grant, this enum can be used to increase
                          * the level of checks at significant performance cost.
                          *
                          * Disabling the permission checks does not result in information disclosure to the caller
                          * as the [Uri]s are built from information available in [MediaStore], but it may confuse
                          * the caller if it can't access the [Uri]s are returned.
                          */
                         resolvePermissions: ResolvePermissions = ResolvePermissions.OnlyPersisted,
                         /**
                          * Whether permission checks should be performed against write or read permission.
                          *
                          * If the caller has permission to multiple tree prefixes that apply to a document [Uri],
                          * but only some of them have write permission granted:
                          * - setting true will prefer the tree with the write permission grant and handle as missing
                          *   permission if there is none.
                          * - setting false will use the first applicable tree prefix, even if it doesn't have write
                          *   permission.
                          * - setting null will prefer the tree with the write permission grant.
                          *   if no such tree is found, it'll use a tree with only read permission granted
                          * */
                         forWrite: Boolean? = null,
                         /**
                          * A cache of the result of [StorageManagerCompat.getStorageVolumes]. This is useful if
                          * [getDocumentUriEx] will be called multiple
                          * times because a batch of [Uri]s is being processed. If the cache is present, a Binder
                          * call can be avoided for each of the [Uri]s which improves method performance. If this is
                          * null, [getDocumentUri] will instead call the method internally and discard the list of
                          * volumes after it returns, which is useful for convenience if only a single [Uri] has to
                          * be converted.
                          */
                         volumesCache: List<StorageVolumeCompat>? = null,
                         /**
                          * A cache of the result of [android.content.ContentResolver.getPersistedUriPermissions].
                          * This is useful if [getDocumentUriEx] will be called multiple times because a batch of [Uri]s is being processed. If the
                          * cache is present, a Binder call can be avoided for each of the [Uri]s which improves
                          * method performance. If this is null, [getDocumentUri] will instead call the method
                          * internally and discard the list of persisted [Uri] permissions after it returns, which is
                          * useful for convenience if only a single [Uri] has to be converted.
                          *
                          * This data is not required if one of the following is true:
                          * - The [resolvePermissions] mode is [ResolvePermissions.Never].
                          * - On Android 8 and later, [forWrite] is null and the [resolvePermissions] mode is
                          * [ResolvePermissions.Full].
                          */
                         persistedUriPermissionsCache: List<UriPermission>? = null): Any {
        if (mediaUri == null && mediaFile == null)
            throw IllegalArgumentException("one of mediaUri or mediaFile should be non-null")
        if (mediaUri?.authority?.equals(MediaStore.AUTHORITY) == false)
            throw IllegalArgumentException("Expected a MediaStore uri: $mediaUri")
        if (mediaFile?.isAbsolute == false)
            throw IllegalArgumentException("mediaFile, if provided, must be absolute path")
        var vol = mediaStoreVolumeName
        var mediaUri = mediaUri
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && forWrite == null &&
            resolvePermissions == ResolvePermissions.OnlyPersisted) {
            if (mediaUri == null) {
                val file = if (mediaFile!!.absolutePath.startsWith("/sdcard", ignoreCase =
                        true)) StorageManagerCompat.getStorageVolumes(context).first { it.isPrimary
                        || it.isEmulated }.requireCanonicalDirectory().path + mediaFile.absolutePath
                            .substring("/sdcard".length) else mediaFile.absolutePath
                // If we have a grant for the media uri but don't usually have access to the file,
                // this will fail. The caller has to supply the mediaUri in that case.
                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.contentResolver.query(
                        FILES_EXTERNAL_CONTENT_URI,
                        arrayOf(
                            MediaStore.MediaColumns._ID,
                            MediaStore.MediaColumns.VOLUME_NAME
                        ),
                        Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                                "${MediaStore.Files.FileColumns.DATA} = ?")
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                                arrayOf(file))
                            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        },
                        null
                    )
                } else {
                    context.contentResolver.query(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            @Suppress("deprecation")
                            MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI)
                        else FILES_EXTERNAL_CONTENT_URI,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            arrayOf(
                                MediaStore.MediaColumns._ID,
                                MediaStore.MediaColumns.VOLUME_NAME
                            )
                        else arrayOf(
                            MediaStore.MediaColumns._ID
                        ),
                        "${MediaStore.Files.FileColumns.DATA} = ?",
                        arrayOf(file),
                        null
                    )
                }
                cursor.use { _ ->
                    // Index might be missing (Q-).
                    if (cursor != null && cursor.moveToFirst()) {
                        val id = cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns._ID
                            )
                        )
                        vol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns.VOLUME_NAME
                                )
                            ) else null
                        mediaUri = ContentUris.withAppendedId(
                            FILES_EXTERNAL_CONTENT_URI, id
                        )
                    }
                }
            }
            if (mediaUri != null)
                try {
                    MediaStore.getDocumentUri(context, mediaUri)?.let { return it }
                } catch (e: RuntimeException) {
                    Log.w(TAG, "getDocumentUri failed", e)
                }
        }
        var mediaFile = mediaFile
        if (mediaFile == null) {
            val cursor = context.contentResolver.query(
                mediaUri!!,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.VOLUME_NAME)
                else arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null
            )
            cursor.use { cursor ->
                if (cursor == null || !cursor.moveToFirst())
                    throw IllegalArgumentException("Can't access media row: $mediaUri")
                mediaFile = File(cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DATA
                    )
                ))
                vol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            MediaStore.MediaColumns.VOLUME_NAME
                        )
                    ) else null
            }
        }
        val volumes = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vol != null)
            volumes.find { it.mediaStoreVolumeName == vol }
                ?: throw IllegalArgumentException("Can't find volume for $mediaFile ($vol)")
        else StorageManagerCompat.getVolumeForPath(volumes, mediaFile!!)
        val documentId = StorageManagerCompat.buildExternalStorageDocumentId(
            volume, mediaFile!!.relativeTo(volume.requireCanonicalDirectory()))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && forWrite == null
            && mediaUri != null && resolvePermissions == ResolvePermissions.OnlyPersisted)
            return documentId
        return getPrefixForDocument(context, documentId, forWrite, resolvePermissions,
            persistedUriPermissionsCache) ?: documentId
    }

    /**
     * Given a storage access framework document ID for ExternalStorageProvider (only!), find the
     * best prefix which gives us Uri permission to this document, or null if we have no permission
     * at the moment.
     */
    @JvmStatic
    @JvmOverloads
    fun getPrefixForDocument(context: Context, documentId: String, forWrite: Boolean?,
                             resolvePermissions: ResolvePermissions,
                             persistedUriPermissionsCache: List<UriPermission>? = null): Uri? {
        // We prefer the _least specific_ matching path, as that is least likely to be deleted. If
        // we use a more specific path, and then have to mkdirs(), the more specific path may have
        // already been deleted. That would be avoidable if we'd be able to recreate it with less
        // specific one (which we also have access to in this case). If we only have one path to
        // choose from and that is deleted, it's fine to throw in mkdirs(), but if we have multiple
        // we shouldn't use less specific ones to increase chance of success.
        var persistedUriPermissionsCache = persistedUriPermissionsCache
        val ret = when (resolvePermissions) {
            ResolvePermissions.Full -> {
                when (forWrite) {
                    true -> findParentTreeWithPermission(context, documentId,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    false -> findParentTreeWithPermission(context, documentId,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    null -> findParentTreeWithPermission(context, documentId,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        ?: findParentTreeWithPermission(context, documentId,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ResolvePermissions.OnlyPersistedOrDirect -> {
                persistedUriPermissionsCache = (persistedUriPermissionsCache
                    ?: context.contentResolver.persistedUriPermissions)
                val persistedUriPermissions = persistedUriPermissionsCache.filter {
                    it.uri.authority == StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE
                }.sortedBy { it.uri.toString().length }
                findUriWithPersisted(context, documentId, forWrite,
                    persistedUriPermissions.filter {
                        DocumentsContractCompat.isTreeUri(it.uri)
                                // isDocumentUri uses binder which we want to avoid
                                || it.uri.pathSegments.size >= 2 &&
                                it.uri.pathSegments[0] == "document" }) ?: run {
                    val documentUri = DocumentsContract.buildDocumentUri(
                        StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE, documentId
                    )
                    if (context.checkGrantSelfUriPermission(
                            documentUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    if (forWrite == true)
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
                        )
                    )
                        documentUri
                    else null
                }
            }
            ResolvePermissions.OnlyPersisted -> {
                persistedUriPermissionsCache = (persistedUriPermissionsCache
                    ?: context.contentResolver.persistedUriPermissions)
                val persistedUriPermissions = persistedUriPermissionsCache.filter {
                    it.uri.authority == StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE
                }.sortedBy { it.uri.toString().length }
                findUriWithPersisted(
                    context, documentId, forWrite,
                    persistedUriPermissions.filter {
                        DocumentsContractCompat.isTreeUri(it.uri)
                                // isDocumentUri uses binder which we want to avoid
                                || it.uri.pathSegments.size >= 2 &&
                                it.uri.pathSegments[0] == "document"
                    })
            }
            ResolvePermissions.OnlyPersistedTree -> {
                persistedUriPermissionsCache = (persistedUriPermissionsCache
                    ?: context.contentResolver.persistedUriPermissions)
                val persistedUriPermissions = persistedUriPermissionsCache.filter {
                    it.uri.authority == StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE
                }.sortedBy { it.uri.toString().length }
                findParentTreeWithPersisted(context, documentId, forWrite,
                    persistedUriPermissions.filter { DocumentsContractCompat.isTreeUri(it.uri) })
            }
            ResolvePermissions.Never -> null
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
            resolvePermissions != ResolvePermissions.Never && ret == null &&
            !StorageManagerCompat.getExternalStoragePath(documentId).startsWith("./")) {
            // In R you cannot get access to whole volume in a normal way, but you can get Volume/.
            // see https://cs.android.com/android/_/android/platform/frameworks/base/+/8b55dd05ce694a97123fafa3f01c4f4dfe4854d9
            val ret = getPrefixForDocument(context,
                StorageManagerCompat.buildExternalStorageDocumentId(
                    StorageManagerCompat.getExternalStorageVolumeName(documentId),
                    "./" + StorageManagerCompat.getExternalStoragePath(documentId)
                ), forWrite, // only persisted tree mode would use this hack from our side
                ResolvePermissions.OnlyPersistedTree, persistedUriPermissionsCache)
            if (ret == null)
                return null
            return DocumentsContract.buildDocumentUriUsingTree(ret, documentId)
        }
        return ret
    }

    /**
     * This serves the function of [Context.checkUriPermission] on ourselves, and in addition,
     * making sure this [Uri] permission also stays for our entire process lifetime. A persistable
     * grant being revoked using [android.content.ContentResolver.releasePersistableUriPermission]
     * under the condition that a reboot happened since it was granted to us will be revoked
     * instantly. Unrelated app code releasing a persisted [Uri] grant while business logic executes
     * would then result in a crash, which is highly undesired. Always promoting a persisted grant
     * back to an active grant avoids that scenario. (Active grants don't offer more permissions,
     * but are more often never revoked during process execution because there's no limit on them,
     * unlike persisted grants, so there is no incentive to revoke them)
     */
    internal fun Context.checkGrantSelfUriPermission(uri: Uri, flags: Int): Boolean {
        try {
            maybeGrantSelfUriPermission(this, uri, flags)
            return true
        } catch (_: SecurityException) {
            return false
        }
    }

    /**
     * Various APIs such as [MediaStore.createWriteRequest] (on Android 11 or later) or
     * [android.content.ContentResolver.update] (on Android 10 for SD cards only) require you to use
     * the most specific Uri possible, one which matches the media type. Using [MediaStore.Files]
     * Uris is only rarely (subtitle/lyric files, or files you own) allowed. When using the wrong
     * Uris, access is denied even though it would be allowed otherwise. This method provides the
     * correct base URI for use with [ContentUris.withAppendedId] that will allow maximum possible
     * access on all API levels. That is, for [MEDIA_TYPE_AUDIO] it would return a [Uri] from
     * [MediaStore.Audio.Media.getContentUri].
     *
     * Also, for Android 10-15, it's required to specify the correct volume in the Uri to be allowed
     * to move and trash files due to a bug that didn't resolve [MediaStore.VOLUME_EXTERNAL] to the
     * correct volume.
     */
    @JvmStatic
    fun getBaseUriForMediaType(volume: String?, mediaType: Int): Uri {
        return getBaseUriForMediaType(volume, mediaType, false)
    }

    private fun getBaseUriForMediaType(volume: String?, mediaType: Int, isDownload: Boolean): Uri {
        val volume = if (volume != MediaStore.VOLUME_EXTERNAL) volume else null
        if (volume != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            throw IllegalArgumentException("volume is unsupported value: $volume")
        val isDownload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isDownload else false
        return when (mediaType) {
            else if volume == null && isDownload -> @SuppressLint("NewApi")
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            else if isDownload -> @SuppressLint("NewApi") // lint false positive
                MediaStore.Downloads.getContentUri(volume!!)
            MEDIA_TYPE_AUDIO if volume == null -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
            // It's deprecated but also the only way to write to m3u files (which is the recommended
            // alternative to the deprecated feature). Apparently, someone didn't really understand
            // what they were deprecating when doing that...
            MEDIA_TYPE_PLAYLIST if volume == null ->
                @Suppress("deprecation") MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI
            MEDIA_TYPE_PLAYLIST -> @Suppress("deprecation")
                MediaStore.Audio.Playlists.getContentUri(volume)
            MEDIA_TYPE_IMAGE if volume == null -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
            MEDIA_TYPE_VIDEO if volume == null -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
            MEDIA_TYPE_NONE if volume == null -> FILES_EXTERNAL_CONTENT_URI
            MEDIA_TYPE_NONE -> MediaStore.Files.getContentUri(volume)
            // The only case where a file accessible with media permissions only can be written to
            // using a MediaStore.Files Uri in the restricted APIs.
            MEDIA_TYPE_SUBTITLE
                if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && volume == null ->
                    FILES_EXTERNAL_CONTENT_URI
            MEDIA_TYPE_SUBTITLE
                if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.Files.getContentUri(volume)
            // This would only happen for owned files, with granted uri permission, or if
            // MANAGE_EXTERNAL_STORAGE is granted
            MEDIA_TYPE_DOCUMENT
                if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && volume == null ->
                FILES_EXTERNAL_CONTENT_URI
            MEDIA_TYPE_DOCUMENT if Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                MediaStore.Files.getContentUri(volume)
            // shouldn't happen, but who knows what future Android versions add
            else if volume == null -> FILES_EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri(volume)
        }
    }

    private fun Cursor.rowToContentValues(): ContentValues {
        val values = ContentValues()
        for (i in 0..<columnCount) {
            val columnName = getColumnName(i)
            when (getType(i)) {
                Cursor.FIELD_TYPE_INTEGER ->
                    values.put(columnName, getLong(i))
                Cursor.FIELD_TYPE_FLOAT ->
                    values.put(columnName, getDouble(i))
                Cursor.FIELD_TYPE_STRING ->
                    values.put(columnName, getString(i))
                Cursor.FIELD_TYPE_BLOB ->
                    values.put(columnName, getBlob(i))
                Cursor.FIELD_TYPE_NULL ->
                    values.putNull(columnName)
            }
        }
        return values
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private val folders = hashMapOf(
        MEDIA_TYPE_AUDIO to setOf(
            Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_ALARMS,
            Environment.DIRECTORY_PODCASTS, Environment.DIRECTORY_NOTIFICATIONS,
            Environment.DIRECTORY_RINGTONES,
        ),
        MEDIA_TYPE_PLAYLIST to setOf(
            Environment.DIRECTORY_MUSIC),
        MEDIA_TYPE_VIDEO to setOf(
            Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES),
        MEDIA_TYPE_IMAGE to setOf(
            Environment.DIRECTORY_DCIM, Environment.DIRECTORY_PICTURES),
        MEDIA_TYPE_NONE to setOf(
            Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS),
    ).also {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            it[MEDIA_TYPE_DOCUMENT] = it[MEDIA_TYPE_NONE]!!
            it[MEDIA_TYPE_SUBTITLE] = setOf(
                Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_MOVIES)
            it[MEDIA_TYPE_PLAYLIST] = it[MEDIA_TYPE_PLAYLIST]!! + Environment.DIRECTORY_MOVIES
            it[MEDIA_TYPE_VIDEO] = it[MEDIA_TYPE_VIDEO]!! + Environment.DIRECTORY_PICTURES
            it[MEDIA_TYPE_AUDIO] = it[MEDIA_TYPE_AUDIO]!! + Environment.DIRECTORY_AUDIOBOOKS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 3) {
                it[MEDIA_TYPE_AUDIO] = it[MEDIA_TYPE_AUDIO]!! +
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            Environment.DIRECTORY_RECORDINGS else "Recordings"
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun canInsertIntoNoneWithRealMime(mediaType: Int): Boolean {
        // https://cs.android.com/android/_/android/platform/packages/providers/MediaProvider/+/94d8974d6b357f364607f13e0287bf8867ac90f1
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supportsWriteRequestForSidecar() ||
                mediaType != MEDIA_TYPE_SUBTITLE && mediaType != MEDIA_TYPE_PLAYLIST ||
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getOkFolders(mediaType: Int): Set<String> =
        (folders[mediaType] ?: emptySet()) + folders[MEDIA_TYPE_NONE]!!

    private fun computeFileAndMime(file: File, mimeType: String?): Pair<File, String> {
        var file = file
        var mimeType = mimeType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mimeType !=
            DocumentsContract.Document.MIME_TYPE_DIR) {
            // before Q the platform accepted any combination of extension+mime+media type, but no
            // longer, it now matters. Sadly they decided to silently rename files which we don't
            // want, hence we do validation here and throw if nonsense comes out
            val displayName = file.name
            // Exactly mirror platform logic in order to validate if arguments will be accepted
            val lastDot = displayName.lastIndexOf('.')
            var ext: String? = null
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastDot > 0 else lastDot >= 0) {
                ext = displayName.substring(lastDot + 1).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.lowercase() else it
                }
            }
            mimeType = mimeType ?: ext?.let {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it?.lowercase() else it
                }
            } ?: MIMETYPE_UNKNOWN
            val mimeTypeFromExt = ((if (ext != null)
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null)
                ?: MIMETYPE_UNKNOWN /* if no or unsupported extension, use unknown mime */).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.lowercase() else it
            }
            val extFromMimeType = if (mimeType != MIMETYPE_UNKNOWN)
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it?.lowercase() else it
                } else null
            if (mimeTypeFromExt != mimeType && ext != extFromMimeType) {
                var shouldChange = true
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    // Correct casing of extension to make MediaProvider happy (fixed in R)
                    // This doesn't matter as Android uses case-insensitive FS anyway
                    if (ext.equals(extFromMimeType, ignoreCase = true)) {
                        file = file.resolveSibling(
                            displayName.substring(0, lastDot + 1) + extFromMimeType
                        )
                        shouldChange = false
                    }
                    // mimeType casing can also mismatch but that is taken care of right below
                }
                if (shouldChange) {
                    mimeType = mimeTypeFromExt
                }
            }
        } else {
            mimeType = mimeType ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension) ?: MIMETYPE_UNKNOWN
        }
        return file to mimeType
    }

    /**
     * Guess the MIME type based on the file extension. Returns [MediaStoreCompat.MIMETYPE_UNKNOWN]
     * if the MIME type couldn't be determined.
     */
    @JvmStatic
    fun guessMimeTypeFromFileName(fileName: String): String {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName
            .substringAfterLast('.', "")) ?: MIMETYPE_UNKNOWN
    }

    /**
     * Convert a MIME type to a media type. This tries to use the system database using reflection
     * if possible, but falls back silently to a built-in mapping if that isn't possible.
     */
    @JvmStatic
    fun getMediaTypeForMime(mimeType: String?): Int {
        val m = mimeType?.lowercase()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val mediaFile = @SuppressLint("PrivateApi")
                Class.forName("android.media.MediaFile")
                val fileType = mediaFile.getMethod("getFileTypeForMimeType",
                    String::class.java).invoke(null, m) as Int
                if (mediaFile.getMethod("isPlayListFileType", Int::class
                        .java).invoke(null, fileType) as Boolean) {
                    return MEDIA_TYPE_PLAYLIST
                }
                if (mediaFile.getMethod("isAudioFileType", Int::class
                        .java).invoke(null, fileType) as Boolean) {
                    return MEDIA_TYPE_AUDIO
                }
                if (mediaFile.getMethod("isVideoFileType", Int::class
                        .java).invoke(null, fileType) as Boolean) {
                    return MEDIA_TYPE_VIDEO
                }
                if (mediaFile.getMethod("isImageFileType", Int::class
                        .java).invoke(null, fileType) as Boolean) {
                    return MEDIA_TYPE_IMAGE
                }
                return MEDIA_TYPE_NONE
            } catch (e: Exception) {
                Log.w(TAG, "failed to get file type from mime type", e)
            }
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            try {
                val mediaFile = @SuppressLint("PrivateApi")
                Class.forName("android.media.MediaFile")
                if (mediaFile.getMethod("isPlayListMimeType", String::class
                        .java).invoke(null, m) as Boolean) {
                    return MEDIA_TYPE_PLAYLIST
                }
                if (mediaFile.getMethod("isAudioMimeType", String::class
                        .java).invoke(null, m) as Boolean) {
                    return MEDIA_TYPE_AUDIO
                }
                if (mediaFile.getMethod("isVideoMimeType", String::class
                        .java).invoke(null, m) as Boolean) {
                    return MEDIA_TYPE_VIDEO
                }
                if (mediaFile.getMethod("isImageMimeType", String::class
                        .java).invoke(null, m) as Boolean) {
                    return MEDIA_TYPE_IMAGE
                }
                return MEDIA_TYPE_NONE
            } catch (e: Exception) {
                Log.w(TAG, "failed to get file type from mime type", e)
            }
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // Fallback: Mappings from MediaFile, order from ModernMediaScanner
            return when {
                m == "application/vnd.apple.mpegurl" -> MEDIA_TYPE_PLAYLIST
                m == "application/vnd.ms-wpl" -> MEDIA_TYPE_PLAYLIST
                m == "application/x-mpegurl" -> MEDIA_TYPE_PLAYLIST
                m == "audio/mpegurl" -> MEDIA_TYPE_PLAYLIST
                m == "audio/x-mpegurl" -> MEDIA_TYPE_PLAYLIST
                m == "audio/x-scpls" -> MEDIA_TYPE_PLAYLIST
                m?.startsWith("audio/") == true -> MEDIA_TYPE_AUDIO
                m?.startsWith("image/") == true -> MEDIA_TYPE_IMAGE
                m?.startsWith("video/") == true -> MEDIA_TYPE_VIDEO
                else -> MEDIA_TYPE_NONE
            }
        }
        // Mappings inside MediaProvider code so we can't access them, but they at least haven't
        // been updated since Android 11 (and they can't be customized by OEMs anymore), so we can
        // have a copy here just fine.
        return when {
            m == "application/vnd.apple.mpegurl" -> MEDIA_TYPE_PLAYLIST
            m == "application/vnd.ms-wpl" -> MEDIA_TYPE_PLAYLIST
            m == "application/x-extension-smpl" -> MEDIA_TYPE_PLAYLIST
            m == "application/x-mpegurl" -> MEDIA_TYPE_PLAYLIST
            m == "application/xspf+xml" -> MEDIA_TYPE_PLAYLIST
            m == "audio/mpegurl" -> MEDIA_TYPE_PLAYLIST
            m == "audio/x-mpegurl" -> MEDIA_TYPE_PLAYLIST
            m == "audio/x-scpls" -> MEDIA_TYPE_PLAYLIST
            m == "application/lrc" -> MEDIA_TYPE_SUBTITLE
            m == "application/smil+xml" -> MEDIA_TYPE_SUBTITLE
            m == "application/ttml+xml" -> MEDIA_TYPE_SUBTITLE
            m == "application/x-extension-cap" -> MEDIA_TYPE_SUBTITLE
            m == "application/x-extension-srt" -> MEDIA_TYPE_SUBTITLE
            m == "application/x-extension-sub" -> MEDIA_TYPE_SUBTITLE
            m == "application/x-extension-vtt" -> MEDIA_TYPE_SUBTITLE
            m == "application/x-subrip" -> MEDIA_TYPE_SUBTITLE
            m == "text/vtt" -> MEDIA_TYPE_SUBTITLE
            m == "application/vnd.ms-asf" -> MEDIA_TYPE_VIDEO
            m == "application/epub+zip" -> MEDIA_TYPE_DOCUMENT
            m == "application/msword" -> MEDIA_TYPE_DOCUMENT
            m == "application/pdf" -> MEDIA_TYPE_DOCUMENT
            m == "application/rtf" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-excel" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-excel.addin.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-excel.sheet.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-excel.template.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-powerpoint" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-powerpoint.addin.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-powerpoint.slideshow.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-powerpoint.template.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-word.document.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.ms-word.template.macroenabled.12" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.chart" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.database" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.formula" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.graphics" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.graphics-template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.presentation" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.presentation-template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.spreadsheet" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.spreadsheet-template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.text" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.text-master" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.text-template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.oasis.opendocument.text-web" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.presentationml.slideshow" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.presentationml.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.spreadsheetml.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.openxmlformats-officedocument.wordprocessingml.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.calc" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.chart" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.draw" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.impress" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.impress-packed" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.mail" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.math" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.writer" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.stardivision.writer-global" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.calc" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.calc.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.draw" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.draw.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.impress" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.impress.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.math" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.writer" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.writer.global" -> MEDIA_TYPE_DOCUMENT
            m == "application/vnd.sun.xml.writer.template" -> MEDIA_TYPE_DOCUMENT
            m == "application/x-mspublisher" -> MEDIA_TYPE_DOCUMENT
            m?.startsWith("audio/") == true -> MEDIA_TYPE_AUDIO
            m?.startsWith("image/") == true -> MEDIA_TYPE_IMAGE
            m?.startsWith("video/") == true -> MEDIA_TYPE_VIDEO
            m?.startsWith("text/") == true -> MEDIA_TYPE_DOCUMENT
            else -> MEDIA_TYPE_NONE
        }
    }

    /**
     * Checks whether a file can be created at this location. If the return value is null, [create]
     * can be called immediately, otherwise a [RequestToken] generated has to be passed to
     * [createWriteRequest] to gain authorization first.
     *
     * If you're not sure about [mimeType], set `null` to let the library automatically determine it.
     *
     * If the volume provided is `null`, [fileNameAndPath] must either be an absolute path pointing
     * to a mounted volume, or the device's internal storage will be used by default. If there is a
     * volume provided, [fileNameAndPath]  may either be an absolute path pointing to that volume,
     * or a relative path from the volume's root.
     *
     * This method assumes that, on Android 11 or later, a file will be created in an appropriate
     * directory allowed by MediaStore, or [Manifest.permission.MANAGE_EXTERNAL_STORAGE] is declared
     * in manifest (if an unsupported folder is requested, [createWriteRequest] will then ask for
     * that permission if declared).
     *
     * - [MEDIA_TYPE_AUDIO]: Music/, Alarms/, Ringtones/, Notifications/, Podcasts/, Audiobooks/
     *   (since Android 12, not backported since the folder didn't exist before 12: Recordings/)
     * - [MEDIA_TYPE_SUBTITLE]: Music/, Movies/
     * - [MEDIA_TYPE_PLAYLIST]: Music/, Movies/
     * - [MEDIA_TYPE_VIDEO]: DCIM/, Movies/, Pictures/
     * - [MEDIA_TYPE_IMAGE]: DCIM/, Pictures/
     * - [MEDIA_TYPE_NONE], [MEDIA_TYPE_DOCUMENT]: Download/, Documents/
     *
     * Note for Q only: From the platform side, allowed directories are a bit less extensive, and
     * MEDIA_TYPE_SUBTITLE does not exist on this version. If not adhering to Q's restricted folder
     * rules (because another older is required, which is a valid use case), R's enhanced rules are
     * backported using [Manifest.permission.WRITE_EXTERNAL_STORAGE]. It must thus be declared in
     * the manifest (and will be requested by the library when [createWriteRequest] is called). The
     * restricted folder rules are:
     *
     * - [MEDIA_TYPE_AUDIO]: Music/, Alarms/, Ringtones/, Notifications/, Podcasts/
     * - [MEDIA_TYPE_PLAYLIST]: Music/
     * - [MEDIA_TYPE_VIDEO]: DCIM/, Movies/
     * - [MEDIA_TYPE_IMAGE]: DCIM/, Pictures/
     * - [MEDIA_TYPE_NONE]: Download/, Documents/
     *
     * Nullable parameters are optional. This method has so many of them to be able to efficiently
     * check for create permission for a batch of [Uri]s with the least possible amount of repeated
     * Binder calls.
     */
    @JvmStatic
    @JvmOverloads
    fun needRequestCreate(context: Context, fileNameAndPath: String,
                          volumeCompat: StorageVolumeCompat? = null, mimeType: String? = null,
                          needsMkdirs: Boolean? = null, isManager: Boolean? = null,
                          volumesCache: List<StorageVolumeCompat>? = null,
                          persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        var fileIn = File(fileNameAndPath)
        var volumesCache = volumesCache
        var volumeCompat = volumeCompat
        if (fileIn.isAbsolute) {
            if (volumeCompat != null) {
                fileIn = fileIn.relativeTo(volumeCompat.requireCanonicalDirectory())
                if (fileIn.path.startsWith("../")) {
                    throw IllegalArgumentException("$fileIn not inside $volumeCompat")
                }
            } else {
                volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
                volumeCompat = StorageManagerCompat.getVolumeForPath(volumesCache, fileIn)
                fileIn = fileIn.relativeTo(volumeCompat.requireCanonicalDirectory())
            }
        } else if (volumeCompat == null) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volumeCompat = volumesCache.find { it.isPrimary || it.isEmulated }
                ?: throw IllegalStateException("Internal storage appears to be unavailable at this moment")
        }
        var (file, mimeTypeReal) = computeFileAndMime(fileIn, mimeType)
        val folderName = file.path.split('/', limit = 2)[0]
        @SuppressLint("NewApi") // folders array is fine to use in this isolated case
        // audio/3gpp <-> video/3gpp is the only case where one file extension (.3gpp) can map to
        // multiple media types, special case it for simplicity.
        if (mimeTypeReal == "audio/3gpp" && !folders[MEDIA_TYPE_AUDIO]!!.contains(folderName) &&
            folders[MEDIA_TYPE_VIDEO]!!.contains(folderName)) {
            mimeTypeReal = "video/3gpp"
        } else if (mimeTypeReal == "video/3gpp" && !folders[MEDIA_TYPE_VIDEO]!!
                .contains(folderName) && folders[MEDIA_TYPE_AUDIO]!!.contains(folderName)) {
            mimeTypeReal = "audio/3gpp"
        }
        val mediaType = if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
            getMediaTypeForMime(mimeTypeReal)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            folders.entries.find { it.value.contains(folderName) }?.key ?: MEDIA_TYPE_NONE
        else MEDIA_TYPE_NONE
        var wrongMediaTypeException: Exception? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mimeType?.equals(mimeTypeReal, ignoreCase = true) == false) {
            if (getMediaTypeForMime(mimeType) != mediaType) {
                wrongMediaTypeException = IllegalArgumentException(
                    "Sorry, the file ${file.name} can't be created with the MIME type" +
                            " $mimeTypeReal because Android doesn't recognize this MIME type," +
                            " and an attempt to use the supported MIME type" +
                            " $mimeTypeReal failed because the top-level folder " +
                            file.path.split('/', limit = 2)[0] + " is not " +
                            "allowed with $mimeTypeReal. Possible folders (with " +
                            "$mimeTypeReal) are ${getOkFolders(mediaType)}."
                )
            }
        }
        val relativePath = file.parent ?: ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (mimeTypeReal == DocumentsContract.Document.MIME_TYPE_DIR) {
                val firstFolderFromRoot = if (relativePath == "") file.name else relativePath
                if (folders.values.find { it.contains(firstFolderFromRoot) } == null) {
                    if (!canBecomeManager(context))
                        throw IllegalArgumentException("Creating a non-default top level folder " +
                                "requires MANAGE_EXTERNAL_STORAGE: $fileNameAndPath. The default " +
                                "folders are: ${folders.values.flatten()}")
                    if (isManager ?: Environment.isExternalStorageManager())
                        return null
                    return RequestToken.Manager
                }
                return null // We can create directories anywhere else with FUSE
            }
            val okFolders = getOkFolders(mediaType)
            // We can use MediaStore to insert any file in wrong directories if we're a manager
            if (!okFolders.contains(folderName) && !isAndroidMediaFolder(context, relativePath)) {
                if (!canBecomeManager(context)) {
                    if (wrongMediaTypeException != null)
                        throw wrongMediaTypeException
                    throw IllegalArgumentException(
                        "folder $folderName not allowed, allowed folders " +
                                "are $okFolders (can't request MANAGE_EXTERNAL_STORAGE)"
                    )
                }
                if (isManager ?: Environment.isExternalStorageManager())
                    return null
                Log.i(TAG, "Falling back to manager permission due to unsupported MIME" +
                        " (not an error, but maybe unexpected)", wrongMediaTypeException)
                return RequestToken.Manager
            }
            // We can use MediaStore to insert any kind of file in the appropriate directories
            return null
        }
        // We can use MediaStore to insert media files into primary or secondary storage in Q if the
        // file follows the legacy folder rules. (Except playlists which are affected by two bugs)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && mediaType != MEDIA_TYPE_PLAYLIST &&
            getOkFolders(mediaType).contains(folderName)) {
            return null
        }
        // because we only use SAF if canMediaProviderAccessSd() unexpectedly returns false, always
        // assert for WRITE_EXTERNAL_STORAGE. not having that permission declared here leads to bad
        // UX and uselessly complicated code for no clear benefit.
        if (!canGetWriteExternalStorage(context)) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
                throw IllegalArgumentException("WRITE_EXTERNAL_STORAGE has to be declared in " +
                        "manifest for Android 10 when not following Q's restricted folder rules: " +
                        "folder $folderName not allowed, allowed folders are ${getOkFolders(mediaType)}")
                    .apply { if (wrongMediaTypeException != null)
                        addSuppressed(wrongMediaTypeException) }
            else
                throw IllegalArgumentException("WRITE_EXTERNAL_STORAGE has to be declared for " +
                        "Android 9 and earlier to create files")
        }
        // MediaStore can also do non-media files on Q (assumes requestExternalLegacyStorage)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && (folders.values.find {
            it.contains(folderName) } != null || folderName == Environment.DIRECTORY_DOWNLOADS
                    || (needsMkdirs ?: !volumeCompat.requireCanonicalDirectory()
                        .resolve(relativePath).exists()))) {
            // if we pass legacy check (which requires to be granted write permission) we can use
            // DATA column to write to all kinds of storage even for non-media files. but only in
            // folders that already exist. (but we can create folders using dummy media files we
            // get rid of, so only non-default folders on SD card that don't exist require SAF)
            if (isManager ?: hasWriteExternalStorage(context))
                return null
            return RequestToken.Manager
        }
        // We can use the File API to create any file anywhere on the primary volume before R
        if (volumeCompat.isPrimary) {
            if (isManager ?: hasWriteExternalStorage(context))
                return null
            return RequestToken.Manager
        }
        // We may be able to use MediaStore to insert any kind of file on secondary storage volumes
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && (isManager ?:
        hasWriteExternalStorage(context)) && canMediaProviderAccessSd(context, volumeCompat)) {
            return null
            // we can afford to be a bit flexible here and use SAF if WRITE_EXTERNAL_STORAGE isn't
            // granted (that is, without asking for WRITE_EXTERNAL_STORAGE) if we already have SAF
            // grant for that path for some reason
        }
        // We'll have to use SAF, let's check if we already have the required permissions
        val needRequestManager = !(isManager ?: hasWriteExternalStorage(context))
        // for creating, we need storage permission even when using SAF, in order to insert row
        val safUri = getDocumentUriEx(context, null,
            volumeCompat.requireCanonicalDirectory().resolve(relativePath),
            volumeCompat.mediaStoreVolumeName,
            ResolvePermissions.OnlyPersistedTree, true, volumesCache,
            persistedUriPermissionsCache)
        if (safUri is String) {
            return RequestToken.Uri(safUri, needRequestManager)
        }
        if (needRequestManager) {
            return RequestToken.Manager
        }
        return null
    }

    private fun scanFileInternal(context: Context, file: String): Uri? {
        val result = AtomicReference<Uri>(null)
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context, arrayOf(file),
            null
        ) { _, mediaUri -> result.set(mediaUri); latch.countDown() }
        if (!latch.await(10, TimeUnit.SECONDS))
            throw IllegalStateException("Timed out")
        // Once/if we can ever deprecate Audio.Playlists Uri usage here entirely, we should look
        // into rewriting these Uris on the modern Android versions here as we wouldn't want to
        // poison the app with deprecated Uris if not needed.
        return result.get()
    }

    /**
     * MediaStore playlists are deprecated, and for a good reason: the design is suboptimal, and
     * it's better for all parties involved to just parse and write playlists themselves.
     *
     * On Android 11 and later, MediaStore playlists are ALWAYS a representation of what is on disk.
     * So there is no reason to interact with the playlist API anymore. (This method will return
     * false in any case on Android 11 and later)
     *
     * However, on Android 10 and earlier, it's possible MediaStore has modifications to a playlist
     * which are not represented in the disk version. This method compares the `mtime` to figure out
     * whether it's safe to ignore MediaStore (false is returned) and use
     * [ContentResolver.openInputStream] or similar methods to read the playlist, or whether
     * MediaStore's playlist should be used for reading the playlist (true is returned).
     *
     * For writing, NEVER use MediaStore playlists. Always use [openOutputStream] or similar methods
     * and write the playlist as bytes, and then use [scanFile] to synchronize the MediaStore
     * representation.
     */
    @DeprecatedSinceApi(Build.VERSION_CODES.R, "Always returns false on R+")
    fun shouldPreferAbstractPlaylistOverFile(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return false // yay, we can forget abstract playlists even exist! all is sane here!
        }
        var mediaFile: File? = null
        var lastModified: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DATE_MODIFIED
            ), null,
            null, null
        ).use {
            if (it == null || !it.moveToFirst())
                throw IllegalArgumentException("Can't resolve media Uri: $uri")
            mediaFile = File(
                it.getString(
                    it.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DATA
                    )
                )!!
            )
            // Will be null if playlist was always abstract. If this is not null but the file
            // doesn't exist, this row belongs to a playlist which used to be a file that was since
            // deleted without deleting the playlist row.
            lastModified = it.getLongOrNull(it.getColumnIndexOrThrow(
                MediaStore.MediaColumns.DATE_MODIFIED))
        }
        val fileLastModified = mediaFile!!.lastModified() / 1000L
        if (fileLastModified > (lastModified ?: 0L)) {
            // On next reboot, the abstract playlist's contents will be discarded, so it's stale.
            // It's likely the file was modified by a non-MediaStore-aware application on behalf of
            // the user, so we should read the file instead.
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fileLastModified > 0 &&
            fileLastModified == lastModified) {
            // On Android Q, equal mtime means the database representation wasn't modified at all.
            // Previously, this sadly did not apply.
            return false
        }
        // The file could've been possibly modified using the playlist API, and hence we must use
        // that to be safe.
        return true
    }

    /**
     * Scans a media file and returns the [Uri].
     *
     * This method is blocking and should hence be called on a background thread.
     *
     * This wrapper fixes an issue in Android versions before 10 where playlist files would not be
     * scanned by [MediaScannerConnection.scanFile], by scanning the whole volume instead and then
     * looking up the playlist manually, if scanning a playlist is requested.
     *
     * This method will return null if:
     * - the file does not exist
     * - or, if scanning timed out (message will be logged)
     * - or, on Android 10, if the file is hidden (name of it or a parent folder starts with dot)
     * - or, on Android 10, if one of the file's parents is a folder with .nomedia
     * - or, on Android 10, if the file is invalid (such as 0 bytes)
     *
     * If ensuring the database contains the latest scanner result is desired, this method is
     * optimal. But if it's desired to get a media [Uri] for a file on disk (or ensure such an [Uri]
     * at least exists in the database), prefer [getMediaUriForFile] instead because it works around
     * most issues causing scans to fail and if it can't, it will fail with clearer error messages.
     *
     * @see MediaScannerConnection.scanFile
     */
    @SuppressLint("SdCardPath")
    @JvmStatic
    fun scanFile(context: Context, file: String): Uri? {
        val file = if (file.startsWith("/sdcard", ignoreCase = true)) StorageManagerCompat
            .getStorageVolumes(context).first { it.isPrimary || it.isEmulated }
            .requireCanonicalDirectory().path + file.substring("/sdcard".length)
        else file
        return try {
            scanFileOrThrow(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "failed to scan", e)
            null
        }
    }

    /**
     * Scans a media file.
     *
     * If a file was edited with [openOutputStream] or similar methods, the system may not
     * automatically rescan the file on Android 11 or earlier. Calling this method manually helps
     * the system maintain an up-to-date mapping of files and metadata.
     *
     * This method is blocking and should hence be called on a background thread.
     *
     * This wrapper fixes an issue in Android versions before 10 where playlist files would not be
     * scanned by [MediaScannerConnection.scanFile], by scanning the whole volume instead, if
     * scanning a playlist is requested.
     *
     * @see MediaScannerConnection.scanFile
     */
    @JvmStatic
    @JvmOverloads
    fun scanFile(context: Context, uri: Uri, mediaFile: File? = null) {
        var mediaFile = mediaFile
        queryMissing(context, uri, null, null,
            null, mediaFile, needsOwner = false, needsType = false,
            needsIsDownload = false, needsFile = true
        ) { _, _, _, mediaFileH ->
            mediaFile = mediaFileH
        }
        scanFile(context, mediaFile!!.absolutePath)
        return
    }

    internal fun scanFileOrThrow(context: Context, file: String): Uri? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return scanFileInternal(context, file)
        }
        val ext = File(file).extension
        val isPlaylist = getMediaTypeForMime(MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext)) == MEDIA_TYPE_PLAYLIST
        if (!isPlaylist) {
            return scanFileInternal(context, file)
        }
        scanFileInternal(context, file)?.let {
            return it // if it works, it sure was worth the try, but usually it fails
        }
        scanVolumeLegacy(context, 5 * 60)
        return context.contentResolver.query(
            FILES_EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            ),
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(file),
            null
        ).use {
            if (it != null && it.moveToFirst())
                ContentUris.withAppendedId(
                    getBaseUriForMediaType(
                        VOLUME_EXTERNAL,
                        it.getInt(1)
                    ), it.getLong(0)
                )
            else null
        }
    }

    @DeprecatedSinceApi(Build.VERSION_CODES.Q, "doesn't work on Q+")
    private fun scanVolumeLegacy(context: Context, timeoutSecs: Long) {
        val latch = CountDownLatch(1)
        val finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                latch.countDown()
            }
        }
        val ht = HandlerThread("scanFile")
        ht.start()
        try {
            ContextCompat.registerReceiver(context, finishReceiver,
                IntentFilter(Intent.ACTION_MEDIA_SCANNER_FINISHED)
                    .apply { addDataScheme("file") },
                "android.permission.WRITE_MEDIA_STORAGE",
                Handler(ht.looper), ContextCompat.RECEIVER_EXPORTED)
            // Playlist files have a bug where they only get updated on volume scan
            context.startService(
                Intent("android.media.IMediaScannerService")
                    .setClassName( // the existence of this is CTS tested so it's safe
                        "com.android.providers.media",
                        "com.android.providers.media.MediaScannerService"
                    )
                    .putExtra("volume", VOLUME_EXTERNAL)
            )
            if (!latch.await(timeoutSecs, TimeUnit.SECONDS))
                throw IllegalStateException(
                    "failed to scan: Timed out waiting for volume scan " +
                            "after $timeoutSecs seconds"
                )
        } finally {
            try {
                context.unregisterReceiver(finishReceiver)
            } catch (_: IllegalArgumentException) {}
            ht.quitSafely()
        }
    }

    /**
     * Scan all files which are heuristically likely to have outdated metadata. Fast on Android 11
     * or later, slower on Android 10 or earlier.
     */
    @JvmStatic
    fun smartScan(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Scan pending-by-FUSE files to remove their pending flag. All other cases are handled
            // automatically by other apps or the system.
            context.contentResolver.query(FILES_EXTERNAL_CONTENT_URI,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                arrayOf(MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME)
                else arrayOf(MediaStore.Files.FileColumns.DATA),
                Bundle().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // Caution: on U+, a query() with OWNER_PACKAGE_NAME in selection/sort
                        // will filter the query to only our own owned files... so do the filter
                        // manually because in projection it's allowed (will get NULLed for
                        // privacy if we can't see the actual owner, but it's ok, we just want
                        // to filter out files we ourselves own).
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "LOWER(" +
                                "${MediaStore.MediaColumns.DATA}) NOT REGEXP '/\\.pending-[^/]+$'")
                    } else {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "LOWER(" +
                                "${MediaStore.MediaColumns.DATA}) NOT REGEXP '/\\.pending-[^/]+$'" +
                                " AND (${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} != ?" +
                                " OR ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} IS NULL)")
                        putStringArray(
                            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(context.packageName)) // TODO support shared uid? or not?
                    }
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                }, null)!!.use {
                val dataColumn = it.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.DATA)
                val ownerColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    it.getColumnIndexOrThrow(MediaStore.Files.FileColumns
                        .OWNER_PACKAGE_NAME) else null
                if (it.moveToFirst()) {
                    do {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            if (!isOwned(context, it.getStringOrNull(
                                    ownerColumn!!) ?: "null"))
                                scanFile(context, it.getString(dataColumn))
                        } else {
                            scanFile(context, it.getString(dataColumn))
                        }
                    } while (it.moveToNext())
                }
            }
        } else {
            SdScanner.scanEverything(context, ignoreMtime = false)
        }
    }

    /**
     * Scan all media files. This will take a long time, and is often only useful on Android 10 or
     * earlier versions.
     *
     * Default timeout for Android 9 or earlier is 30 minutes.
     */
    @JvmStatic
    @JvmOverloads
    fun scanEverything(context: Context, timeoutSecsForLegacy: Long = 30 * 60) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SdScanner.scanEverything(context)
        } else {
            try {
                scanVolumeLegacy(context, timeoutSecsForLegacy)
            } catch (e: Exception) {
                Log.w(TAG, "failed to scan", e)
            }
        }
    }

    /** @see ContentResolver.loadThumbnail */
    @JvmStatic
    @JvmOverloads
    fun loadThumbnail(context: Context, uri: Uri, size: Size,
                      cancellationSignal: CancellationSignal? = null): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri.authority != MediaStore.AUTHORITY) {
                throw IllegalArgumentException("Unsupported non-MediaStore uri: $uri")
            }
            return context.contentResolver.loadThumbnail(uri, size, cancellationSignal)
        }
        var mediaType: Int? = null
        queryMissing(context, uri, null, null,
            null, null, needsOwner = false, needsFile = false,
            needsType = true, needsIsDownload = false) { _, type, _, _ ->
            mediaType = type
        }
        when (mediaType) {
            MEDIA_TYPE_IMAGE -> {
                val kind = when {
                    size.width <= 96 && size.height <= 96 ->
                        @Suppress("deprecation") MediaStore.Images.Thumbnails.MICRO_KIND
                    size.width <= 512 && size.height <= 384 ->
                        @Suppress("deprecation") MediaStore.Images.Thumbnails.MINI_KIND
                    else -> @Suppress("deprecation")
                    MediaStore.Images.Thumbnails.FULL_SCREEN_KIND
                }
                val id = ContentUris.parseId(uri)
                cancellationSignal?.setOnCancelListener {
                    @Suppress("deprecation")
                    MediaStore.Images.Thumbnails.cancelThumbnailRequest(
                        context.contentResolver, id)
                }
                @Suppress("deprecation")
                return MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver,
                    id, kind, null)
                    ?: throw IOException("Failed to generate thumbnail (no details available)")
            }
            MEDIA_TYPE_VIDEO -> {
                val kind = when {
                    size.width <= 96 && size.height <= 96 ->
                        @Suppress("deprecation") MediaStore.Video.Thumbnails.MICRO_KIND
                    size.width <= 512 && size.height <= 384 ->
                        @Suppress("deprecation") MediaStore.Video.Thumbnails.MINI_KIND
                    else -> @Suppress("deprecation")
                    MediaStore.Video.Thumbnails.FULL_SCREEN_KIND
                }
                val id = ContentUris.parseId(uri)
                cancellationSignal?.setOnCancelListener {
                    @Suppress("deprecation")
                    MediaStore.Video.Thumbnails.cancelThumbnailRequest(
                        context.contentResolver, id)
                }
                @Suppress("deprecation")
                return MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver,
                    id, kind, null)
                    ?: throw IOException("Failed to generate thumbnail (no details available)")
            }
            MEDIA_TYPE_AUDIO -> {
                val thumbUri = ContentUris.appendId(MediaStore.Audio.Media
                    .EXTERNAL_CONTENT_URI.buildUpon(), ContentUris.parseId(uri))
                    .appendPath("albumart").build()
                cancellationSignal?.throwIfCanceled()
                try {
                    return context.contentResolver.openFileDescriptor(thumbUri, "r")
                        .use { pfdInput ->
                            pfdInput
                                ?: throw IOException("No thumbnail, perhaps this song has no artwork?")
                            cancellationSignal?.throwIfCanceled()
                            val maxSize = max(size.width, size.height)
                            val opts = BitmapFactory.Options()
                            opts.inJustDecodeBounds = true
                            BitmapFactory.decodeFileDescriptor(
                                pfdInput.fileDescriptor, null, opts
                            )
                            cancellationSignal?.throwIfCanceled()
                            opts.inJustDecodeBounds = false
                            val widthSample = opts.outWidth / maxSize
                            val heightSample = opts.outHeight / maxSize
                            opts.inSampleSize = max(widthSample, heightSample)
                            BitmapFactory.decodeFileDescriptor(
                                pfdInput.fileDescriptor, null, opts
                            )
                        }
                } catch (e: OperationCanceledException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Can't load thumbnail for $uri", e)
                }
            }
            else -> throw IOException("Can't load thumbnail for $uri due to unsupported type")
        }
    }

    // Improves performance when not having to call Binder over and over again
    // This cache only becomes invalid if one of the Uris inside is persisted (which wouldn't be our
    // fault, it would be app code); AND more than 122 other Uris are persisted after that point in
    // time. Which is a bit far-fetched, but I've documented it in the class doc comment.
    private val grantedSelfUriPrefixPermissionsCache = mutableSetOf<Uri>()
    private fun maybeGrantSelfUriPermission(context: Context, uri: Uri, flags: Int) {
        val isPersistPrefixRw = flags == (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (isPersistPrefixRw && grantedSelfUriPrefixPermissionsCache.contains(uri))
            return
        context.grantUriPermission(context.packageName, uri, flags)
        if (isPersistPrefixRw)
            grantedSelfUriPrefixPermissionsCache.add(uri)
    }

    @VisibleForTesting
    internal var mediaProviderSdCachedValue: Boolean? = null
    private const val MP_TEST_VER = 1 // bump this when outcome could change due to enhanced test
    // needs WRITE_EXTERNAL_STORAGE to be granted
    // ExternalStorageProvider on P can write to SD and MediaProvider cannot, because it uses
    // internal (/mnt/media_rw) paths and MediaProvider does not.
    private fun canMediaProviderAccessSd(context: Context, volume: StorageVolumeCompat): Boolean {
        // versions before O generally do, but we can't use it because we can't grant uri permission
        // to ourselves for a provider for which we have path-permission for, maybe an OEM changed
        // this, though, try our luck.
        // on O and O MR1 this is generally expected to return true.
        // P generally doesn't, but we can try our luck, maybe an OEM changed this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            throw IllegalStateException()
        mediaProviderSdCachedValue?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(
            "org.nift4.mediastorecompat_cache", Context.MODE_PRIVATE)
        val cacheBuildId = prefs.getString("BuildId", null)
        // if kernel - maybe the user uses custom kernels - or ROM changes, the result of this test
        // may change. otherwise it really should not, though.
        val buildId = Os.uname().run { machine + release + sysname + version } + MP_TEST_VER +
                Build.DEVICE + Build.FINGERPRINT + Build.TIME + Build.ID + Build.DISPLAY
        if (cacheBuildId == buildId) {
            return prefs.getBoolean("CanMediaProviderAccessSd", false)
                .also { mediaProviderSdCachedValue = it }
        }
        // grant ourselves access to the Files table to be able to interact with secondary storage
        context.checkGrantSelfUriPermission(FILES_EXTERNAL_CONTENT_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        var canAccessSd: Boolean? = null
        do {
            if (!context.checkSelfUriPermission(FILES_EXTERNAL_CONTENT_URI,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.e(TAG, "can't grant uri permission to self")
                } else {
                    // before O this is somewhat expected
                    Log.d(TAG, "can't grant uri permission to self")
                }
                canAccessSd = false
                break
            }
            if (volume.state != Environment.MEDIA_MOUNTED) {
                throw IllegalArgumentException("this volume is not mounted? $volume")
            }
            val appPath = context.getExternalFilesDirs(null).find {
                it?.let { StorageManagerCompat.isVolumeForPath(volume, it) } == true
            }
            if (appPath == null) {
                Log.i(TAG, "Unsupported volume: $volume, perhaps invisible volume?")
                // We cannot do the test with this SD card, but if this is actually a USB stick or
                // if there's a second SD card slot that may be adoptable, the result may change.
                // So we do not cache this (also, computing this is pretty quick so there is no
                // need to cache it).
                return false
            }
            if (Environment.getExternalStorageState(appPath) == Environment.MEDIA_MOUNTED_READ_ONLY) {
                throw IllegalArgumentException("this volume is mounted read-only: $volume")
            }
            if (Environment.getExternalStorageState(appPath) != Environment.MEDIA_MOUNTED) {
                throw IllegalArgumentException("this volume is not mounted: $volume")
            }
            // This path is 100% writable for us via File API, but may not be writable for MediaProvider
            val path = appPath.resolve(".mediaStoreCompat_deleteMe")
            var needScan = false
            if (path.exists()) {
                Log.e(TAG, "left-over test file found")
                if (path.delete()) {
                    needScan = true
                } else if (path.exists()) {
                    Log.e(TAG, "left-over test file found and File API returned false")
                    break // try again another day
                }
            }
            context.contentResolver.query(
                FILES_EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DATA} = ?",
                arrayOf(path.absolutePath), null
            ).use {
                if (it != null && it.moveToFirst()) {
                    needScan = true
                    try {
                        needScan = context.contentResolver.delete(
                            ContentUris.withAppendedId(
                                FILES_EXTERNAL_CONTENT_URI, it.getLong(
                                    0
                                )
                            ), null, null
                        ) != 1
                    } catch (t: Throwable) {
                        Log.e(TAG, "error cleaning up old file that should've been deleted", t)
                    }
                }
            }
            if (needScan) {
                scanFile(context, path.absolutePath)
            }
            // We'll have to test every trick we have to avoid unrecoverable failures later.
            // Start with creating a file.
            val msUri = try {
                context.contentResolver.insert(FILES_EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DATA, path.absolutePath)
                    put(MediaStore.Files.FileColumns.MEDIA_TYPE, MEDIA_TYPE_NONE)
                })
            } catch (t: Throwable) {
                Log.e(TAG, "insert() failed", t)
                break
            }
            if (msUri == null) {
                Log.e(
                    TAG, "insert() returned null, did MediaProvider crash or path end with " +
                            "trailing slash or file already exists? path = $path"
                )
                break
            }
            Log.i(TAG, "Insert $msUri ok, trying to open for write")
            val fd = try {
                context.contentResolver.openFileDescriptor(msUri, "wa")
            } catch (t: Throwable) {
                if (t.message == "open failed: EACCES (Permission denied)") {
                    canAccessSd = false // tested successfully, and the test result is that it can't
                } else if (t.message?.startsWith("open failed: ") == true) {
                    Log.e(TAG, "open failed with MediaProvider on SD (for test)", t)
                    if (Environment.getExternalStorageState(appPath) != Environment.MEDIA_MOUNTED) {
                        return false // wow, that's mean! let's try again after we got remounted
                    }
                } else {
                    // this is most likely a bug in our code, we'll save false and when it's fixed we'll
                    // re-check because MP_TEST_VER is bumped
                    canAccessSd = false
                    Log.e(TAG, "error when trying to detect if MediaProvider SD perm", t)
                    break
                }
                null
            }
            if (fd != null) {
                try {
                    fd.close()
                    if (!path.exists()) {
                        if (Environment.getExternalStorageState(appPath) != Environment.MEDIA_MOUNTED) {
                            return false // wow, that's mean! let's try again after we got remounted
                        }
                        // test bug?
                        canAccessSd = false
                        break
                    } else canAccessSd = true // yay!
                } catch (t: Throwable) {
                    Log.e(TAG, "error when closing while detecting MediaProvider SD perm", t)
                    // this means the SD was unmounted or something else happened and we got unlucky.
                }
            }
            try {
                if (context.contentResolver.update(msUri, ContentValues().apply {
                    put(MediaStore.Files.FileColumns.MEDIA_TYPE, MEDIA_TYPE_IMAGE)
                }, null, null) != 1)
                    throw IllegalStateException("update() failed")
            } catch (t: Throwable) {
                Log.e(TAG, "update() failed", t) // ???
                break
            }
            try {
                context.contentResolver.delete(msUri, null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "delete() failed", t) // ???
            }
            if (path.exists()) {
                Log.e(TAG, "delete() failed, fake success")
                if (!path.delete())
                    Log.e(TAG, "delete() failed, fake success and File API returned false")
                canAccessSd = false // while we can write, we cannot delete, so use SAF
                break
            }
            val folderPath = appPath.resolve(".MediaStoreCompat_tmp_deleteMe")
            try {
                mediaStoreMkdirs(context, volume, folderPath)
            } catch (t: Throwable) {
                // while we can write and delete, we cannot make folders, so use SAF
                Log.e(TAG, "mediaStoreMkdirs() failed", t)
                canAccessSd = false
                break
            } finally {
                folderPath.deleteRecursively()
            }
        } while (false)
        mediaProviderSdCachedValue = canAccessSd ?: false
        if (canAccessSd != null) {
            prefs.edit(commit = true) {
                putBoolean("CanMediaProviderAccessSd", canAccessSd)
                putString("BuildId", buildId)
            }
        }
        return canAccessSd ?: false
    }

    private fun Context.checkSelfUriPermission(uri: Uri, flags: Int): Boolean =
        checkUriPermission(uri, Process.myPid(), Process.myUid(), flags) ==
                PackageManager.PERMISSION_GRANTED

    private fun isMediaTypeForQ(mediaType: Int) =
        mediaType == MEDIA_TYPE_IMAGE || mediaType == MEDIA_TYPE_VIDEO
                || mediaType == MEDIA_TYPE_AUDIO || mediaType == MEDIA_TYPE_PLAYLIST

    private fun isMovableForQ(mediaType: Int) =
        mediaType == MEDIA_TYPE_IMAGE || mediaType == MEDIA_TYPE_VIDEO
                || mediaType == MEDIA_TYPE_AUDIO // || isDownload

    /**
     * Ability to call [android.content.ContentResolver.update] on the media [Uri] (NOT on the
     * collection [Uri] matching with a WHERE clause).
     */
    private const val PERMISSION_UPDATE_SQL = 0x1

    /**
     * Ability to call [android.content.ContentResolver.update] on the well-defined (ie not
     * [MediaStore.Files] or [MediaStore.Downloads]) collection [Uri], allowing for batch updates.
     *
     * This cannot be obtained in a normal way on Android 11 and later, unless if the permission is
     * already present (for example, due to owning the file) or with MANAGE_EXTERNAL_STORAGE.
     * [PERMISSION_UPDATE_SQL] (ie, using the specific media [Uri] instead of a collection [Uri] for
     * batch updates) is the recommended alternative.
     */
    private const val PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT = 0x2

    /**
     * Ability to call [android.content.ContentResolver.update] on the [MediaStore.Files] uri, and
     * if the file is a download, also on the [MediaStore.Downloads] uri, allowing for batch updates
     * across multiple media types.
     *
     * This cannot be obtained in a normal way on Android 11 and later, unless if the permission is
     * already present (for example, due to owning the file) or with MANAGE_EXTERNAL_STORAGE.
     * [PERMISSION_UPDATE_SQL] (ie, using the specific media [Uri] instead of a collection [Uri] for
     * batch updates) is the recommended alternative.
     *
     * This might be unavailable on Android 10 on removable storage if an OEM has customized the
     * firmware, [PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT] or [PERMISSION_UPDATE_SQL] would
     * have to be used instead.
     */
    private const val PERMISSION_UPDATE_SQL_FROM_FILES_PARENT = 0x4

    private const val PERMISSION_UPDATE_SQL_FROM_ANY_PARENT = PERMISSION_UPDATE_SQL or
        PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT or PERMISSION_UPDATE_SQL_FROM_FILES_PARENT

    /**
     * Ability to call [openFileDescriptor] with mode that contains `w`.
     */
    private const val PERMISSION_OPEN_FD_FOR_WRITE = 0x8

    /**
     * Ability to call [delete].
     *
     * Note: calling [createWriteRequest] with [PERMISSION_DELETE] and later calling [delete] would
     * result in dialog asking for permission to modify the file, while on Android 11 and later,
     * calling [createDeleteRequest] would result in a dialog asking for deleting the file. The
     * clearer dialog text from [createDeleteRequest] might result in a better user experience.
     */
    private const val PERMISSION_DELETE = 0x10

    /**
     * Ability to call [efficientMove].
     */
    private const val PERMISSION_EFFICIENT_MOVE = 0x20

    /**
     * Ability to call [efficientMove] (Q rules only).
     */
    private const val PERMISSION_EFFICIENT_MOVE_Q_RULES = 0x40

    /**
     * Ability to call [efficientMove] (Q rules only, but mismatching media type rules are OK).
     */
    private const val PERMISSION_EFFICIENT_MOVE_Q_RULES_FLEX = 0x80

    private const val PERMISSION_EFFICIENT_MOVE_ANY = PERMISSION_EFFICIENT_MOVE or
            PERMISSION_EFFICIENT_MOVE_Q_RULES or PERMISSION_EFFICIENT_MOVE_Q_RULES_FLEX

    private fun guessMediaTypeFromUri(uri: Uri): Int? {
        return when {
            uri.pathSegments[1] == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.pathSegments[1]
                    && uri.pathSegments[2] == MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                .pathSegments[2] -> MEDIA_TYPE_AUDIO
            uri.pathSegments[1] == @Suppress("deprecation") MediaStore.Audio.
            Playlists.EXTERNAL_CONTENT_URI.pathSegments[1] && uri.pathSegments[2] ==
                    @Suppress("deprecation") MediaStore.Audio.Playlists
                        .EXTERNAL_CONTENT_URI.pathSegments[2] -> MEDIA_TYPE_PLAYLIST
            uri.pathSegments[1] == MediaStore.Video.Media.EXTERNAL_CONTENT_URI.pathSegments[1] ->
                MEDIA_TYPE_VIDEO
            uri.pathSegments[1] == MediaStore.Images.Media.EXTERNAL_CONTENT_URI.pathSegments[1] ->
                MEDIA_TYPE_IMAGE
            else -> null
        }
    }

    private fun isOwned(context: Context, ownerPackageName: String): Boolean {
        return context.packageName == ownerPackageName // TODO support shared uid
    }

    private inline fun queryMissing(context: Context, mediaUri: Uri, ownerPackageName: String?,
                                    mediaType: Int?, isDownload: Boolean?, mediaFile: File?,
                                    needsOwner: Boolean, needsType: Boolean,
                                    needsIsDownload: Boolean, needsFile: Boolean,
                                    hook: (ownerPackageName: String?, mediaType: Int?,
                                           isDownload: Boolean?, mediaFile: File?) -> Unit) {
        if (mediaUri.authority?.equals(MediaStore.AUTHORITY) == false)
            throw IllegalArgumentException("Expected a MediaStore uri: $mediaUri")
        var mediaFile = mediaFile
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType ?: guessMediaTypeFromUri(mediaUri)
        var isDownload = isDownload ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mediaUri.pathSegments[1] == MediaStore.Downloads.EXTERNAL_CONTENT_URI.pathSegments[1])
            true else null)
        if (mediaType == null && needsType || mediaFile == null && needsFile
            || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ((needsOwner &&
                    ownerPackageName == null) || (needsIsDownload && isDownload == null)))) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (mediaUri.pathSegments[1] ==
                MediaStore.Downloads.EXTERNAL_CONTENT_URI.pathSegments[1] && mediaType == null
                && needsType || mediaUri.pathSegments[1] == @Suppress("deprecation")
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI.pathSegments[1] && needsOwner
                        && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                        ownerPackageName == null || isDownload == null && needsIsDownload)
            ) {
                // we have to query the files table to find out the media type
                try {
                    val msv = MediaStore.getVolumeName(mediaUri)
                    val id = ContentUris.parseId(mediaUri)
                    val contentUri = MediaStore.Files.getContentUri(msv)
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        // Increase our chances of the query below succeeding
                        context.checkGrantSelfUriPermission(
                            contentUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    // Querying the Files table gives us the media type, which we don't get from
                    // the Downloads table
                    context.contentResolver.query(
                        ContentUris.withAppendedId(contentUri, id),
                        arrayOf(
                            MediaStore.Files.FileColumns.DATA,
                            MediaStore.Files.FileColumns.IS_DOWNLOAD,
                            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                            MediaStore.Files.FileColumns.MEDIA_TYPE
                        ), null,
                        null, null
                    ).use {
                        if (it == null || !it.moveToFirst())
                            throw SecurityException()
                        mediaType = it.getInt(
                            it.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns.MEDIA_TYPE
                            )
                        )
                        isDownload = it.getInt(
                            it.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns.IS_DOWNLOAD
                            )
                        ) == 1
                        ownerPackageName = it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            )
                        ) ?: "null"
                        mediaFile = File(
                            it.getString(
                                it.getColumnIndexOrThrow(
                                    MediaStore.Files.FileColumns.DATA
                                )
                            )!!
                        )
                    }
                } catch (_: SecurityException) {
                    // may be thrown by query() or by our code when query() returns empty result
                    context.contentResolver.query(
                        mediaUri,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            arrayOf(
                                MediaStore.MediaColumns.DATA,
                                MediaStore.MediaColumns.IS_DOWNLOAD,
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            )
                        else if (guessMediaTypeFromUri(mediaUri) != MEDIA_TYPE_PLAYLIST) arrayOf(
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                        ) else arrayOf(MediaStore.MediaColumns.DATA), null,
                        null, null
                    ).use {
                        if (it == null || !it.moveToFirst())
                            throw IllegalArgumentException("Can't resolve media Uri: $mediaUri")
                        mediaFile = File(
                            it.getString(
                                it.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns.DATA
                                )
                            )!!
                        )
                        isDownload = isDownload ?:
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.getInt(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.IS_DOWNLOAD
                            )
                        ) == 1 else false // Due to lack of permissions we have to assume the worst
                        ownerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            || guessMediaTypeFromUri(mediaUri) != MEDIA_TYPE_PLAYLIST) it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            )
                        ) ?: "null" else ownerPackageName
                        // Due to lack of permissions we have to assume the worst
                        /* if (needsType) */ mediaType = mediaType ?: MEDIA_TYPE_NONE
                    }
                }
            } else if (mediaUri.pathSegments[1] == FILES_EXTERNAL_CONTENT_URI.pathSegments[1]) {
                context.contentResolver.query(
                    mediaUri,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        arrayOf(
                            MediaStore.Files.FileColumns.DATA,
                            MediaStore.Files.FileColumns.IS_DOWNLOAD,
                            MediaStore.Files.FileColumns.OWNER_PACKAGE_NAME,
                            MediaStore.Files.FileColumns.MEDIA_TYPE
                        )
                    else arrayOf(
                        MediaStore.Files.FileColumns.DATA,
                        MediaStore.Files.FileColumns.MEDIA_TYPE
                    ), null,
                    null, null
                ).use {
                    if (it == null || !it.moveToFirst())
                        throw IllegalArgumentException("Can't resolve media Uri: $mediaUri")
                    mediaType = it.getInt(
                        it.getColumnIndexOrThrow(
                            MediaStore.Files.FileColumns.MEDIA_TYPE
                        )
                    )
                    isDownload = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.getInt(
                            it.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns.IS_DOWNLOAD
                            )
                        ) == 1
                    } else false
                    ownerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            )
                        ) ?: "null"
                    } else null
                    mediaFile = File(
                        it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns.DATA
                            )
                        )!!
                    )
                }
            } else {
                context.contentResolver.query(
                    mediaUri,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        arrayOf(
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.IS_DOWNLOAD,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                        )
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        guessMediaTypeFromUri(mediaUri) != MEDIA_TYPE_PLAYLIST)
                        arrayOf(
                            MediaStore.MediaColumns.DATA,
                            MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                        )
                    else arrayOf(
                        MediaStore.MediaColumns.DATA
                    ), null,
                    null, null
                ).use {
                    if (it == null || !it.moveToFirst())
                        throw IllegalArgumentException("Can't resolve media Uri: $mediaUri")
                    mediaFile = File(
                        it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.DATA
                            )
                        )!!
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        isDownload = it.getInt(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.IS_DOWNLOAD
                            )
                        ) == 1
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        isDownload = false
                    }
                    ownerPackageName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        || Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
                        && guessMediaTypeFromUri(mediaUri) != MEDIA_TYPE_PLAYLIST) {
                        it.getString(
                            it.getColumnIndexOrThrow(
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            )
                        ) ?: "null"
                    } else null
                }
            }
        }
        hook(ownerPackageName, mediaType,
            isDownload, mediaFile)
    }

    private fun hasWriteExternalStorage(context: Context): Boolean {
        return canGetWriteExternalStorage(context) && ContextCompat.checkSelfPermission(context,
            Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * On Android 11+: returns whether [Manifest.permission.MANAGE_EXTERNAL_STORAGE] is granted.
     * On older versions: returns whether [Manifest.permission.WRITE_EXTERNAL_STORAGE] is granted.
     */
    @JvmStatic
    fun isManager(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            canBecomeManager(context) && Environment.isExternalStorageManager()
        } else {
            hasWriteExternalStorage(context)
        }
    }

    /**
     * Nullable parameters are optional. This method has so many of them to be able to query for
     * write permissions for a batch of [Uri]s with the least possible amount of repeated Binder
     * calls. Only Binder calls which have a different result for each [Uri] (that is basically just
     * [Context.checkUriPermission]) are exempted from being an optional parameter.
     *
     * [isManager] means [Environment.isExternalStorageManager] since R and grant state of
     * [Manifest.permission.WRITE_EXTERNAL_STORAGE] on Q and earlier.
     */
    private fun getWritePermissionInternal(
        context: Context, mediaUri: Uri, token: Boolean, isManager: Boolean?,
        requestedPerm: Int, ownerPackageName: String?, mediaType: Int?, isDownload: Boolean?,
        mediaFile: File?, volumesCache: List<StorageVolumeCompat>?,
        persistedUriPermissionsCache: List<UriPermission>?
    ): RequestToken? {
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType
        var isDownload = isDownload
        var mediaFile = mediaFile
        var volume: StorageVolumeCompat
        var volumesCache = volumesCache
        val uriIsNotWellDefined = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                mediaUri.pathSegments[1] ==
                MediaStore.Downloads.EXTERNAL_CONTENT_URI.pathSegments[1])
                || mediaUri.pathSegments[1] == FILES_EXTERNAL_CONTENT_URI.pathSegments[1]
        val maybePlaylistOrSubtitleOnR = Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                !supportsWriteRequestForSidecar() &&
                (uriIsNotWellDefined || guessMediaTypeFromUri(mediaUri) == MEDIA_TYPE_PLAYLIST)
        queryMissing(context, mediaUri, ownerPackageName, mediaType, isDownload, mediaFile,
            needsOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canBecomeManager(context) ||
                            isManager != true),
            needsFile = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || maybePlaylistOrSubtitleOnR
                    && (requestedPerm and (PERMISSION_EFFICIENT_MOVE_ANY or
                    PERMISSION_OPEN_FD_FOR_WRITE or PERMISSION_DELETE)) != 0,
            needsType = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q || token &&
                    uriIsNotWellDefined || maybePlaylistOrSubtitleOnR,
            needsIsDownload = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && (
                    requestedPerm == PERMISSION_EFFICIENT_MOVE_Q_RULES ||
                            (ownerPackageName == null || !isOwned(context, ownerPackageName)))) {
            ownerPackageNameH, mediaTypeH, isDownloadH, mediaFileH ->
            ownerPackageName = ownerPackageNameH
            mediaType = mediaTypeH
            isDownload = isDownloadH
            mediaFile = mediaFileH
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Clean and simple!
            val isManager = canBecomeManager(context) &&
                    (isManager ?: Environment.isExternalStorageManager())
            if ((isManager || isOwned(context, ownerPackageName!!)) && (requestedPerm and
                        (PERMISSION_UPDATE_SQL_FROM_ANY_PARENT or PERMISSION_EFFICIENT_MOVE_ANY or
                                PERMISSION_OPEN_FD_FOR_WRITE or PERMISSION_DELETE)) != 0)
                return null
            if (context.checkSelfUriPermission(mediaUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) && (requestedPerm and
                        (PERMISSION_UPDATE_SQL or PERMISSION_DELETE or PERMISSION_OPEN_FD_FOR_WRITE
                                or PERMISSION_EFFICIENT_MOVE_ANY)) != 0)
                return null
            if (supportsWriteRequestForSidecar() || !(mediaType == MEDIA_TYPE_SUBTITLE ||
                        mediaType == MEDIA_TYPE_PLAYLIST) ||
                (requestedPerm and (PERMISSION_EFFICIENT_MOVE_ANY or PERMISSION_OPEN_FD_FOR_WRITE or
                        PERMISSION_DELETE)) == 0) {
                if (token) {
                    if (requestedPerm == PERMISSION_UPDATE_SQL && !supportsWriteRequestForSidecar() &&
                        (mediaType == MEDIA_TYPE_SUBTITLE || mediaType == MEDIA_TYPE_PLAYLIST)
                    ) {
                        if (canBecomeManager(context)) {
                            return RequestToken.Manager
                        } else {
                            throw IllegalStateException(
                                "On Android 11 only, requesting SQL update permission for a " +
                                        "playlist or subtitle (or lyrics) file requires declaring" +
                                        " the MANAGE_EXTERNAL_STORAGE permission in manifest"
                            )
                        }
                    }
                    return if (requestedPerm != PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT
                        && requestedPerm != PERMISSION_UPDATE_SQL_FROM_FILES_PARENT
                    ) {
                        if (uriIsNotWellDefined) {
                            if (mediaType != MEDIA_TYPE_PLAYLIST && mediaType != MEDIA_TYPE_SUBTITLE
                                && mediaType != MEDIA_TYPE_VIDEO && mediaType != MEDIA_TYPE_IMAGE &&
                                mediaType != MEDIA_TYPE_AUDIO
                            ) {
                                if (canBecomeManager(context)) {
                                    return RequestToken.Manager
                                } else {
                                    throw IllegalStateException(
                                        "On Android 11 and later, requesting write permission for a " +
                                                "non-media file (which can only be read through a " +
                                                "grant from another app) requires either asking said " +
                                                "another app where the grant is from, or declaring" +
                                                " the MANAGE_EXTERNAL_STORAGE permission in manifest"
                                    )
                                }
                            }
                            if (mediaType != MEDIA_TYPE_SUBTITLE) {
                                throw IllegalArgumentException(
                                    "The media uri $mediaUri is not a well" +
                                            "-defined Uri. Due to Android limitations, the Uri must be of" +
                                            " the specific media type that is to be requested. Please " +
                                            "refer to the MediaStoreCompat.getBaseUriForMediaType() " +
                                            "documentation for more information. Media type is $mediaType"
                                )
                            }
                            // subtitles are ok as Files collection uri
                        }
                        RequestToken.Uri(mediaUri.toString(), false)
                    } else if (canBecomeManager(context)) {
                        RequestToken.Manager
                    } else {
                        throw IllegalStateException(
                            "On Android 11 and later, requesting a batch" +
                                    " updateMode for a file that does not already have it requires" +
                                    " Manifest.permission.MANAGE_EXTERNAL_STORAGE to be declared"
                        )
                    }
                }
                return RequestToken.Manager
            }
            // fall-through to SAF only for R workaround
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
        } else {
            // Ugh, time to suffer
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
            val isManager = canGetWriteExternalStorage(context) &&
                    (isManager ?: hasWriteExternalStorage(context))
            val hasTablePermission by lazy {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                    isOwned(context, ownerPackageName!!)
                ) true
                // we don't need WRITE_EXTERNAL_STORAGE for Q
                else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q || isManager) {
                    val volumeUri = FILES_EXTERNAL_CONTENT_URI
                    val specificTypeUri = if (mediaType != null && isMediaTypeForQ(mediaType))
                        getBaseUriForMediaType(null, mediaType)
                    else null
                    (try {
                        // Due to a bug in Android, we need to grant ourselves permission before writing
                        // non-media files to secondary storage. Q: also useful for batch-update calls
                        maybeGrantSelfUriPermission(
                            context, volumeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                        if (!context.checkSelfUriPermission(
                                volumeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        )
                            false // L~N-MR1 usually
                        else {
                            // also grant ourselves access to the specific table view if applicable to
                            // ensure any kind of access works no matter which view is used, just like
                            // in Android R
                            if (specificTypeUri != null) {
                                maybeGrantSelfUriPermission(
                                    context, specificTypeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            }
                            // A single file can have 3 valid Uris if it's both media and downloaded
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && isDownload!!) {
                                maybeGrantSelfUriPermission(
                                    context,
                                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            }
                            true
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "error when granting table permission, falling back", t)
                        false
                    }) && (if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        // in Q we need to grant both per-volume and all-volume view, who knows what
                        // caller is trying to use. it's more complex to communicate this than to just
                        // do this, and most importantly it allows app code to work the same way on
                        // every android version that has multi-volume MediaStore.
                        val volumeUri = MediaStore.Files.getContentUri(volume.mediaStoreVolumeName)
                        val specificVolumeUri = if (mediaType != null && isMediaTypeForQ(mediaType))
                            getBaseUriForMediaType(volume.mediaStoreVolumeName, mediaType)
                        else null
                        try {
                            // Due to a bug in Android, we need to grant ourselves permission before
                            // writing non-media files to secondary storage. It's also useful for
                            // batch-update calls
                            maybeGrantSelfUriPermission(
                                context, volumeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            )
                            // also grant ourselves access to the specific table view if applicable to
                            // ensure any kind of access works no matter which view is used, just like
                            // in Android R
                            if (specificVolumeUri != null) {
                                maybeGrantSelfUriPermission(
                                    context, specificVolumeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            }
                            // A single file can have 3 valid Uris if it's both media and downloaded
                            if (isDownload!!) {
                                maybeGrantSelfUriPermission(
                                    context,
                                    MediaStore.Downloads.getContentUri(
                                        volume.mediaStoreVolumeName!!
                                    ),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            }
                            true
                        } catch (t: Throwable) {
                            Log.w(TAG, "error when granting table permission, falling back", t)
                            false
                        }
                    } else true)
                } else false
            }
            var flags = 0
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                if (isManager && volume.isPrimary && (requestedPerm and (PERMISSION_DELETE or
                            PERMISSION_OPEN_FD_FOR_WRITE or PERMISSION_UPDATE_SQL_FROM_ANY_PARENT or
                            PERMISSION_EFFICIENT_MOVE_ANY)) != 0
                ) // File API
                    return null
                else if (hasTablePermission)
                    flags = PERMISSION_UPDATE_SQL_FROM_ANY_PARENT or PERMISSION_DELETE or
                            PERMISSION_OPEN_FD_FOR_WRITE
                else if (isManager && isMediaTypeForQ(mediaType!!)) // MediaStore API (move is below)
                    flags = PERMISSION_UPDATE_SQL or PERMISSION_DELETE or
                            PERMISSION_OPEN_FD_FOR_WRITE or
                            PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT
                else if (requestedPerm == PERMISSION_UPDATE_SQL_FROM_FILES_PARENT) {
                    if (token)
                        throw IllegalStateException(
                            "Files batch update is impossible to obtain on " +
                                    "this Android Q customized OEM system with SD card files, sorry"
                        )
                    else
                        return RequestToken.Manager
                } else if (requestedPerm != PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT &&
                    context.checkSelfUriPermission(
                        mediaUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                )
                    flags = PERMISSION_UPDATE_SQL or PERMISSION_DELETE or
                            PERMISSION_OPEN_FD_FOR_WRITE
                if (requestedPerm == PERMISSION_EFFICIENT_MOVE_Q_RULES_FLEX) {
                    if (hasTablePermission)
                        flags = flags or PERMISSION_EFFICIENT_MOVE_Q_RULES_FLEX
                } else if (requestedPerm == PERMISSION_EFFICIENT_MOVE_Q_RULES) {
                    if ((flags and PERMISSION_UPDATE_SQL) != 0 &&
                        (isMovableForQ(mediaType!!) || isDownload!!)
                    )
                        flags = flags or PERMISSION_EFFICIENT_MOVE_Q_RULES
                    else if (isManager && isDownload!!)
                    // moving in Downloads also works without update_sql, but we want storage to verify
                        flags = flags or PERMISSION_EFFICIENT_MOVE_Q_RULES
                }
            } else {
                if (isManager) {
                    // canWrite: https://github.com/d4rken-org/sdmaid/issues/312#issuecomment-191460988
                    if ((volume.isPrimary || mediaFile.canWrite()) && (requestedPerm and (
                                PERMISSION_DELETE or PERMISSION_OPEN_FD_FOR_WRITE or
                                        PERMISSION_UPDATE_SQL_FROM_ANY_PARENT or
                                        PERMISSION_EFFICIENT_MOVE_ANY)) != 0
                    )
                        return null
                    flags = PERMISSION_UPDATE_SQL_FROM_ANY_PARENT
                } else if ((requestedPerm and PERMISSION_EFFICIENT_MOVE_ANY) == 0) {
                    if (hasTablePermission) {
                        flags = PERMISSION_UPDATE_SQL_FROM_ANY_PARENT
                    } else if (context.checkSelfUriPermission(
                            mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    )
                        flags = PERMISSION_UPDATE_SQL
                }
                // and now check if we can use MediaStore for some more advanced stuff
                if ((flags and PERMISSION_UPDATE_SQL) != 0 && (volume.isPrimary || (isManager
                            && (requestedPerm == PERMISSION_OPEN_FD_FOR_WRITE || requestedPerm ==
                            PERMISSION_DELETE) && hasTablePermission &&
                            canMediaProviderAccessSd(context, volume)))
                )
                    flags = flags or PERMISSION_DELETE or PERMISSION_OPEN_FD_FOR_WRITE
            }
            if ((flags and requestedPerm) != 0)
                return null // yay, our permission is granted
            if ((requestedPerm and PERMISSION_UPDATE_SQL_FROM_ANY_PARENT) != 0) {
                // SQL update is never requested using SAF, so exit early here.
                return RequestToken.Manager
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N && !volume.isPrimary &&
            (requestedPerm and PERMISSION_EFFICIENT_MOVE_ANY) != 0) {
            if (token)
                throw IllegalArgumentException("Efficient move is not supported on removable " +
                            "storage before Android 7")
            else
                return RequestToken.Manager
        }
        val safUri = getDocumentUriEx(context, mediaUri, mediaFile,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                volume.mediaStoreVolumeName else null, ResolvePermissions.OnlyPersistedTree,
                true, volumesCache, persistedUriPermissionsCache)
        if (safUri !is String)
            return null
        return if (!volume.isPrimary && (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !isMediaTypeForQ(mediaType!!))
            || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            RequestToken.Uri(safUri, false)
        } else {
            RequestToken.Manager
        }
    }

    private fun isAndroidMediaFolder(context: Context, folder: String): Boolean {
        if (!folder.startsWith("Android/media/"))
            return false
        val packageName = folder.substring("Android/media/".length).trimEnd { it == '/' }
        if (packageName == context.packageName) // TODO should this do shared uid? check system
            return true
        return context.packageManager.getPackageInfo(packageName, 0)
            .applicationInfo?.uid == Process.myUid()
    }

    /**
     * Change the [MediaStore.MediaColumns.OWNER_PACKAGE_NAME] of a file to this app. This is useful
     * to be able to change this file repeatedly in the future without asking for permission over
     * and over again. No-op if this app already owns this file.
     *
     * There's no official API to do this, so it is achieved by deleting the old file and creating
     * it again, with the same content. This is merely a convenience wrapper around other methods in
     * this class that are used to achieve this.
     *
     * This method requires Android 11 because, while [MediaStore.MediaColumns.OWNER_PACKAGE_NAME]
     * was introduced in Android 10, there is no scenario in which you could temporarily access the
     * file and then loose access later in Android 10 when using this library's methods to gain
     * access. But this kind of temporary access is granted by [createWriteRequest] on Android 11
     * and later, and this method allows "converting" that temporary access into a permanent one.
     *
     * This may be intrusive to other apps and hence should only be done if you are sure the user
     * will primarily interact with this file through your app.
     *
     * @return The media [Uri] of the adopted file.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun adoptFile(context: Context, uri: Uri, ownerPackageName: String? = null,
                  mediaType: Int? = null, isDownload: Boolean? = false, mediaFile: File? = null,
                  isManager: Boolean? = null, volumesCache: List<StorageVolumeCompat>? = null,
                  persistedUriPermissionsCache: List<UriPermission>? = null): Uri {
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType
        var isDownload = isDownload
        var mediaFile = mediaFile
        queryMissing(context, uri, ownerPackageName, mediaType, isDownload, mediaFile,
            needsOwner = true, needsType = true, needsIsDownload = true, needsFile = true) {
            ownerPackageNameH, mediaTypeH, isDownloadH, mediaFileH ->
            ownerPackageName = ownerPackageNameH
            mediaType = mediaTypeH
            isDownload = isDownloadH
            mediaFile = mediaFileH
        }
        if (isOwned(context, ownerPackageName!!))
            return uri // nothing to do here :)
        val persistedUriPermissionsCache = persistedUriPermissionsCache ?:
        (if (!supportsWriteRequestForSidecar() &&
            (mediaType == MEDIA_TYPE_PLAYLIST || mediaType == MEDIA_TYPE_SUBTITLE))
            context.contentResolver.persistedUriPermissions else null)
        var step = 0
        val volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
        val isManager = isManager ?: isManager(context)
        val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
        val inputStream = openInputStream(context, uri, ownerPackageName,
            mediaType, mediaFile, isManager, volumesCache, persistedUriPermissionsCache)!!
        var uri = uri
        var newUri: Uri? = null
        try {
            uri = markIsTrashedStatus(
                context, uri, true, ownerPackageName, mediaType, isDownload,
                mediaFile, isManager, volumesCache, persistedUriPermissionsCache)
            step = 1
            newUri = create(
                context, mediaFile.path, volume, null, relatedUri = uri,
                isManager, volumesCache, persistedUriPermissionsCache
            )!!
            step = 2
            openOutputStream(
                context, newUri, "wa", context.packageName,
                null, null, isManager, volumesCache,
                persistedUriPermissionsCache
            )!!.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            step = 3
            inputStream.close()
            delete(context, uri, ownerPackageName, mediaType, isDownload, null, isManager,
                volumesCache, persistedUriPermissionsCache)
            step = 4
            finishCreate(context, newUri, mediaFile, volumesCache)
            return newUri
        } finally {
            try {
                if (step in 1..3) {
                    markIsTrashedStatus(
                        context, uri, false, ownerPackageName, mediaType, isDownload,
                        null, isManager, volumesCache, persistedUriPermissionsCache
                    )
                }
                if (step == 2) {
                    delete(
                        context, newUri!!, isManager = isManager, volumesCache = volumesCache,
                        persistedUriPermissionsCache = persistedUriPermissionsCache
                    )
                }
                if (step < 3) {
                    inputStream.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up after adopt failed", e)
            }
        }
    }

    /**
     * Convenience method to check whether the access required to adopt this item needs to be
     * requested. If this method returns a non-null value, use [createWriteRequest] to gain access
     * to the file and then call [adoptFile]. If it returns a null value, you can use [adoptFile]
     * immediately.
     *
     * Nullable arguments are optional.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun needRequestAdoption(context: Context, mediaUri: Uri, ownerPackageName: String? = null,
                          mediaType: Int? = null, isDownload: Boolean? = null,
                          mediaFile: File? = null, isManager: Boolean? = null,
                          volumesCache: List<StorageVolumeCompat>? = null,
                          persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        // If we can delete it, we can also trash it, and that's all we need to adopt it.
        // The creation part will never need extra permissions due to QUERY_ARG_RELATED_URI.
        return getWritePermissionInternal(context, mediaUri, true,
            isManager, PERMISSION_DELETE, ownerPackageName, mediaType, isDownload,
            mediaFile, volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Efficient move or rename (ie without rewriting the data on disk). Only possible within a
     * single volume because it'd be a copying move otherwise.
     *
     * Parent folders of the new file will be automatically created if required.
     *
     * [newPathAndName] may be absolute on the mounted volume the file already resides on, or
     * relative to the root of the file's volume.
     *
     * Caution: if [android.provider.DocumentsProvider.renameDocument] has to be used on Android 11
     * for a subtitle or playlist file, the media ID might change. Use [getMediaUriForFile] with the
     * returned file to obtain it.
     *
     * Copying move is the act of creating the target file, opening the source file for read, then
     * reading from source and writing to target until all contents are copied, and then deleting
     * the source file. Because efficient move/rename is often the only operation requiring
     * additional permission requests, for small files, copying move may be a better alternative.
     *
     * Note: It's not possible to efficient-move files on secondary storage before Android 7. This
     * method will throw [SecurityException] if that is requested. Copying move is an alternative
     * that is available.
     *
     * Nullable parameters are optional. This method has so many of them to be able to efficiently
     * move the files behind a batch of [Uri]s with the least possible amount of repeated Binder
     * calls.
     *
     * Throws [SecurityException] if [needRequestEfficientMove] would return true.
     *
     * Throws if the target file already exists. To overwrite a file, first delete and then move.
     *
     * This method assumes that, on Android 11 or later, if file to be moved is a media file, that
     * it will be moved to an appropriate directory allowed by MediaStore on these versions.
     *
     * If the ID refers to a folder and can be accessed, the entire folder including its contents is
     * moved. Accessing a folder via its ID requires MANAGE_EXTERNAL_STORAGE on Android 11 or later,
     * but this capability may still be useful on older Android versions. Alternatively, if the ID
     * is not known, File.renameTo() can be used (if and only if this app owns all files in it) on
     * the folder on Android 11 and later.
     */
    @JvmStatic
    @JvmOverloads
    // TODO: can i do format 0x3001 trick on modern android to move files where they don't belong?
    fun efficientMove(context: Context, uri: Uri, newPathAndName: String,
                      ownerPackageName: String? = null, mediaType: Int? = null,
                      isDownload: Boolean? = null, mediaFile: File? = null,
                      isManager: Boolean? = null, skipPlaylistName: Boolean = false,
                      volumesCache: List<StorageVolumeCompat>? = null,
                      persistedUriPermissionsCache: List<UriPermission>? = null): File {
        if (uri.authority?.equals(MediaStore.AUTHORITY) == false)
            throw IllegalArgumentException("Expected a MediaStore uri: $uri")
        var volumesCache = volumesCache
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType
        var isDownload = isDownload
        var mediaFile = mediaFile
        var isManager = isManager
        val newPathAndNameFile = File(newPathAndName)
        val newRelativePath: String
        var volume: StorageVolumeCompat? = null
        var forceMove = false
        if (newPathAndNameFile.isAbsolute) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volume = StorageManagerCompat.getVolumeForPath(volumesCache, newPathAndNameFile)
            newRelativePath = newPathAndNameFile
                .toRelativeString(volume.requireCanonicalDirectory())
        } else {
            newRelativePath = newPathAndName
        }
        val folderName = newRelativePath.split('/', limit = 2)[0]
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!supportsWriteRequestForSidecar()) {
                isManager = isManager ?: isManager(context)
            }
            // forceMove uses a bug in FUSE implementation to move files to non-default top level
            forceMove = folders.values.find { it.contains(folderName) } == null &&
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.R && folderName == "Recordings" &&
                    !(isManager ?: isManager(context))
        }
        queryMissing(context, uri, ownerPackageName, mediaType, isDownload,
            mediaFile, needsOwner = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
                    || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !supportsWriteRequestForSidecar(),
            needsFile = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || forceMove ||
                    isAffectedByMoveGenericVolumeBug() || volume != null, needsType =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !forceMove
                    || !supportsWriteRequestForSidecar(),
            needsIsDownload = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !forceMove) {
            ownerPackageNameH, mediaTypeH, isDownloadH, mediaFileH ->
            ownerPackageName = ownerPackageNameH
            mediaType = mediaTypeH
            isDownload = isDownloadH
            mediaFile = mediaFileH
        }
        if (volume != null && mediaFile!!.toRelativeString(volume
                .requireCanonicalDirectory()).startsWith("../")) {
            throw IllegalArgumentException("$newPathAndName is not inside current $volume ($mediaFile)")
        }
        volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
        volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
        val newFile = volume.requireCanonicalDirectory().resolve(newRelativePath)
        if (newFile.exists()) {
            throw IllegalArgumentException("Target file exists: $newFile")
        }
        if (!mediaFile.exists()) {
            if (mediaType == MEDIA_TYPE_PLAYLIST && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                // Abstract playlists _can_ move
                context.checkGrantSelfUriPermission(@Suppress("deprecation")
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                if (context.contentResolver.update(ContentUris.withAppendedId(
                    @Suppress("deprecation") MediaStore.Audio.Playlists
                        .EXTERNAL_CONTENT_URI, ContentUris.parseId(uri)),
                    ContentValues().apply {
                        put(@Suppress("deprecation") MediaStore.Audio.Playlists.NAME,
                            File(newRelativePath).nameWithoutExtension)
                        put(@Suppress("deprecation") MediaStore.Audio.Playlists.DATA,
                            newFile.absolutePath)
                    }, null, null) != 1)
                    throw IllegalStateException("Failed to rename abstract playlist")
                return newFile
            }
            throw IllegalArgumentException("File that doesn't exist can't move: $mediaFile")
        }
        // If there's another dead column with this path, move will fail spectacularly
        // So, delete it. (But only if the file doesn't exist, or we might cause data loss.)
        // Potential data loss is also why abstract playlists are excluded on the versions where
        // they exist.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.delete(FILES_EXTERNAL_CONTENT_URI, Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns.DATA} = ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                        newFile.absolutePath))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                })
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(
                    @Suppress("deprecation")
                    MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI),
                    "${MediaStore.Files.FileColumns.DATA} = ? AND" +
                            " ${MediaStore.Files.FileColumns.MEDIA_TYPE} != $MEDIA_TYPE_PLAYLIST",
                    arrayOf(newFile.absolutePath)
                )
            } else {
                context.contentResolver.delete(
                    FILES_EXTERNAL_CONTENT_URI,
                    "${MediaStore.Files.FileColumns.DATA} = ? AND" +
                            " ${MediaStore.Files.FileColumns.MEDIA_TYPE} != $MEDIA_TYPE_PLAYLIST",
                    arrayOf(newFile.absolutePath)
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to delete duplicates", t)
        }
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.contentResolver.query(
                FILES_EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Files.FileColumns._ID),
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns.DATA} = ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(newFile.absolutePath))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                },
                null
            )
        } else {
            context.contentResolver.query(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    @Suppress("deprecation")
                    MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI)
                else FILES_EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Files.FileColumns._ID),
                "${MediaStore.Files.FileColumns.DATA} = ?",
                arrayOf(newFile.absolutePath),
                null
            )
        }).use { cursor ->
            if (cursor != null && cursor.moveToFirst()) {
                throw IllegalArgumentException("There is a conflicting row which could not be " +
                        "automatically cleaned up which would cause move to fail: " +
                        ContentUris.withAppendedId(FILES_EXTERNAL_CONTENT_URI,
                            cursor.getLong(cursor.getColumnIndexOrThrow(
                                MediaStore.Files.FileColumns._ID))))
            }
        }
        var displayName = newFile.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pattern = Regex("""\.(pending|trashed)-\d+-(.+)""",
                RegexOption.IGNORE_CASE)
            val match = pattern.matchEntire(newFile.name)
            var shouldBePendingR = false
            var shouldBeTrashed = false
            if (match != null) {
                shouldBePendingR = match.groupValues[1] == "pending"
                shouldBeTrashed = match.groupValues[1] == "trashed"
                displayName = match.groupValues[2]
            }
            val match2 = pattern.matchEntire(mediaFile.name)
            var isPendingR = false
            var isTrashed = false
            if (match2 != null) {
                isPendingR = match2.groupValues[1] == "pending"
                isTrashed = match2.groupValues[1] == "trashed"
            }
            if (supportsWriteRequestForSidecar() || (mediaType != MEDIA_TYPE_SUBTITLE && mediaType
                        != MEDIA_TYPE_PLAYLIST) || isManager!!
                || isOwned(context, ownerPackageName!!)) {
                if (forceMove) {
                    if (volume.requireCanonicalDirectory().resolve(folderName).isDirectory) {
                        newFile.parentFile?.mkdirs()
                        if (!mediaFile.renameTo(newFile)) {
                            throw SecurityException(
                                "Moving a file into the non-default top level folder " +
                                        "$folderName unexpectedly requires MANAGE_EXTERNAL_STORAGE " +
                                        "permission. The default folders are: ${folders.values.flatten()}"
                            )
                        }
                        if (mediaType == MEDIA_TYPE_PLAYLIST) {
                            val playlistUri = ContentUris.withAppendedId(
                                @Suppress("deprecation")
                                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                                ContentUris.parseId(uri))
                            var mimeType: String? = null
                            if (isAffectedByPlaylistMimeReset()) {
                                mimeType = context.contentResolver.query(playlistUri,
                                    arrayOf(MediaStore.MediaColumns.MIME_TYPE),
                                    null, null).use { cursor ->
                                    if (cursor == null || !cursor.moveToFirst())
                                        throw IllegalArgumentException("Can't resolve media Uri: $uri")
                                    cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.MediaColumns.MIME_TYPE))
                                }
                            }
                            if (context.contentResolver.update(playlistUri, ContentValues().apply {
                                    put(@Suppress("deprecation")
                                    MediaStore.Audio.Playlists.NAME, displayName
                                        .substringBeforeLast('.'))
                                    if (mimeType != null) {
                                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                    }
                                }, null, null) != 1)
                                throw IllegalStateException("update() for playlist failed")
                        }
                        return newFile
                    }
                } else {
                    // Creates all parent folders for us if needed. Here used to be "Nice and simple as
                    // usual :)" but honestly it's really not simple anymore. FUSE would be easier, but it
                    // has no way to surface error messages.
                    var uri = uri
                    val msv = if (isAffectedByMoveGenericVolumeBug() &&
                        MediaStore.getVolumeName(uri) == MediaStore.VOLUME_EXTERNAL) {
                        // https://issuetracker.google.com/issues/350540990
                        if (volume.mediaStoreVolumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY)
                            volume.mediaStoreVolumeName
                        else MediaStore.VOLUME_EXTERNAL
                    } else MediaStore.getVolumeName(uri)
                    val newPath = File(newRelativePath)
                    var outMediaType = mediaType!!
                    var outIsDownload = isDownload
                    if (!isDownload!! || folderName != Environment.DIRECTORY_DOWNLOADS) {
                        outIsDownload = false
                        if (folders[mediaType]?.contains(folderName) != true &&
                            canInsertIntoNoneWithRealMime(mediaType) &&
                            folders[MEDIA_TYPE_NONE]!!.contains(folderName)
                        ) {
                            outMediaType = MEDIA_TYPE_NONE
                        }
                    }
                    uri = ContentUris.withAppendedId(
                        getBaseUriForMediaType(
                            msv, outMediaType, outIsDownload
                        ),
                        ContentUris.parseId(uri)
                    )
                    var mimeType: String? = null
                    if (isAffectedByPlaylistMimeReset() && (!skipPlaylistName ||
                        guessMediaTypeFromUri(uri) == MEDIA_TYPE_PLAYLIST)) {
                        mimeType = context.contentResolver.query(
                            uri, arrayOf(MediaStore.MediaColumns.MIME_TYPE),
                            null, null).use { cursor ->
                            if (cursor == null || !cursor.moveToFirst())
                                throw IllegalArgumentException("Can't resolve media Uri: $uri")
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(
                                    MediaStore.MediaColumns.MIME_TYPE
                                )
                            )
                        }
                    }
                    // also works for folders if you know their IDs
                    if (context.contentResolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, newPath.parent ?: "")
                            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                            // By adding IS_PENDING field, scan will be triggered, skip this if we
                            // don't actually change that field.
                            if (shouldBePendingR != isPendingR) {
                                put(MediaStore.MediaColumns.IS_PENDING,
                                    if (shouldBePendingR) 1 else 0)
                            }
                            if (shouldBeTrashed != isTrashed) {
                                put(MediaStore.MediaColumns.IS_TRASHED,
                                    if (shouldBeTrashed) 1 else 0)
                            }
                            if (guessMediaTypeFromUri(uri) == MEDIA_TYPE_PLAYLIST) {
                                if (mimeType != null) {
                                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                }
                                if (!skipPlaylistName) {
                                    put(@Suppress("deprecation")
                                    MediaStore.Audio.Playlists.NAME, displayName
                                        .substringBeforeLast('.'))
                                }
                            }
                        }, null, null) != 1)
                        throw IllegalStateException("update() failed")
                    if (mediaType == MEDIA_TYPE_PLAYLIST && !skipPlaylistName &&
                        guessMediaTypeFromUri(uri) != MEDIA_TYPE_PLAYLIST) {
                        val playlistUri = ContentUris.withAppendedId(
                            getBaseUriForMediaType(msv, mediaType),
                            ContentUris.parseId(uri)
                        )
                        if (context.contentResolver.update(playlistUri, ContentValues().apply {
                                put(@Suppress("deprecation")
                                MediaStore.Audio.Playlists.NAME, displayName
                                    .substringBeforeLast('.'))
                                if (mimeType != null) {
                                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                }
                            }, null, null) != 1)
                            throw IllegalStateException("update() for playlist failed")
                    }
                    return newFile
                }
            }
            // Moving a playlist requires updating the NAME field with SQL update, which requires
            // owning it on Android 11 only. But because this is such a specific version and file
            // type combination, these files are often small, and we have a method to change owner
            // using copying move, just silently do it. The alternative is bothering the developer
            // that uses this method with doing it manually in this specific case only.
            if (mediaType == MEDIA_TYPE_PLAYLIST && !skipPlaylistName) {
                // However, we have to be careful because adoptFile calls markIsTrashedStatus
                // which calls back here, and that call is something we'd like to permit. Thus
                // make markIsTrashedStatus set skipPlaylistName=true to bypass this.
                val newUri = adoptFile(context, uri, ownerPackageName, mediaType, isDownload,
                    mediaFile, isManager, volumesCache, persistedUriPermissionsCache)
                // Forcing ownerPackageName to context.packageName prevents infinite recursion,
                // and is also correct after adoptFile() because that's the whole point.
                return efficientMove(context, newUri, newPathAndName,
                    context.packageName, null, null,
                    null, isManager, false, volumesCache,
                    persistedUriPermissionsCache)
            }
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // Q move doesn't allow Files and Playlist collections (but Downloads is allowed oddly
            // enough). This is then combined with permission checks for everything except Downloads
            // (thus possible to bypass the primary-volume-or-owned-files-only restriction for
            // Downloads for fs move, but not for db update).
            val id = ContentUris.parseId(uri)
            // We can fake the media type of any kind of file, so if the target directory is one of
            // the allowed ones for any kind of media, then we can move the file there.
            var fakeMediaType = if (isMovableForQ(mediaType!!) && folders[mediaType]!!.find {
                        prefix -> newRelativePath.startsWith("$prefix/", ignoreCase = true)
                        || newRelativePath.equals(prefix, ignoreCase = true) } != null)
                mediaType
            else folders.filter { isMovableForQ(it.key) }.entries.find { it.value.find {
                    prefix -> newRelativePath.startsWith("$prefix/", ignoreCase = true) ||
                        newRelativePath.equals(prefix, ignoreCase = true) } != null }?.key
            var fakeIsDownload = newRelativePath.startsWith(
                "${Environment.DIRECTORY_DOWNLOADS}/", ignoreCase = true) || newRelativePath
                    .equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true)
            if (fakeIsDownload && !isDownload!!) {
                context.checkGrantSelfUriPermission(ContentUris.removeId(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                try {
                    if (context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_DOWNLOAD, 1)
                    }, null, null) != 1)
                        throw IllegalStateException("update() failed")
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to change IS_DOWNLOAD", t)
                    fakeIsDownload = false
                }
            }
            if (fakeIsDownload || fakeMediaType != null) {
                val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile)
                var baseUri: Uri? = getBaseUriForMediaType(volume.mediaStoreVolumeName,
                        fakeMediaType ?: MEDIA_TYPE_NONE, fakeIsDownload)
                var fileUri: Uri? = null
                var mediaFileUsedToExist = false
                var targetFileUsedToNotExist = false
                // Increase our chances this is successful
                if (!isOwned(context, ownerPackageName!!) &&
                    !context.checkGrantSelfUriPermission(baseUri!!,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)) {
                    // If we don't have storage permission and can't get table permission either,
                    // don't risk the move maybe succeeding but this being undetectable due to
                    // downloads bug. Just fall back
                    fakeMediaType = null // don't consider faking media type in this case
                    if (fakeIsDownload && hasWriteExternalStorage(context)) {
                        // have storage permission, let's get the information needed to detect if
                        // the move was a success through the storage permission instead of
                        // MediaStore. it might work because MediaStore has a bug allowing moves to
                        // work without having any kind of permission (but we only try if we can
                        // detect the result to increase reliability and avoid being chaotic). see:
                        // https://cs.android.com/android/_/android/platform/packages/providers/MediaProvider/+/3f12cfbd7f7d76e9908ebe9285f6d0c8bc1e7775
                        mediaFileUsedToExist = mediaFile.exists()
                        targetFileUsedToNotExist = !newFile.exists()
                    } else {
                        baseUri = if (isMovableForQ(mediaType) && folders[mediaType]!!.find {
                            prefix -> newRelativePath.startsWith("$prefix/",
                                ignoreCase = true) || newRelativePath.equals(prefix,
                                ignoreCase = true) } != null)
                            getBaseUriForMediaType(volume.mediaStoreVolumeName, mediaType)
                        else null
                        if (baseUri != null) {
                            // Increase our chances this is successful
                            context.checkGrantSelfUriPermission(baseUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            )
                        }
                    }
                } else if (fakeMediaType != mediaType) {
                    val baseUriForFile = getBaseUriForMediaType(volume.mediaStoreVolumeName,
                        MEDIA_TYPE_NONE)
                    context.checkGrantSelfUriPermission(baseUriForFile,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    fileUri = ContentUris.withAppendedId(baseUriForFile,
                        ContentUris.parseId(uri))
                    try {
                        if (context.contentResolver.update(fileUri, ContentValues().apply {
                            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                            put(MediaStore.Files.FileColumns.MEDIA_TYPE, fakeMediaType)
                        }, null, null) != 1)
                            throw IllegalStateException("update() failed")
                    } catch (t: Throwable) {
                        Log.e(TAG, "failed to change MEDIA_TYPE", t)
                        fakeMediaType = null
                        baseUri = null // we'll have to use SAF
                    }
                }
                if (baseUri != null) {
                    val specificUri = ContentUris.withAppendedId(baseUri, id)
                    val newPath = File(newRelativePath)
                    val isDirAndFakeMedia = fakeMediaType != null && mediaType != fakeMediaType &&
                            mediaFile.isDirectory
                    // Creates all parent folders for us if needed. (This also supports folders.)
                    val rows = context.contentResolver.update(specificUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, newPath.parent ?: "")
                        put(MediaStore.MediaColumns.DISPLAY_NAME, newPath.name)
                        if (fakeMediaType != null && mediaType != fakeMediaType) {
                            // Setting IS_PENDING allows to set format which allows the extension
                            // correction logic to be bypassed, allowing freeform renames.
                            put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                            put("format", 0x3001)
                        }
                        if (fakeIsDownload && !isDownload!!) {
                            // We had changed the download flag to be able to move this, so
                            // we'll have to undo that now.
                            put(MediaStore.MediaColumns.IS_DOWNLOAD, 0)
                        }
                    }, null, null)
                    if (rows == 1) { // normal success
                        if (fakeMediaType != null && mediaType != fakeMediaType) {
                            // We had changed the media type to be able to move this, so we'll have
                            // to undo that now.
                            if (context.contentResolver.update(fileUri!!,
                                ContentValues().apply {
                                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                                    put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType)
                                    put("format", when (mediaType) {
                                        else if isDirAndFakeMedia -> 0x3001
                                        MEDIA_TYPE_AUDIO, MEDIA_TYPE_PLAYLIST -> 0xB900
                                        MEDIA_TYPE_IMAGE -> 0x3800
                                        MEDIA_TYPE_VIDEO -> 0xB980
                                        else -> 0x3000
                                    })
                                }, null, null) != 1)
                                Log.e(TAG, "Failed to update file in MediaStore (1)")
                        }
                        if (mediaType == MEDIA_TYPE_PLAYLIST && !skipPlaylistName) {
                            context.checkGrantSelfUriPermission(@Suppress("deprecation")
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                            if (context.contentResolver.update(ContentUris.withAppendedId(
                                    @Suppress("deprecation")
                                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, id),
                                    ContentValues().apply {
                                        put(@Suppress("deprecation")
                                        MediaStore.Audio.Playlists.IS_PENDING, 0)
                                        put(@Suppress("deprecation")
                                        MediaStore.Audio.Playlists.NAME, newFile.nameWithoutExtension)
                                    }, null, null) != 1)
                                throw IllegalStateException("Failed to update playlist in MediaStore")
                        } else {
                            if (context.contentResolver.update(fileUri!!,
                                ContentValues().apply {
                                    put(MediaStore.Files.FileColumns.IS_PENDING, 0)
                                }, null, null) != 1)
                                throw IllegalStateException("Failed to update file in MediaStore")
                        }
                        return newFile
                    } else if (rows == 0 && fakeIsDownload && ((mediaFileUsedToExist &&
                                !mediaFile.exists()) || (targetFileUsedToNotExist &&
                                newFile.exists()))
                    ) {
                        // we have storage permission but weren't allowed to edit downloads table,
                        // and file was on SD card
                        try {
                            scanFile(context, mediaFile.path)
                        } catch (_: Exception) {}
                        getMediaUriForFile(context, newFile.path)
                        return newFile
                    }
                }
            }
        }
        // canWrite: https://github.com/d4rken-org/sdmaid/issues/312#issuecomment-191460988
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            (volume.isPrimary || mediaFile.canWrite())) {
            if (volume.isPrimary && !hasWriteExternalStorage(context)) {
                throw SecurityException("WRITE_EXTERNAL_STORAGE is required for moving files " +
                        "before Android Q")
            }
            if (!newFile.parentFile!!.exists() && !newFile.parentFile!!.mkdirs()) {
                throw IllegalStateException("Failed to mkdirs() folders")
            }
            if (!mediaFile.renameTo(newFile))
                throw IllegalStateException("Failed to renameTo($newFile) file")
            if (mediaType == MEDIA_TYPE_PLAYLIST && !skipPlaylistName) {
                context.checkGrantSelfUriPermission(@Suppress("deprecation")
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                if (context.contentResolver.update(ContentUris.withAppendedId(
                        @Suppress("deprecation") MediaStore.Audio.Playlists
                            .EXTERNAL_CONTENT_URI, ContentUris.parseId(uri)),
                        ContentValues().apply {
                            put(@Suppress("deprecation") MediaStore.Audio.Playlists.NAME,
                                newFile.nameWithoutExtension)
                            put(@Suppress("deprecation") MediaStore.Audio.Playlists.DATA,
                                newFile.absolutePath)
                        }, null, null) != 1)
                    throw IllegalStateException("Failed to update playlist in MediaStore")
            } else if (context.contentResolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DATA, newFile.absolutePath)
                    }, null, null) != 1) {
                Log.e(TAG, "Failed to update file in MediaStore")
            }
            return newFile
        }
        var safUri: Any? = null
        var safTargetUri: Any? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val newPath = File(newRelativePath)
            safUri = getDocumentUriEx(
                context, uri, mediaFile,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    volume.mediaStoreVolumeName else null, ResolvePermissions.OnlyPersistedTree,
                    forWrite = true, volumesCache = volumesCache, persistedUriPermissionsCache
            )
            safTargetUri = getDocumentUriEx(
                context, null, newFile.parentFile,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    volume.mediaStoreVolumeName else null, ResolvePermissions.OnlyPersistedTree,
                forWrite = true, volumesCache = volumesCache, persistedUriPermissionsCache
            )
            if (safUri is Uri && safTargetUri is Uri) {
                val safId = DocumentsContract.getDocumentId(safUri)
                val oldPath = File(StorageManagerCompat.getExternalStoragePath(safId))
                val oldParent = DocumentsContract.buildDocumentUriUsingTree(
                    safUri,
                    StorageManagerCompat.buildExternalStorageDocumentId(
                        StorageManagerCompat.getExternalStorageVolumeName(safId),
                        oldPath.parent ?: ""
                    )
                )
                mkdirsSaf(context.contentResolver, safTargetUri,
                    newPath.parent ?: "")
                val moveResult = if (oldPath.parent != newPath.parent) {
                    DocumentsContract.moveDocument(
                        context.contentResolver, safUri,
                        oldParent, safTargetUri
                    )
                } else safUri
                if (moveResult != null) {
                    val renameResult = if (oldPath.name != newPath.name) {
                        DocumentsContract.renameDocument(context.contentResolver,
                            moveResult, newPath.name)
                    } else moveResult
                    if (renameResult != null) {
                        val rows = if (mediaType == MEDIA_TYPE_PLAYLIST) {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                                context.checkGrantSelfUriPermission(
                                    @Suppress("deprecation")
                                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                )
                            }
                            val playlistUri = ContentUris.withAppendedId(
                                @Suppress("deprecation") MediaStore.Audio
                                    .Playlists.EXTERNAL_CONTENT_URI,
                                ContentUris.parseId(uri))
                            var mimeType: String? = null
                            if (isAffectedByPlaylistMimeReset()) {
                                mimeType = context.contentResolver.query(playlistUri,
                                    arrayOf(MediaStore.MediaColumns.MIME_TYPE),
                                    null, null).use { cursor ->
                                    if (cursor == null || !cursor.moveToFirst())
                                        throw IllegalArgumentException("Can't resolve media Uri: $uri")
                                    cursor.getString(cursor.getColumnIndexOrThrow(
                                        MediaStore.MediaColumns.MIME_TYPE))
                                }
                            }
                            context.contentResolver.update(playlistUri, ContentValues()
                                .apply {
                                    if (!skipPlaylistName) {
                                        put(@Suppress("deprecation") MediaStore.Audio
                                            .Playlists.NAME, displayName
                                                .substringBeforeLast('.'))
                                    }
                                    put(@Suppress("deprecation")
                                        MediaStore.Audio.Playlists.DATA, newFile.absolutePath)
                                    if (mimeType != null) {
                                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                                    }
                                }, null, null)
                        } else {
                            context.contentResolver.update(uri, ContentValues().apply {
                                put(MediaStore.MediaColumns.DATA, newFile.absolutePath)
                            }, null, null)
                        }
                        if (rows != 1) {
                            if (Build.VERSION.SDK_INT != Build.VERSION_CODES.R ||
                                    supportsWriteRequestForSidecar())
                                throw IllegalStateException("Failed to update file $uri in MediaStore")
                            getMediaUriForFile(context, newFile.absolutePath)
                        }
                        return newFile
                    } else {
                        throw IllegalStateException("rename $safUri to ${newPath.name} failed")
                    }
                } else {
                    throw IllegalStateException("move $safUri failed: $oldParent to $safTargetUri")
                }
            }
        }
        throw SecurityException("failed to move file, apparently don't have permissions " +
                "(file: $mediaFile - doc: $safUri - target: $safTargetUri)")
    }

    /**
     * Checks if a permission request is required to perform efficient move or rename (ie without
     * rewriting the data on disk). Only possible within a single volume because it'd be a copying
     * move otherwise. If this returns true, use [createWriteRequest] to gain permission.
     *
     * [newPathWithoutName] may be absolute on the mounted volume the file already resides on, or
     * relative to the root of the file's volume.
     *
     * Copying move is the act of creating the target file, opening the source file for read, then
     * reading from source and writing to target until all contents are copied, and then deleting
     * the source file. Because efficient move/rename is often the only operation requiring
     * additional permission requests, for small files, copying move may be a better alternative.
     *
     * Note: It's not possible to efficient-move files on secondary storage before Android 7. This
     * method will throw [IllegalArgumentException] if that is requested. Copying move is an alternative
     * that is available.
     *
     * Nullable parameters are optional. This method has so many of them to be able to efficiently
     * move the files behind a batch of [Uri]s with the least possible amount of repeated Binder
     * calls.
     *
     * This method assumes that, on Android 11 or later, if file to be moved is a media file, that
     * it will be moved to an appropriate directory allowed by MediaStore on these versions.
     */
    @JvmStatic
    @JvmOverloads
    fun needRequestEfficientMove(context: Context, uri: Uri, newPathWithoutName: String,
                                 ownerPackageName: String? = null, mediaType: Int? = null,
                                 isDownload: Boolean? = null, mediaFile: File? = null,
                                 isManager: Boolean? = null,
                                 volumesCache: List<StorageVolumeCompat>? = null,
                                 persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType
        var isDownload = isDownload
        var mediaFile = mediaFile
        var volumesCache = volumesCache
        val newPathWithoutNameFile = File(newPathWithoutName)
        val newParent: String
        var volume: StorageVolumeCompat? = null
        if (newPathWithoutNameFile.isAbsolute) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volume = StorageManagerCompat.getVolumeForPath(volumesCache, newPathWithoutNameFile)
            newParent = newPathWithoutNameFile.toRelativeString(
                volume.requireCanonicalDirectory())
        } else {
            newParent = newPathWithoutName
        }
        val newRelative = File(newParent)
        val rootFolder = newRelative.path.split('/', limit = 2)[0]
        val forceMove = Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                rootFolder == "Recordings" && folders.values.find { it.contains(rootFolder) } ==
                null && !(isManager ?: isManager(context))
        queryMissing(context, uri, ownerPackageName, mediaType, isDownload, mediaFile,
            needsOwner = false, needsFile = true, needsType = true, needsIsDownload = false
        ) { ownerPackageNameH, mediaTypeH, isDownloadH, mediaFileH ->
            mediaType = mediaTypeH
            mediaFile = mediaFileH
            // We may get those for free due to type check query despite needs flags being false
            ownerPackageName = ownerPackageNameH
            isDownload = isDownloadH
        }
        if (mediaType == MEDIA_TYPE_PLAYLIST && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            && !mediaFile!!.exists()) { // abstract playlists are special
            return getWritePermissionInternal(context, uri, true, isManager,
                PERMISSION_UPDATE_SQL, ownerPackageName, mediaType, isDownload,
                mediaFile, volumesCache, null)
        }
        if (volume != null && mediaFile!!.toRelativeString(volume
                .requireCanonicalDirectory()).startsWith("../")) {
            throw IllegalArgumentException("$newPathWithoutName is not inside current $volume ($mediaFile)")
        }
        val invalidPath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !getOkFolders(mediaType!!).contains(rootFolder) && !forceMove
        if (invalidPath) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
            // Same folder gets an exception, as well as Android/media/$packageName/
            if (volume.requireCanonicalDirectory().resolve(newParent) !=
                mediaFile.parentFile && !isAndroidMediaFolder(context, newParent)) {
                if (!canBecomeManager(context))
                    throw IllegalArgumentException("folder $rootFolder not allowed, allowed folders " +
                            "are ${getOkFolders(mediaType)} (can't request MANAGE_EXTERNAL_STORAGE)")
                if (isManager ?: Environment.isExternalStorageManager())
                    return null
                return RequestToken.Manager
            }
        }
        val qRulesOk = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
                folders[mediaType!!]?.contains(rootFolder) == true
        val mask = if (qRulesOk) PERMISSION_EFFICIENT_MOVE_Q_RULES else if (
            folders.filter { isMovableForQ(it.key) }.entries.find { it.value.find {
                    prefix -> newParent.startsWith("$prefix/", ignoreCase = true) ||
                    newParent.equals(prefix, ignoreCase = true) } != null }
                ?.let { isMovableForQ(it.key) } == true ||
            newParent.startsWith("${Environment.DIRECTORY_DOWNLOADS}/",
                ignoreCase = true) || newParent.equals(Environment.DIRECTORY_DOWNLOADS,
                ignoreCase = true)
        ) PERMISSION_EFFICIENT_MOVE_Q_RULES_FLEX else PERMISSION_EFFICIENT_MOVE
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isAffectedByMoveGenericVolumeBug()) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
            if (volume.mediaStoreVolumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY)
                uri
            else (MediaStore.AUTHORITY_URI.buildUpon()
                .appendPath(volume.mediaStoreVolumeName).build().toString() +
                    uri.toString().substring(
                        MediaStore.AUTHORITY_URI.buildUpon()
                            .appendPath(MediaStore.getVolumeName(uri))
                            .build().toString().length
                    )).toUri()
        } else uri
        return getWritePermissionInternal(context, uri,
            true, isManager, mask, ownerPackageName, mediaType,
            isDownload, mediaFile, volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Delete a file.
     *
     * Nullable parameters are optional. This method has so many of them to be able to efficiently
     * delete the files behind a batch of [Uri]s with the least possible amount of repeated Binder
     * calls.
     *
     * If the ID refers to an empty folder and can be accessed, the folder is deleted. Accessing a
     * folder via its ID requires MANAGE_EXTERNAL_STORAGE on Android 11 or later, but this
     * capability may still be useful on older Android versions. Alternatively, if the ID
     * is not known, File.delete() can be used if the folder is empty and not a top-level folder, on
     * Android 11 and later.
     */
    @JvmStatic
    @JvmOverloads
    fun delete(context: Context, uri: Uri, ownerPackageName: String? = null,
               mediaType: Int? = null, isDownload: Boolean? = null,
               mediaFile: File? = null, isManager: Boolean? = null,
               volumesCache: List<StorageVolumeCompat>? = null,
               persistedUriPermissionsCache: List<UriPermission>? = null) {
        var mediaType = mediaType
        var mediaFile = mediaFile
        var ownerPackageName = ownerPackageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // This does support empty folders if you have their ID. But you can't get this ID
            // without manager permission, so it's a bit pointless.
            if (uri.authority?.equals(MediaStore.AUTHORITY) == false)
                throw IllegalArgumentException("Expected a MediaStore uri: $uri")
            var canUseMediaStore = true
            if (!supportsWriteRequestForSidecar() && !(isManager ?: isManager(context))) {
                queryMissing(context, uri, ownerPackageName, mediaType, null,
                    mediaFile, needsOwner = true, needsFile = true, needsType = true,
                    needsIsDownload = false) {
                        ownerPackageNameH, mediaTypeH, _, mediaFileH ->
                    mediaType = mediaTypeH
                    mediaFile = mediaFileH
                    ownerPackageName = ownerPackageNameH
                }
                if (!isOwned(context, ownerPackageName!!) && (mediaType == MEDIA_TYPE_SUBTITLE
                            || mediaType == MEDIA_TYPE_PLAYLIST)) {
                    canUseMediaStore = false
                }
            }
            if (canUseMediaStore) {
                if (context.contentResolver.delete(uri, null) != 1)
                    throw IllegalArgumentException("nothing was deleted: $uri")
                return
            }
        }
        var isDownload = isDownload
        queryMissing(context, uri, ownerPackageName, mediaType, null,
            mediaFile, needsOwner = true, needsFile = true,
            needsType = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q,
            needsIsDownload = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            ownerPackageNameH, mediaTypeH, isDownloadH, mediaFileH ->
            mediaType = mediaTypeH
            mediaFile = mediaFileH
            isDownload = isDownloadH
            ownerPackageName = ownerPackageNameH
        }
        val volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
        val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && volume.isPrimary) {
            try {
                // ContentResolver.delete() is required for (abstract) playlists to delete properly
                if (context.contentResolver.delete(uri, null, null) != 1)
                    throw IllegalArgumentException("nothing was deleted: $uri")
                if (!mediaFile.exists())
                    return
            } catch (e: Exception) {
                Log.w(TAG, "failed to tell mediastore to delete file", e)
            }
            if (!mediaFile.delete())
                throw SecurityException("can't delete file")
            scanFile(context, mediaFile.absolutePath)
            return
        }
        // https://github.com/d4rken-org/sdmaid/issues/312#issuecomment-191460988
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mediaFile.delete()) {
            context.checkGrantSelfUriPermission(
                ContentUris.removeId(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            try {
                // ContentResolver.delete() is required for (abstract) playlists to delete properly
                if (context.contentResolver.delete(uri, null, null) == 1)
                    return
                throw IllegalArgumentException("nothing was deleted: $uri")
            } catch (e: Exception) {
                Log.w(TAG, "failed to tell mediastore to delete file", e)
            }
            scanFile(context, mediaFile.absolutePath)
            return
        }
        var failed: Throwable? = null
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && (isManager ?:
            hasWriteExternalStorage(context)) && canMediaProviderAccessSd(context, volume)) {
            val valuesBackup = context.contentResolver.query(uri, null,
                null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst())
                    throw IllegalArgumentException("can't query $uri")
                cursor.rowToContentValues()
            }
            var ok = true
            val exists = mediaFile.exists()
            if (exists && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                try {
                    // now ensure both we and MediaProvider have required permissions
                    context.contentResolver.openFileDescriptor(uri, "wa")!!.close()
                } catch (t: Throwable) {
                    if (!t.message!!.contains("EISDIR (Is a directory)")) {
                        Log.e(TAG, "failed to write (for delete)", t)
                        ok = false
                        failed = t
                    }
                }
            }
            var fileUri: Uri? = null
            if (mediaType == MEDIA_TYPE_PLAYLIST && exists
                && !isDeletionAllowedUsingSqlHook(mediaFile.path)) {
                try {
                    // ContentResolver.delete() is required for (abstract) playlists to delete properly
                    if (context.contentResolver.delete(uri, null, null) != 1)
                        throw IllegalArgumentException("nothing was deleted: $uri")
                    if (!mediaFile.exists())
                        throw IllegalStateException("isDeletionAllowedUsingSqlHook() was wrong")
                } catch (e: Exception) {
                    Log.w(TAG, "failed to tell mediastore to delete file", e)
                }
                val baseUriForFile = getBaseUriForMediaType(
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
                        volume.mediaStoreVolumeName else null, MEDIA_TYPE_NONE)
                context.checkGrantSelfUriPermission(
                    baseUriForFile,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                fileUri = context.contentResolver.insert(FILES_EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Files.FileColumns.DATA, mediaFile.path)
                        put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                            MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
                    })
                if (fileUri == null) {
                    failed = IllegalStateException("The old entry was deleted and a new one " +
                            "couldn't be added, so the file can't be deleted")
                    Log.e(TAG, "error adding file to delete", failed)
                    ok = false
                } else {
                    if (context.contentResolver.update(fileUri, ContentValues().apply {
                        // Media type can only be changed by update(), not by insert()
                        put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
                    }, null, null) != 1)
                        Log.e(TAG, "failed to change media type to image")
                }
            }
            if (ok) {
                val hasUnsupportedType = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
                    !isMediaTypeForQ(mediaType!!) && !isDownload!!
                else mediaType != MEDIA_TYPE_IMAGE && mediaType != MEDIA_TYPE_VIDEO &&
                        mediaType != MEDIA_TYPE_PLAYLIST
                if (hasUnsupportedType) {
                    // We need to change media type to image first, as generic files that aren't
                    // downloads don't have deletion hook in Q or earlier.
                    // Ignore the result, this just increases our chances this will work
                    val baseUriForFile = getBaseUriForMediaType(
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
                            volume.mediaStoreVolumeName else null, MEDIA_TYPE_NONE)
                    context.checkGrantSelfUriPermission(
                        baseUriForFile,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    fileUri = ContentUris.withAppendedId(baseUriForFile,
                        ContentUris.parseId(uri))
                    try {
                        val cnt = context.contentResolver.update(
                            fileUri,
                            ContentValues().apply {
                                put(
                                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                                )
                            }, null, null
                        )
                        if (cnt == 0) {
                            Log.w(TAG, "failed to update $fileUri, 0 rows changed")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "failed to change image type", t)
                    }
                }
                // always run this even if not required for determining mustWork, as it's important
                // for legacy platform to allow SD access
                val granted = context.checkGrantSelfUriPermission(
                    ContentUris.removeId(fileUri ?: uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                val mustWork = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted
                        || isOwned(context, ownerPackageName!!) || isMediaTypeForQ(mediaType!!)
                        && (isManager ?: hasWriteExternalStorage(context))
                try {
                    // Folders can also be deleted if we pretend they are an image.
                    if (context.contentResolver.delete(fileUri ?: uri, null,
                            null) == 1) {
                        if (!mediaFile.exists())
                            return
                        // Shouldn't happen because we verified that 1. MediaProvider has write
                        // permission 2. media type is image/video 3. we have write permission.
                        // But let's recover if there's something we forgot to account for.
                        Log.e(TAG, "failed to delete $fileUri physical copy")
                        try {
                            // Restore original data to avoid persisting wrong media type or
                            // loosing extracted music tags, which would confuse other apps.
                            if (mediaType != MEDIA_TYPE_PLAYLIST) {
                                context.contentResolver.insert(
                                    FILES_EXTERNAL_CONTENT_URI,
                                    valuesBackup
                                )
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "failed to restore row: $valuesBackup", t)
                        }
                        failed = IOException("failed to delete $uri, file did not disappear")
                        Log.e(TAG, "failed to delete", failed)
                    } else if (mustWork) {
                        try {
                            if (hasUnsupportedType && mediaType != MEDIA_TYPE_PLAYLIST) {
                                // Put the original value back to avoid confusing other apps
                                if (context.contentResolver.update(
                                        fileUri!!, ContentValues().apply {
                                            put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType)
                                        }, null, null
                                    ) != 1
                                )
                                    throw IllegalStateException("update() failed")
                            }
                        } catch (t: Throwable) {
                            Log.e(TAG, "failed to restore mediaType of $fileUri", t)
                        }
                        failed = IOException("failed to delete $uri, 0 rows changed")
                        Log.e(TAG, "failed to delete", failed)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "failed to delete", t)
                    failed = t
                }
            }
        }
        val safUri = getDocumentUriEx(context, uri, mediaFile,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                volume.mediaStoreVolumeName else null, ResolvePermissions.OnlyPersistedTree,
                forWrite = true, volumesCache = volumesCache, persistedUriPermissionsCache)
        if (safUri is Uri) {
            if (!DocumentsContract.deleteDocument(context.contentResolver,
                    safUri))
                throw IOException("failed to delete $safUri")
            try {
                // ContentResolver.delete() is required for trashed files to delete properly
                if (context.contentResolver.delete(uri, null, null) == 1)
                    return
                // It's okay if this returns == 0, this may either mean we don't have permission
                // (which is likely why we are here in the first place) or the file is already gone.
                // In both cases a scan will do no harm and at best will bring some benefit so do
                // that as well.
            } catch (_: SecurityException) {
                // we don't have permission (which is likely why we are here in the first place).
                // let's try using scan as that doesn't need permissions.
            } catch (e: Exception) {
                Log.w(TAG, "failed to tell mediastore to delete file", e)
            }
            scanFile(context, mediaFile.absolutePath)
            return
        }
        if (failed != null)
            throw IOException("failed to delete $uri", failed)
        throw SecurityException("no permission to delete $uri ($safUri)")
    }

    /**
     * Create a file.
     *
     * After creating a file with this method, you'll get a media [Uri], but on some versions,
     * that cannot be directly used for writing. Instead, use [openFileDescriptor] to write to it,
     * as that will translate to the appropriate method to write on this API. When you're done, use
     * [finishCreate] to scan the file and mark it as not-pending.
     *
     * If you're not sure about [mimeType], set `null` to let the library automatically determine it.
     *
     * If the volume provided is `null`, [fileNameAndPath] must either be an absolute path pointing
     * to a mounted volume, or the device's internal storage will be used by default. If there is a
     * volume provided, [fileNameAndPath]  may either be an absolute path pointing to that volume,
     * or a relative path from the volume's root.
     *
     * `null` is returned if there is some issue that prevented creating a [MediaStore] row. In many
     * other cases of failure, this method will throw with more details on what went wrong.
     *
     * Parent folders of the new file will be automatically created if required.
     *
     * Nullable default parameters are optional. This method has so many of them to be able to
     * efficiently batch-create files with the least possible amount of repeated Binder calls.
     *
     * Throws [SecurityException] if [needRequestCreate] would return true.
     *
     * This method assumes that, on Android 11 or later, if file to be created is a media file, that
     * it will be created in an appropriate directory allowed by MediaStore on these versions.
     * Alternatively, if the file is a copy of another file in the same directory, this can be
     * allowed even if it normally would fail the folder check, by setting the [relatedUri] to the
     * media [Uri] of the file that already exists (see [MediaStore.QUERY_ARG_RELATED_URI]).
     *
     * If the mimeType is set to [DocumentsContract.Document.MIME_TYPE_DIR], an empty directory will
     * be created instead of a file.
     */
    @JvmStatic
    @JvmOverloads
    // TODO: can i do format 0x3001 trick on modern android to create files where they don't belong?
    fun create(context: Context, fileNameAndPath: String, volume: StorageVolumeCompat? = null,
               mimeType: String? = null, relatedUri: Uri? = null, isManager: Boolean? = null,
               volumesCache: List<StorageVolumeCompat>? = null,
               persistedUriPermissionsCache: List<UriPermission>? = null): Uri? {
        var fileIn = File(fileNameAndPath)
        var volumesCache = volumesCache
        var volume = volume
        if (fileIn.isAbsolute) {
            if (volume != null) {
                fileIn = fileIn.relativeTo(volume.requireCanonicalDirectory())
                if (fileIn.path.startsWith("../")) {
                    throw IllegalArgumentException("$fileIn not inside $volume")
                }
            } else {
                volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
                volume = StorageManagerCompat.getVolumeForPath(volumesCache, fileIn)
                fileIn = fileIn.relativeTo(volume.requireCanonicalDirectory())
            }
        } else if (volume == null) {
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            volume = volumesCache.find { it.isPrimary || it.isEmulated }
                ?: throw IllegalStateException("Internal storage appears to be unavailable at this moment")
        }
        var (fileRelative, mimeTypeReal) = computeFileAndMime(fileIn, mimeType)
        val folderName = fileRelative.path.split('/', limit = 2)[0]
        @SuppressLint("NewApi") // folders array is fine to use in this isolated case
        // audio/3gpp <-> video/3gpp is the only case where one file extension (.3gpp) can map to
        // multiple media types, special case it for simplicity.
        if (mimeTypeReal == "audio/3gpp" && !folders[MEDIA_TYPE_AUDIO]!!.contains(folderName) &&
            folders[MEDIA_TYPE_VIDEO]!!.contains(folderName)) {
            mimeTypeReal = "video/3gpp"
        } else if (mimeTypeReal == "video/3gpp" && !folders[MEDIA_TYPE_VIDEO]!!
                .contains(folderName) && folders[MEDIA_TYPE_AUDIO]!!.contains(folderName)) {
            mimeTypeReal = "audio/3gpp"
        }
        val mediaType = if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
            getMediaTypeForMime(mimeTypeReal)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            folders.entries.find { it.value.contains(folderName) }?.key ?: MEDIA_TYPE_NONE
        else MEDIA_TYPE_NONE
        var wrongMediaTypeException: Exception? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mimeType?.equals(mimeTypeReal, ignoreCase = true) == false) {
            if (getMediaTypeForMime(mimeType) != mediaType) {
                wrongMediaTypeException = IllegalArgumentException(
                    "Sorry, the file ${fileRelative.name} can't be created with the MIME type" +
                            " $mimeType because Android doesn't recognize this MIME type," +
                            " and an attempt to use the supported MIME type" +
                            " $mimeTypeReal failed because the top-level folder " +
                            fileRelative.path.split('/', limit = 2)[0] + " is not " +
                            "allowed with $mimeTypeReal. Possible folders (with " +
                            "$mimeTypeReal) are ${getOkFolders(mediaType)}."
                )
            }
        }
        val path = volume.requireCanonicalDirectory().resolve(fileRelative)
        if (path.exists()) {
            // Do a good deed
            try {
                getMediaUriForFile(context, path.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "failed to scan file", e)
            }
            throw IOException("File exists: $path")
        }
        // If there's another dead column with this path, insert will fail spectacularly
        // So, delete it. (But only if the file doesn't exist, or we might cause data loss.)
        // Potential data loss is also why abstract playlists are excluded on the versions where
        // they exist.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.delete(FILES_EXTERNAL_CONTENT_URI, Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.Files.FileColumns.DATA} = ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(
                        path.absolutePath
                    ))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                })
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(
                    @Suppress("deprecation")
                    MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI),
                    "${MediaStore.Files.FileColumns.DATA} = ? AND" +
                            " ${MediaStore.Files.FileColumns.MEDIA_TYPE} != $MEDIA_TYPE_PLAYLIST",
                    arrayOf(path.absolutePath)
                )
            } else {
                context.contentResolver.delete(
                    FILES_EXTERNAL_CONTENT_URI,
                    "${MediaStore.Files.FileColumns.DATA} = ? AND" +
                            " ${MediaStore.Files.FileColumns.MEDIA_TYPE} != $MEDIA_TYPE_PLAYLIST",
                    arrayOf(path.absolutePath)
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to delete duplicates", t)
        }
        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.contentResolver.query(
                    FILES_EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.Files.FileColumns.DATA} = ?")
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                            arrayOf(path.absolutePath))
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                    },
                    null
                )
            } else {
                context.contentResolver.query(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        @Suppress("deprecation")
                        MediaStore.setIncludePending(FILES_EXTERNAL_CONTENT_URI)
                    else FILES_EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Files.FileColumns._ID),
                    "${MediaStore.Files.FileColumns.DATA} = ?",
                    arrayOf(path.absolutePath),
                    null
                )
            }).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    throw IllegalArgumentException("There is a conflicting row which could not be " +
                            "automatically cleaned up which would cause create to fail: " +
                            ContentUris.withAppendedId(FILES_EXTERNAL_CONTENT_URI,
                                cursor.getLong(cursor.getColumnIndexOrThrow(
                                    MediaStore.Files.FileColumns._ID))))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                // We can create a directory everywhere, except in the top-level if it's non-default
                if (!path.mkdirs() && !path.exists()) {
                    if (folders.values.find { it.contains(folderName) } == null) {
                        throw SecurityException("Creating a non-default top level folder requires" +
                                " MANAGE_EXTERNAL_STORAGE: $path. The default folders are: " +
                                "${folders.values.flatten()}")
                    }
                    throw IllegalStateException("Failed to create folder $path")
                }
                return getBaseUriForMediaType(volume.mediaStoreVolumeName, mediaType,
                    folderName == Environment.DIRECTORY_DOWNLOADS)
            }
            val needNoneWorkaround = !canInsertIntoNoneWithRealMime(mediaType) &&
                    !(isManager ?: isManager(context)) && (folderName.equals(
                Environment.DIRECTORY_DOWNLOADS, ignoreCase = true) || folderName
                    .equals(Environment.DIRECTORY_DOCUMENTS, ignoreCase = true))
            // forceMove uses a bug in FUSE implementation to create file in non-default top level
            val forceMove = folders.values.find { it.contains(folderName) } == null &&
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.R && folderName == "Recordings" &&
                    !(isManager ?: isManager(context))
            // Regarding the playlist check: the Audio.Playlists Uris as a whole are deprecated, and
            // there is indeed a fully featured alternative available through the Files Uris. The
            // issue is that create*Request and markIsFavoriteStatus don't accept them, but that is
            // no issue for files we create ourselves, so we can do this just fine. Sadly, because
            // there's no way to gain access to the equivalent Files Uri even if we have access to
            // the Audio.Playlists Uri, we have to keep support for writing to the Audio.Playlists
            // variant everywhere.
            // TODO: file AOSP bug regarding markIsFavoriteStatus not accepting the Files Uris
            val outMediaType = if (forceMove || folders[MEDIA_TYPE_NONE]!!.contains(folderName) &&
                folders[mediaType]?.contains(folderName) == false &&
                folderName != Environment.DIRECTORY_DOWNLOADS &&
                canInsertIntoNoneWithRealMime(mediaType) || mediaType == MEDIA_TYPE_PLAYLIST)
                MEDIA_TYPE_NONE else mediaType
            val insPath = if (forceMove) File("Downloads/${System.currentTimeMillis()}" +
                    "_${fileRelative.name}") else if (needNoneWorkaround) File("Movies/"
                    + "${System.currentTimeMillis()}_${fileRelative.name}") else fileRelative
            // Creates all parent folders for us if needed. Nice and simple as usual :)
            // (It will throw if the folder is invalid)
            val ret = try {
                context.contentResolver.insert(
                    getBaseUriForMediaType(
                        volume.mediaStoreVolumeName, outMediaType,
                        folderName == Environment.DIRECTORY_DOWNLOADS
                    ),
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, insPath.parent)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, insPath.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeReal)
                        if (!forceMove && !needNoneWorkaround) { // Those will set pending in a sec
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                    }, Bundle().apply {
                        if (relatedUri != null) {
                            putParcelable(MediaStore.QUERY_ARG_RELATED_URI, relatedUri)
                        }
                    })
            } catch (e: Exception) {
                if (wrongMediaTypeException != null)
                    e.addSuppressed(wrongMediaTypeException)
                throw e
            }
            if (forceMove || needNoneWorkaround) {
                openOutputStream(context, ret!!)!!.close()
                val insPathAbs = volume.requireCanonicalDirectory().resolve(insPath)
                path.parentFile?.mkdirs()
                val pendingFile = path.resolveSibling(".pending-${System
                    .currentTimeMillis() / 1000L + 30 * 24 * 60 * 60}-${path.name}")
                try {
                    Files.move(insPathAbs.toPath(), pendingFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE)
                } catch (e: Exception) {
                    insPathAbs.delete()
                    if (needNoneWorkaround) {
                        throw SecurityException("Working around bug 166057832 failed", e)
                    }
                    throw SecurityException("Creating a file in the non-default top level folder " +
                                "$folderName unexpectedly requires MANAGE_EXTERNAL_STORAGE " +
                                "permission. The default folders are: ${folders.values.flatten()}",
                        e)
                }
                return ret
            }
            return ret
        }
        val folder = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            path else path.parentFile!!
        val newPath = if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) null else path
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val okFolders = getOkFolders(mediaType)
            if (okFolders.contains(folderName) || folderName == Environment.DIRECTORY_DOWNLOADS) {
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    // Create a folder using new-style insert, creating and deleting a dummy file.
                    val pathFile = File(fileRelative, ".dummy_${System.currentTimeMillis()}")
                    val mediaType = if (folders[MEDIA_TYPE_NONE]!!.contains(folderName) &&
                        canInsertIntoNoneWithRealMime(mediaType)) MEDIA_TYPE_NONE else mediaType
                    val baseUri = getBaseUriForMediaType(volume.mediaStoreVolumeName, mediaType,
                        folderName == Environment.DIRECTORY_DOWNLOADS)
                    context.checkGrantSelfUriPermission(baseUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    val uri = context.contentResolver.insert(
                        baseUri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, pathFile.parent)
                            put(MediaStore.MediaColumns.DISPLAY_NAME, pathFile.name)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                            if (mediaType == MEDIA_TYPE_PLAYLIST) {
                                if (!hasWriteExternalStorage(context))
                                    throw SecurityException("Sorry, creating a playlist needs " +
                                            "storage permission due to multiple bugs in Android 10")
                                put(MediaStore.MediaColumns.DATA, volume.requireCanonicalDirectory()
                                    .resolve(pathFile).path)
                            }
                        })
                    if (uri != null)
                        context.contentResolver.delete(uri, null, null)
                    return baseUri
                }
                // New-style typed insert. Creates all parent folders for us if needed.
                val outMediaType = if (folders[MEDIA_TYPE_NONE]!!.contains(folderName) &&
                    folders[mediaType]?.contains(folderName) == false &&
                    folderName != Environment.DIRECTORY_DOWNLOADS &&
                    canInsertIntoNoneWithRealMime(mediaType)) MEDIA_TYPE_NONE else mediaType
                val baseUri = getBaseUriForMediaType(volume.mediaStoreVolumeName, outMediaType,
                    folderName == Environment.DIRECTORY_DOWNLOADS)
                context.checkGrantSelfUriPermission(baseUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                val uri = context.contentResolver.insert(
                    baseUri, ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, fileRelative.parent)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileRelative.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeReal)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (mediaType == MEDIA_TYPE_PLAYLIST) {
                            if (!hasWriteExternalStorage(context))
                                throw SecurityException("Sorry, creating a playlist needs " +
                                        "storage permission due to multiple bugs in Android 10")
                            put(MediaStore.MediaColumns.DATA, path.path)
                        }
                    })
                if (uri != null && (outMediaType != mediaType ||
                            folderName == Environment.DIRECTORY_DOWNLOADS) &&
                    mediaType != MEDIA_TYPE_SUBTITLE) {
                    context.contentResolver.update(ContentUris.withAppendedId(
                        FILES_EXTERNAL_CONTENT_URI,
                        ContentUris.parseId(uri)), ContentValues().apply {
                        // Now this actually gets applied
                        put(MediaStore.Files.FileColumns.MEDIA_TYPE, mediaType)
                    }, null, null)
                }
                return uri
            }
            if (!(isManager ?: hasWriteExternalStorage(context)))
                throw SecurityException(
                    "WRITE_EXTERNAL_STORAGE has to be granted for Android 10 when not " +
                            "following Q's restricted folder rules: folder $folderName not " +
                            "allowed, allowed folders are " + okFolders)
            ok = folder.exists()
            // this code would also work on primary, but why bother if we have storage permission,
            // and it's external (as opposed to secondary) storage? we can just use File.mkdirs() :)
            if (!volume.isPrimary && !ok) {
                // This is used for inserting files into a folder that doesn't match the media type
                // of the file we want to insert, such as lyrics (type none in Q) files into Music
                // folder, or playlist files into
                // MediaStore will NOT create all parent folders for us if needed, thus can't use it
                // if we need a subfolder created that doesn't exist
                val resolvedMediaType = folders.entries.find { it.value.contains(folderName) }?.key
                if (resolvedMediaType != null || folderName == Environment.DIRECTORY_DOWNLOADS) {
                    // Creates all parent folders by creating a dummy with correct media type
                    // for this folder, then deleting the dummy again.
                    val baseUri = getBaseUriForMediaType(volume.mediaStoreVolumeName,
                        resolvedMediaType ?: MEDIA_TYPE_NONE,
                        folderName == Environment.DIRECTORY_DOWNLOADS)
                    context.checkGrantSelfUriPermission(baseUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    val tempUri = context.contentResolver.insert(baseUri,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, fileRelative.parent)
                            put(MediaStore.MediaColumns.DISPLAY_NAME, ".temp_deleteMePlease_" +
                                    System.currentTimeMillis())
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                            if (resolvedMediaType == MEDIA_TYPE_PLAYLIST) {
                                if (!hasWriteExternalStorage(context))
                                    throw SecurityException("Sorry, creating a playlist needs " +
                                            "storage permission due to multiple bugs in Android 10")
                                put(MediaStore.MediaColumns.DATA, volume.requireCanonicalDirectory()
                                    .resolve(fileRelative).resolveSibling(getAsString(
                                    MediaStore.MediaColumns.DISPLAY_NAME)).path)
                            }
                        })
                    if (tempUri != null) {
                        ok = context.contentResolver.delete(tempUri, null,
                            null) == 1
                    } else {
                        // MediaProvider crashed, path ended with trailing / or file exists
                        Log.e(TAG, "got null unexpectedly after insert() of dummy in " +
                                "${fileRelative.parent}")
                    }
                } // else: have to use SAF
            }
        } else {
            if (!(isManager ?: hasWriteExternalStorage(context)))
                throw SecurityException("WRITE_EXTERNAL_STORAGE has to be granted for Android" +
                            " 9 and earlier to create files")
        }
        if (ok) {
            // empty
        } else if (volume.isPrimary || volume.requireCanonicalDirectory().canWrite()) {
            // canWrite: https://github.com/d4rken-org/sdmaid/issues/312#issuecomment-191460988
            if (!folder.exists() && !folder.mkdirs()) {
                throw IllegalStateException("Failed to mkdirs() folders")
            }
        } else {
            val safUri = getDocumentUriEx(
                context, null, folder,
                null, ResolvePermissions.OnlyPersistedTree,
                forWrite = true, volumesCache, persistedUriPermissionsCache
            )
            if (safUri is Uri) {
                mkdirsSaf(
                    context.contentResolver, safUri,
                StorageManagerCompat.getExternalStoragePath(
                        DocumentsContract.getDocumentId(safUri)
                    )
                )
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                canMediaProviderAccessSd(context, volume)) {
                context.checkGrantSelfUriPermission(
                    FILES_EXTERNAL_CONTENT_URI,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                if (!folder.exists()) {
                    mediaStoreMkdirs(context, volume, folder)
                }
            } else {
                throw SecurityException("no permission to create $newPath")
            }
        }
        if (newPath == null) {
            // when this method is used for creating empty folder, this means success
            return FILES_EXTERNAL_CONTENT_URI
        }
        val baseUri = getBaseUriForMediaType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) volume.mediaStoreVolumeName
            else null, mediaType)
        context.checkGrantSelfUriPermission(baseUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        // This doesn't create the file yet, that is done in openFileDescriptor()
        return context.contentResolver.insert(baseUri, ContentValues().apply {
                put(MediaStore.Files.FileColumns.DATA, newPath.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Files.FileColumns.IS_PENDING, 1)
                }
                put(MediaStore.Files.FileColumns.MIME_TYPE, mimeTypeReal)
            })
    }

    // Assumes newPath does not exist, version is P or earlier, canMediaProviderAccessSd is true,
    // storage permission is granted, and Uri permission for FILES_EXTERNAL_CONTENT_URI is granted.
    private fun mediaStoreMkdirs(context: Context, volume: StorageVolumeCompat, newPath: File) {
        // This is a weird, hacky way to create folders with MediaProvider
        val appPath = (@Suppress("deprecation") context.externalMediaDirs).find {
            it?.let { StorageManagerCompat.isVolumeForPath(volume, it) } == true
        }
        if (appPath == null) {
            throw IllegalArgumentException("Unsupported volume: $volume, " +
                    "perhaps invisible volume?")
        }
        // Note: path mustn't contain "/."/have ".nomedia"
        val spoofFolder = appPath.resolve("MediaStoreCompat_" +
                "tmpDir_${System.currentTimeMillis()}")
        spoofFolder.mkdirs()
        try {
            val spoofFile = spoofFolder.resolve("albumart.jpg")
            val fake = createBitmap(1, 1)
            spoofFile.outputStream().use {
                fake.compress(Bitmap.CompressFormat.JPEG, 10, it)
            }
            val spoofMp3 = spoofFolder.resolve("dummy.mp3")
            // spoofMp3 does have to exist, but it may be empty.
            spoofMp3.outputStream().close()
            // 0 never exists on a normal Android system, so we can use it for trickery.
            val dummyId = 0
            val dummyUri = context.contentResolver.insert(
                FILES_EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DATA, spoofMp3.path)
                    // Inserting into Audio.Media Uri would mean MediaStore "helpfully"
                    // creates or adds it into artist, album, genre, etc. tables. Using
                    // files table with media type instead allows us to avoid that
                    // entirely, also avoid async thumbnail generation for new albums as
                    // side effect. Finally, it allows us to overwrite the album ID
                    // column to point to albums that do not exist, even with ID 0 to
                    // avoid conflicting with real albums.
                    put(
                        MediaStore.Files.FileColumns.MEDIA_TYPE,
                        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                    )
                    // these versions don't check projections yet so we can write whatever
                    // we wish to write
                    put("album_id", dummyId)
                })!!
            val dummyArt = newPath.resolve("._deleteMeTmp_${System.currentTimeMillis()}")
            // album_art table is reached using this private Uri
            val albumArtTableUri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(MediaStore.AUTHORITY).appendPath(VOLUME_EXTERNAL)
                .appendPath("audio").appendPath("albumart").build()
            context.checkGrantSelfUriPermission(
                albumArtTableUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            val albumArtUri2 = albumArtTableUri.buildUpon().appendPath(
                dummyId.toULong().toString()
            ).build()
            try {
                context.contentResolver.delete(albumArtUri2, null, null)
            } catch (t: Throwable) {
                Log.e(TAG, "delete() of potential duplicate failed", t)
            }
            // It will it be null because the album ID is 0.
            val artUri = context.contentResolver.insert(
                albumArtTableUri,
                ContentValues().apply {
                    put(MediaStore.Audio.AudioColumns.DATA, dummyArt.path)
                    put(MediaStore.Audio.AudioColumns.ALBUM_ID, dummyId)
                })
            if (artUri != null) {
                throw IllegalStateException("expected null return value but got $artUri")
            }
            val fd = try {
                // must be read-only mode for generation to trigger
                context.contentResolver.openFileDescriptor(albumArtUri2, "r")
                    .also { it?.close() }
            } catch (t: Throwable) {
                throw IllegalStateException("Failed to mkdir folder", t)
            } finally {
                // Now that we created the parent folder of the dummy art, and the dummy
                // art itself, get rid of the dummy art again.
                try {
                    context.contentResolver.delete(
                        albumArtUri2,
                        null, null
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "delete() failed", t) // ???
                }
                // And get rid of the song that doesn't exist
                try {
                    context.contentResolver.delete(
                        dummyUri,
                        null, null
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "delete() failed", t) // ???
                }
                if (!isDeletionAllowedUsingSqlHook(dummyArt.path)) {
                    // Well thanks for nothing MediaProvider...
                    context.checkGrantSelfUriPermission(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                    // we have to use images table because the file is hidden/nomedia
                    // and mediaType in files table is only applied if it's not hidden
                    try {
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.Images.ImageColumns.DATA, dummyArt.path)
                            }
                        )!!
                        context.contentResolver.delete(
                            uri, null, null)
                    } catch (t: Throwable) {
                        Log.e(TAG, "insert()/delete() in fallback failed", t)
                    }
                }
            }
            if (fd == null) {
                throw IllegalStateException("Failed to mkdir folder: null returned")
            }
        } finally {
            spoofFolder.deleteRecursively()
        }
        if (!newPath.exists()) {
            throw IllegalStateException("Sanity check failed, mkdir failed silently")
        }
        // Folder was created successfully :)
    }

    private fun isDeletionAllowedUsingSqlHook(path: String): Boolean {
        // https://cs.android.com/android/platform/superproject/main/+/main:external/sqlite/android/sqlite3_android.cpp;drc=61197364367c9e404c7da6900658f1b16c42d0da;l=169?q=_DELETE_FILE%20sqlite
        val externalStorage = Os.getenv("EXTERNAL_STORAGE")
        if (!externalStorage.isNullOrEmpty() && path.startsWith(externalStorage))
            return true
        val secondaryStorage = Os.getenv("SECONDARY_STORAGE") ?: return false
        return secondaryStorage.split(':').find { path.startsWith(it) } != null
    }

    private fun createDocumentCompat(content: ContentResolver, parentDocumentUri: Uri,
                                     directory: Boolean, displayName: String): Uri? {
        // mimeType doesn't matter as underlying FS doesn't support mime type anyway, and it also
        // isn't passed to MediaStore db. All we need to do is satisfy the check for consistency
        // check between file extension and mime type. So do it exactly like Android OS does it to
        // ensure we always have acceptable mime type.
        val mimeType = if (directory) DocumentsContract.Document.MIME_TYPE_DIR else {
            val lastDot = displayName.lastIndexOf('.')
            (if (lastDot >= 0) {
                val ext = displayName.substring(lastDot + 1)
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            } else null) ?: MIMETYPE_UNKNOWN
        }
        try {
            return DocumentsContract.createDocument(
                content, parentDocumentUri, mimeType, displayName
            )
        } catch (e: IllegalArgumentException) {
            // ref https://stackoverflow.com/a/66336273
            if (Build.BRAND.equals("huawei", ignoreCase = true) ||
                Build.BRAND.equals("honor", ignoreCase = true)) {
                try {
                    val documentId = DocumentsContract.getDocumentId(parentDocumentUri)
                    val newUri = DocumentsContract.buildDocumentUriUsingTree(
                        parentDocumentUri,
                        StorageManagerCompat.buildExternalStorageDocumentId(
                            StorageManagerCompat.getExternalStorageVolumeName(documentId),
                            File(StorageManagerCompat.getExternalStoragePath(documentId))
                                .resolve(displayName).path
                        )
                    )
                    content.query(newUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                        null, null, null).use {
                            if (it != null && it.moveToFirst())
                                return newUri
                    }
                } catch (t: Throwable) {
                    e.addSuppressed(t)
                }
            }
            throw e
        }
    }

    private fun mkdirsSaf(content: ContentResolver, treeUri: Uri, folderPathFromVolume: String) {
        val folderPathFromVolume = folderPathFromVolume.trimEnd('/')
        val treeId = DocumentsContract.getTreeDocumentId(treeUri)
        val treeVolume = StorageManagerCompat.getExternalStorageVolumeName(treeId)
        val treePath = File(StorageManagerCompat.getExternalStoragePath(
            treeId).let { if (it.startsWith("./")) it.substring(2)
        else if (it == ".") "" else it })
        var first = File(folderPathFromVolume).relativeTo(treePath)
        root@while (true) {
            // We can check existence of two folders at once with one Binder call :)
            if (first.path.isEmpty()) {
                // ... except if we can't because there is only one folder left to check
                val treeItselfUri = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                    StorageManagerCompat.buildExternalStorageDocumentId(
                        treeVolume, treePath.path))
                try {
                    content.query(treeItselfUri, arrayOf(
                        DocumentsContract.Document.COLUMN_MIME_TYPE),null,
                        null, null).use {
                        if (it != null && it.moveToFirst()) {
                            val mime = it.getString(
                                it.getColumnIndexOrThrow(
                                    DocumentsContract.Document.COLUMN_MIME_TYPE
                                )
                            )
                            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                                // Our tree is not a directory? Seriously?
                                // (this can only happen if it used to be but someone deleted it and
                                // then made a file with the same name as the former directory)
                                throw IllegalArgumentException(
                                    "Tree is not a directory, instead $mime: $treeItselfUri"
                                )
                            }
                            break
                        }
                    }
                    throw IllegalArgumentException("Tree doesn't exist (0 results): $treeItselfUri")
                } catch (t: FileNotFoundException) {
                    throw IllegalArgumentException("Tree doesn't exist: $treeItselfUri", t)
                }
            }
            val parent = first.parent?.let { treePath.resolve(it) } ?: treePath
            val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                StorageManagerCompat.buildExternalStorageDocumentId(
                    treeVolume, parent.path))
            val targetId = StorageManagerCompat.buildExternalStorageDocumentId(
                treeVolume, treePath.resolve(first).path)
            try {
                // Note: FileSystemProvider doesn't support selections, we have to do it on our side
                content.query(parentUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null
                ).use {
                    if (it != null && it.moveToFirst()) {
                        val documentIdColumn = it.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        do {
                            if (it.getString(documentIdColumn) != targetId)
                                continue
                            val mime = it.getString(
                                it.getColumnIndexOrThrow(
                                    DocumentsContract.Document.COLUMN_MIME_TYPE
                                )
                            )
                            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                                // This is important if this is the topmost folder we are asked to
                                // create, as then the caller may believe it actually exists after we
                                // return. We instead throw. Also do this throw instead of relying on
                                // createDocument() to throw simply because we already have this info
                                // and there's no point in not throwing.
                                throw IllegalArgumentException(
                                    "Not a directory, instead $mime: $parentUri child ${first.name}"
                                )
                            }
                            break@root
                        } while (it.moveToNext())
                    }
                }
                first = first.parentFile ?: File("")
                // parent of the one we wanted to check does exist, but we don't know if it's a dir
                // but that doesn't matter as we will create a child in this method and that process
                // will throw if the parent is indeed not a directory
                break
            } catch (t: FileNotFoundException) {
                // If the parent doesn't exist, but it's the tree Uri, throw the error from SAF
                first = first.parentFile ?: throw IllegalArgumentException(
                    "Tree doesn't exist: $parentUri", t)
                // Otherwise we know the parent of the folder we want to make has to be created too
                first = first.parentFile ?: File("")
            }
        }
        // Now we have something which does exist, the folder "first". We have to create every
        // subfolder one by one.
        val pathToCreate = File(folderPathFromVolume).relativeTo(treePath)
            .relativeTo(first).invariantSeparatorsPath
        // It turns out the folder that exists is the folder we are tasked to create
        if (pathToCreate.isEmpty())
            return
        pathToCreate.split('/').forEach {
            val parent = first.parent?.let { p -> treePath.resolve(p) } ?: treePath
            val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                StorageManagerCompat.buildExternalStorageDocumentId(
                    treeVolume, parent.path))
            createDocumentCompat(content, parentUri, true,
                it) ?: throw IllegalStateException("Failed to create folder: " +
                    "$parentUri child $it")
            first = first.resolve(it)
        }
    }

    /**
     * @see create
     */
    @JvmStatic
    @JvmOverloads
    fun finishCreate(context: Context, uri: Uri, mediaFile: File? = null,
                     volumesCache: List<StorageVolumeCompat>? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (uri.authority?.equals(MediaStore.AUTHORITY) == false)
                throw IllegalArgumentException("Expected a MediaStore uri: $uri")
            var mimeType: String? = null
            if (isAffectedByPlaylistMimeReset() &&
                guessMediaTypeFromUri(uri) == MEDIA_TYPE_PLAYLIST) {
                mimeType = context.contentResolver.query(uri,
                    arrayOf(MediaStore.MediaColumns.MIME_TYPE), null,
                    null).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst())
                        throw IllegalArgumentException("Can't resolve media Uri: $uri")
                    cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.MIME_TYPE))
                }
            }
            val uri = if (isAffectedByMoveGenericVolumeBug() &&
                MediaStore.getVolumeName(uri) == MediaStore.VOLUME_EXTERNAL) {
                // https://issuetracker.google.com/issues/350540990
                val volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
                val volume =
                    StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
                if (volume.mediaStoreVolumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                    (MediaStore.AUTHORITY_URI.buildUpon()
                        .appendPath(volume.mediaStoreVolumeName).build().toString() + uri
                        .toString().substring(
                            MediaStore.AUTHORITY_URI.buildUpon()
                                .appendPath(MediaStore.getVolumeName(uri)).build().toString()
                                .length
                        )).toUri()
                } else uri
            } else uri
            if (context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                    if (mimeType != null) {
                        // Playlists being moved will reset their MIME type to M3U, so you have to
                        // specify correct MIME type every time to avoid getting .m3u suffix added.
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    }
                }, null, null) != 1)
                throw IllegalStateException("update() failed")
            return
        }
        scanFile(context, uri, mediaFile)
        return
    }

    private fun needRequestSqlUpdateInternal(
        context: Context, mediaUri: Uri, isManager: Boolean? = null,
        ownerPackageName: String? = null, mediaType: Int? = null, isDownload: Boolean? = null,
        mediaFile: File? = null, volumesCache: List<StorageVolumeCompat>? = null
    ): Boolean {
        return getWritePermissionInternal(context, mediaUri, false,
            isManager, PERMISSION_UPDATE_SQL, ownerPackageName, mediaType,
            isDownload, mediaFile, volumesCache, null) != null
    }

    /** @see needRequestSqlUpdate */
    enum class UpdateMode {
        /**
         * A single file is updated with the [Uri] obtained from [getBaseUriForMediaType] combined
         * with the ID using [ContentUris.withAppendedId].
         */
        Single,
        /**
         * Multiple files are updated using a single well-defined (audio, images, videos or
         * playlists) collection appropriate for the file type (such as
         * [MediaStore.Audio.Media.EXTERNAL_CONTENT_URI]).
         */
        WellDefinedBatch,
        /**
         * Multiple files are updated using the [MediaStore.Files] collection.
         */
        FilesBatch,
    }

    /**
     * Returns whether there needs to be a permission request created with [createWriteRequest]
     * before [ContentResolver.update] can be called on this media [Uri]. If the return value is
     * null, [ContentResolver.update] can be called immediately, otherwise a [RequestToken]
     * generated has to be passed to [createWriteRequest] to gain authorization first.
     *
     * Important: please note the documentation of [UpdateMode.Single] and the mention of how the
     * [Uri] has to be constructed. Even if this function returns that access is granted, you may
     * not be able to use the [Uri] passed in and instead have to construct the [Uri] manually.
     *
     * Nullable parameters are optional. This method has so many of them to be able to query for
     * write permissions for a batch of [Uri]s with the least possible amount of repeated Binder
     * calls.
     *
     * Note that, on Android 11 and later, requesting a batch [updateMode] for a file that does
     * not already have it requires [Manifest.permission.MANAGE_EXTERNAL_STORAGE] to be declared in
     * manifest.
     *
     * Note that this method will throw on certain customized Android Q (only) systems if
     * [updateMode] is [UpdateMode.FilesBatch] and the files are on SD card and storage permission
     * is granted, as the batch update permission is then impossible to obtain. For this specific
     * case only, it is required to check if this method throws after [createWriteRequest] returned
     * [android.app.Activity.RESULT_OK] (as it may return OK due to granting storage permission) and
     * adjust strategy accordingly if it does throw.
     *
     * [isManager] means [Environment.isExternalStorageManager] since R and grant state of
     * [Manifest.permission.WRITE_EXTERNAL_STORAGE] on Q and earlier.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @JvmStatic
    @JvmOverloads
    fun needRequestSqlUpdate(context: Context, mediaUri: Uri, updateMode: UpdateMode,
                             ownerPackageName: String? = null, mediaType: Int? = null,
                             isDownload: Boolean? = null, mediaFile: File? = null,
                             isManager: Boolean? = null,
                             volumesCache: List<StorageVolumeCompat>? = null): RequestToken? {
        val mask = when (updateMode) {
            UpdateMode.Single -> PERMISSION_UPDATE_SQL
            UpdateMode.WellDefinedBatch -> PERMISSION_UPDATE_SQL_FROM_WELL_DEFINED_PARENT
            UpdateMode.FilesBatch -> PERMISSION_UPDATE_SQL_FROM_FILES_PARENT
        }
        return getWritePermissionInternal(context, mediaUri, true,
            isManager, mask, ownerPackageName, mediaType, isDownload, mediaFile,
            volumesCache, null)
    }

    /**
     * Returns whether there needs to be a permission request created with [createWriteRequest]
     * before [openFileDescriptor] with a write mode can be called on this media [Uri].
     *
     * Nullable arguments are optional.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @JvmStatic
    @JvmOverloads
    fun needRequestBytesWrite(context: Context, mediaUri: Uri, ownerPackageName: String? = null,
                              mediaType: Int? = null, isDownload: Boolean? = null,
                              mediaFile: File? = null, isManager: Boolean? = null,
                              volumesCache: List<StorageVolumeCompat>? = null,
                              persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        return getWritePermissionInternal(context, mediaUri, true,
            isManager, PERMISSION_OPEN_FD_FOR_WRITE, ownerPackageName, mediaType,
            isDownload, mediaFile, volumesCache, persistedUriPermissionsCache)
    }

    private fun File.toUriCompat(): Uri {
        val tmp = Uri.fromFile(this)
        return if (tmp.scheme != "file") // weird os bug workaround, found on Samsung and Xiaomi
            tmp.buildUpon().scheme("file").build()
        else tmp
    }

    /**
     * Throws [SecurityException] if [needRequestBytesWrite] returns true.
     */
    private fun <T> openBytesWriteCommon(context: Context, mediaUri: Uri, mode: String,
                                         callback: (Uri) -> T, ownerPackageName: String? = null,
                                         mediaType: Int? = null, mediaFile: File? = null,
                                         isManager: Boolean? = null,
                                         volumesCache: List<StorageVolumeCompat>? = null,
                                         persistedUriPermissionsCache: List<UriPermission>? = null): T? {
        var mediaUri = mediaUri
        var mediaType = mediaType
        var mediaFile = mediaFile
        var ownerPackageName = ownerPackageName
        if (mediaUri.authority?.equals(MediaStore.AUTHORITY) == false)
            throw IllegalArgumentException("Expected a MediaStore uri: $mediaUri")
        if (mode == "r" && Build.VERSION.SDK_INT != Build.VERSION_CODES.Q)
            return callback(mediaUri) // read always works like this except for Q playlists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!supportsWriteRequestForSidecar() && !(isManager ?: isManager(context))) {
                queryMissing(context, mediaUri, ownerPackageName, mediaType, null,
                    mediaFile, needsOwner = true, needsFile = true, needsType = true,
                    needsIsDownload = false) {
                        ownerPackageNameH, mediaTypeH, _, mediaFileH ->
                    mediaType = mediaTypeH
                    mediaFile = mediaFileH
                    ownerPackageName = ownerPackageNameH
                }
                if (!isOwned(context, ownerPackageName!!) && (mediaType == MEDIA_TYPE_SUBTITLE
                            || mediaType == MEDIA_TYPE_PLAYLIST)) {
                    val uri = getDocumentUriEx(context, mediaUri, mediaFile,
                        forWrite = true, volumesCache = volumesCache,
                        persistedUriPermissionsCache = persistedUriPermissionsCache)
                    if (uri !is Uri)
                        throw SecurityException("caller has no access to $uri")
                    return callback(uri)
                }
            }
            return callback(mediaUri)
        }
        queryMissing(context, mediaUri, ownerPackageName, mediaType, null, mediaFile,
            needsOwner = true, needsFile = true,
            needsType = Build.VERSION.SDK_INT == Build.VERSION_CODES.Q, needsIsDownload = false) {
                ownerPackageNameH, mediaTypeH, _, mediaFileH ->
            mediaType = mediaTypeH
            mediaFile = mediaFileH
            ownerPackageName = ownerPackageNameH
        }
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            if (mediaType == MEDIA_TYPE_PLAYLIST) {
                // Work around "IllegalArgumentException: Invalid column owner_package_name"
                mediaUri = ContentUris.withAppendedId(FILES_EXTERNAL_CONTENT_URI,
                    ContentUris.parseId(mediaUri))
            }
            // On Q, writing through MediaStore Uri ensures it will stay up to date as it will
            // trigger an automatic rescan. To ensure we can write if possible, try to get a grant.
            if (context.checkGrantSelfUriPermission(ContentUris.removeId(mediaUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                            or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                || isOwned(context, ownerPackageName!!) || context.checkSelfUriPermission(
                    mediaUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                return callback(mediaUri)
        }
        val volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
        val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
        if (volume.isPrimary) {
            try {
                return callback(mediaUri)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to open $mediaUri", t)
                if (!hasWriteExternalStorage(context))
                    throw t
            }
            val fileUri = mediaFile.toUriCompat()
            return callback(fileUri)
        }
        var failed: Throwable? = null
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && isMediaTypeForQ(mediaType!!)) {
            // If we don't own the file, don't have a grant either, and it's on removable storage,
            // then using the most specific media uri is required for Q.
            // if it's not the correct media type, we'll have to use SAF.
            val baseUri = getBaseUriForMediaType(MediaStore.getVolumeName(mediaUri),
                mediaType)
            // Increase our chances
            context.checkGrantSelfUriPermission(baseUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val newUri = ContentUris.withAppendedId(baseUri,
                ContentUris.parseId(mediaUri))
            return callback(newUri)
        }
        val safUri = getDocumentUriEx(context, mediaUri, mediaFile,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                volume.mediaStoreVolumeName else null, ResolvePermissions.OnlyPersistedTree,
            forWrite = true, volumesCache = volumesCache, persistedUriPermissionsCache)
        if (safUri is Uri) {
            try {
                if (!mediaFile.exists()) {
                    val docId = DocumentsContract.getDocumentId(safUri)
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(safUri,
                        StorageManagerCompat.buildExternalStorageDocumentId(
                            StorageManagerCompat.getExternalStorageVolumeName(
                                docId), File(StorageManagerCompat.
                            getExternalStoragePath(docId)).parent ?: ""
                        ))
                    createDocumentCompat(
                        context.contentResolver,
                        parentUri, false, mediaFile.name
                    ) ?: throw IllegalStateException("create failed $parentUri: ${mediaFile.name}")
                }
                return callback(safUri)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to open $mediaUri", t)
                failed = t
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            // Prior to Q, even MediaProvider might not have SD write permission!
            hasWriteExternalStorage(context) && canMediaProviderAccessSd(context, volume)) {
            // Due to a bug in these versions we need to grant ourselves access despite storage perm
            context.checkGrantSelfUriPermission(FILES_EXTERNAL_CONTENT_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val newUri = ContentUris.withAppendedId(FILES_EXTERNAL_CONTENT_URI,
                ContentUris.parseId(mediaUri))
            try {
                return callback(newUri)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to open $mediaUri", t)
                failed = t
            }
        }
        if (failed != null)
            throw IOException("failed to open $mediaUri", failed)
        throw SecurityException("no permission to open $mediaUri ($safUri)")
    }

    /**
     * Throws [SecurityException] if [needRequestBytesWrite] returns true.
     *
     * @see ContentResolver.openAssetFileDescriptor
     */
    @JvmStatic
    @JvmOverloads
    fun openAssetFileDescriptor(context: Context, mediaUri: Uri, mode: String,
                                ownerPackageName: String? = null, mediaType: Int? = null,
                                mediaFile: File? = null, isManager: Boolean? = null,
                                volumesCache: List<StorageVolumeCompat>? = null,
                                persistedUriPermissionsCache: List<UriPermission>? = null): AssetFileDescriptor? {
        return openBytesWriteCommon(context, mediaUri, mode, {
            context.contentResolver.openAssetFileDescriptor(it, mode)
        }, ownerPackageName, mediaType, mediaFile, isManager,
            volumesCache, persistedUriPermissionsCache)
    }

    /**
     * @see ContentResolver.openInputStream
     */
    @JvmStatic
    @JvmOverloads
    fun openInputStream(context: Context, mediaUri: Uri,
                         ownerPackageName: String? = null, mediaType: Int? = null,
                         mediaFile: File? = null, isManager: Boolean? = null,
                         volumesCache: List<StorageVolumeCompat>? = null,
                         persistedUriPermissionsCache: List<UriPermission>? = null): InputStream? {
        return openBytesWriteCommon(context, mediaUri, "r", {
            context.contentResolver.openInputStream(it)
        }, ownerPackageName, mediaType, mediaFile, isManager,
            volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Throws [SecurityException] if [needRequestBytesWrite] returns true.
     *
     * @see ContentResolver.openOutputStream
     */
    @JvmStatic
    @JvmOverloads
    fun openOutputStream(context: Context, mediaUri: Uri, mode: String = "w",
                         ownerPackageName: String? = null, mediaType: Int? = null,
                         mediaFile: File? = null, isManager: Boolean? = null,
                         volumesCache: List<StorageVolumeCompat>? = null,
                         persistedUriPermissionsCache: List<UriPermission>? = null): OutputStream? {
        return openBytesWriteCommon(context, mediaUri, mode, {
            context.contentResolver.openOutputStream(it, mode)
        }, ownerPackageName, mediaType, mediaFile, isManager,
            volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Throws [SecurityException] if [needRequestBytesWrite] returns true.
     *
     * @see ContentResolver.openFileDescriptor
     */
    @JvmStatic
    @JvmOverloads
    fun openFileDescriptor(context: Context, mediaUri: Uri, mode: String,
                           ownerPackageName: String? = null, mediaType: Int? = null,
                           mediaFile: File? = null, isManager: Boolean? = null,
                           volumesCache: List<StorageVolumeCompat>? = null,
                           persistedUriPermissionsCache: List<UriPermission>? = null): ParcelFileDescriptor? {
        return openBytesWriteCommon(context, mediaUri, mode, {
            context.contentResolver.openFileDescriptor(it, mode)
        }, ownerPackageName, mediaType, mediaFile, isManager,
            volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Convenience method to check whether the access required to delete this item needs to be
     * requested. If this method returns a non-null value, use [createDeleteRequest] (or
     * [createWriteRequest]) to delete the file. If it returns a null value, you can use [delete]
     * immediately.
     *
     * Nullable arguments are optional.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @JvmStatic
    @JvmOverloads
    fun needRequestDelete(context: Context, mediaUri: Uri, ownerPackageName: String? = null,
                          mediaType: Int? = null, isDownload: Boolean? = null,
                          mediaFile: File? = null, isManager: Boolean? = null,
                          volumesCache: List<StorageVolumeCompat>? = null,
                          persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        return getWritePermissionInternal(context, mediaUri, true,
            isManager, PERMISSION_DELETE, ownerPackageName, mediaType, isDownload,
            mediaFile, volumesCache, persistedUriPermissionsCache)
    }

    /**
     * Convenience method to check whether the access required to trash this item needs to be
     * requested. If this method returns a non-null token, use [createTrashRequest] to trash the
     * file, or [createWriteRequest] to gain access to trash the file using [markIsTrashedStatus].
     *
     * Nullable arguments are optional.
     *
     * Trashing is not supported before Android 11.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun needRequestTrash(context: Context, mediaUri: Uri, ownerPackageName: String? = null,
                         mediaType: Int? = null, mediaFile: File? = null, isManager: Boolean? = null,
                         volumesCache: List<StorageVolumeCompat>? = null,
                         persistedUriPermissionsCache: List<UriPermission>? = null): RequestToken? {
        var mediaFile = mediaFile
        var mediaType = mediaType
        var ownerPackageName = ownerPackageName
        var volumesCache = volumesCache
        if (!supportsWriteRequestForSidecar() && !(isManager ?: isManager(context)) &&
            (ownerPackageName == null || !isOwned(context, ownerPackageName)) &&
            (mediaType == MEDIA_TYPE_SUBTITLE || mediaType == MEDIA_TYPE_PLAYLIST
                    || mediaType == null)) {
            queryMissing(context, mediaUri, ownerPackageName, mediaType, null,
                mediaFile, needsOwner = true, needsFile = true, needsType = true,
                needsIsDownload = false) {
                    ownerPackageNameH, mediaTypeH, _, mediaFileH ->
                mediaType = mediaTypeH
                mediaFile = mediaFileH
                ownerPackageName = ownerPackageNameH
            }
            if (!isOwned(context, ownerPackageName!!) && (mediaType == MEDIA_TYPE_SUBTITLE
                        || mediaType == MEDIA_TYPE_PLAYLIST)) {
                // we can rename files to trash them using SAF
                return needRequestEfficientMove(context, mediaUri,
                    mediaFile!!.parent ?: "", ownerPackageName,
                    mediaType, null, mediaFile, false, volumesCache,
                    persistedUriPermissionsCache)
            }
        }
        val mediaUri = if (isAffectedByMoveGenericVolumeBug()) {
            if (mediaFile != null) {
                volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
                val volume = StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile)
                if (volume.mediaStoreVolumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    mediaUri
                else (MediaStore.AUTHORITY_URI.buildUpon()
                    .appendPath(volume.mediaStoreVolumeName).build().toString() +
                        mediaUri.toString().substring(
                        MediaStore.AUTHORITY_URI.buildUpon()
                            .appendPath(MediaStore.getVolumeName(mediaUri))
                            .build().toString().length
                    )).toUri()
            } else {
                val resolvedUri = resolveMediaUriVolume(context, mediaUri)
                if (MediaStore.getVolumeName(resolvedUri) != MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    resolvedUri else mediaUri
            }
        } else mediaUri
        // because SDK >=R, all other optional parameters are never used, thus don't add them here
        return getWritePermissionInternal(context, mediaUri, true,
            isManager, PERMISSION_UPDATE_SQL, ownerPackageName, mediaType,
            null, null, null, null)
    }

    /**
     * This method works around https://issuetracker.google.com/issues/350540990 and ensures every
     * Uri is a well-defined Uri (see [getBaseUriForMediaType]) as required by this API, but
     * otherwise works the same as the original one. Requires Android 11 or later.
     *
     * If [needRequestTrash] returns false for every [Uri], [ContentResolver.update] can be used
     * directly with [MediaStore.MediaColumns.IS_TRASHED] to trash items, otherwise this request
     * method has to be used.
     *
     * Performance: If there is a subtitle uris among the uris, and one of the following is true:
     * - the subtitle's uri is already on a specific volume, or
     * - the device has no SD card, or
     * - the device is on Android 16 or later
     *
     * ...then this method might be slower than using [MediaStore.createTrashRequest] directly,
     * because it has to check whether the subtitle uri (which is a Files collection Uri) is
     * actually a subtitle or a generic media file referred to with the wrong Uri type. In all other
     * cases, it either already skips the query or the query is required to fix an actual issue that
     * would cause [MediaStore.createTrashRequest] to fail, and hence this method has optimal speed.
     *
     * Note: folders can be trashed, but all files inside the folder have to be trashed separately.
     * TODO: really? or is trashing folders only since S since trashFile API is S+?
     *  and if that _is_ true then the method should do it recursively by itself?
     *
     * @see MediaStore.createTrashRequest
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun createTrashRequest(context: Context, uris: Collection<Uri>, value: Boolean): PendingIntent {
        var volumesCache: List<StorageVolumeCompat>? = null
        var hasSdCard: Boolean? = null
        var isManager: Boolean? = null
        var urisForSaf: MutableSet<Uri>? = null
        if (!supportsWriteRequestForSidecar()) {
            isManager = isManager(context)
            urisForSaf = mutableSetOf()
        }
        val uris = uris.mapNotNull {
            var vn = MediaStore.getVolumeName(it)
            var mediaFile: File? = null
            var mediaType: Int? = null
            var ownerPackageName: String? = null
            if (vn == VOLUME_EXTERNAL && hasSdCard == null && isAffectedByMoveGenericVolumeBug()) {
                volumesCache = StorageManagerCompat.getStorageVolumes(context)
                hasSdCard = volumesCache.count { v -> v.directory != null } > 1
            }
            // this can infer the type from uri so in most cases we don't actually need to query
            queryMissing(context, it, null, null,
                null, null, needsOwner = isManager == false &&
                        !supportsWriteRequestForSidecar(), needsType = true,
                needsIsDownload = false, needsFile = vn == VOLUME_EXTERNAL &&
                        isAffectedByMoveGenericVolumeBug() && hasSdCard!!
            ) {
                owner, type, _, file ->
                ownerPackageName = owner
                mediaType = type
                mediaFile = file
            }
            if (!supportsWriteRequestForSidecar() && !isManager!! && !isOwned(context,
                    ownerPackageName!!) && (mediaType == MEDIA_TYPE_SUBTITLE ||
                        mediaType == MEDIA_TYPE_PLAYLIST)) {
                urisForSaf!! += it
                return@mapNotNull null
            }
            if (vn == VOLUME_EXTERNAL && isAffectedByMoveGenericVolumeBug() &&
                hasSdCard!!) {
                // https://issuetracker.google.com/issues/350540990 - only affects movement/trashing
                vn = StorageManagerCompat.getVolumeForPath(volumesCache!!, mediaFile!!)
                    .mediaStoreVolumeName!!
            }
            // This ensures generic file media Uris, and especially those of playlists, are
            // rewritten to a supported Uri type. (https://issuetracker.google.com/issues/494294577)
            ContentUris.withAppendedId(getBaseUriForMediaType(vn, mediaType!!),
                ContentUris.parseId(it))
        }
        val out = if (uris.isNotEmpty() || urisForSaf.isNullOrEmpty())
            MediaStore.createTrashRequest(context.contentResolver, uris, value) else null
        if (urisForSaf.isNullOrEmpty()) {
            return out!!
        }
        // unless uris are the same, we need a different requestCode. easiest way to do it
        // is using current time as request code.
        return PendingIntent.getActivity(context,
            System.nanoTime().toInt(),
            Intent(context, DeleteRequestActivity::class.java)
                .apply { if (out != null) putExtra("NextIntent", out) }
                .putExtra("Trash", value)
                .putExtra("Uris", ArrayList(urisForSaf)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    or PendingIntent.FLAG_CANCEL_CURRENT)
    }

    /**
     * Mark a [Uri] as trashed.
     *
     * Will throw [SecurityException] if [needRequestTrash] returns non-null for any of the [Uri]s.
     *
     * This method automatically delegates between [android.content.ContentResolver.update] and
     * [android.provider.DocumentsProvider.renameDocument] (for Android 11 only, for playlists or
     * subtitle files) as needed.
     *
     * Caution: if [android.provider.DocumentsProvider.renameDocument] has to be used, which can
     * only happen on Android 11 and no lower nor higher version, the media ID might change. The new
     * ID is returned as part of a valid media [Uri]. In all other cases the media [Uri] is returned
     * unmodified.
     *
     * Note: folders can be trashed, but all files inside the folder have to be trashed separately.
     * TODO: really? or is trashing folders only since S since trashFile API is S+?
     *  and if that _is_ true then the method should do it recursively by itself?
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun markIsTrashedStatus(context: Context, uri: Uri, isTrashed: Boolean,
                            ownerPackageName: String? = null, mediaType: Int? = null,
                            isDownload: Boolean? = false, mediaFile: File? = null,
                            isManager: Boolean? = null,
                            volumesCache: List<StorageVolumeCompat>? = null,
                            persistedUriPermissionsCache: List<UriPermission>? = null): Uri {
        var ownerPackageName = ownerPackageName
        var mediaType = mediaType
        var mediaFile = mediaFile
        var isDownload = isDownload
        var volumesCache = volumesCache
        if (!supportsWriteRequestForSidecar() && (ownerPackageName == null || !isOwned(context,
                ownerPackageName)) && (mediaType == MEDIA_TYPE_PLAYLIST ||
                    mediaType == MEDIA_TYPE_SUBTITLE || mediaType == null) &&
            !(canBecomeManager(context) && (isManager ?: Environment.isExternalStorageManager()))) {
            queryMissing(
                context, uri, ownerPackageName, mediaType, isDownload,
                null, needsOwner = true, needsType = true, needsIsDownload = false,
                needsFile = true
            ) { owner, type, dl, file ->
                ownerPackageName = owner
                mediaType = type
                isDownload = dl
                mediaFile = file
            }
            if (!isOwned(context, ownerPackageName!!) && (mediaType == MEDIA_TYPE_PLAYLIST ||
                        mediaType == MEDIA_TYPE_SUBTITLE)) {
                val volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
                if (isTrashed == mediaFile!!.name.startsWith(".trashed-"))
                    return uri
                val path = mediaFile.resolveSibling(if (isTrashed) ".trashed-" +
                        "${System.currentTimeMillis() / 1000L + 30 * 24 * 60 * 60}-" +
                        "${mediaFile.name}" else mediaFile.name.substring(".trashed-"
                            .length).substringAfter('-')).absolutePath
                val file = efficientMove(context, uri, path, ownerPackageName,
                    mediaType, isDownload, mediaFile, false, skipPlaylistName = true,
                    volumesCache, persistedUriPermissionsCache)
                return getMediaUriForFile(context, file.path)
            }
        }
        val values = ContentValues()
        val uri = if (isAffectedByMoveGenericVolumeBug() &&
            MediaStore.getVolumeName(uri) == MediaStore.VOLUME_EXTERNAL) {
            // https://issuetracker.google.com/issues/350540990
            volumesCache = volumesCache ?: StorageManagerCompat.getStorageVolumes(context)
            val volume =
                StorageManagerCompat.getVolumeForPath(volumesCache, mediaFile!!)
            if (volume.mediaStoreVolumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                (MediaStore.AUTHORITY_URI.buildUpon()
                    .appendPath(volume.mediaStoreVolumeName).build().toString() + uri
                    .toString().substring(
                        MediaStore.AUTHORITY_URI.buildUpon()
                            .appendPath(MediaStore.getVolumeName(uri)).build().toString()
                            .length
                    )).toUri()
            } else uri
        } else uri
        if (isAffectedByPlaylistMimeReset() && guessMediaTypeFromUri(uri) == MEDIA_TYPE_PLAYLIST) {
            val mimeType = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.MIME_TYPE), null,
                null
            ).use { cursor ->
                if (cursor == null || !cursor.moveToFirst())
                    throw IllegalArgumentException("Can't resolve media Uri: $uri")
                cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.MIME_TYPE
                    )
                )
            }
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }
        values.put(MediaStore.MediaColumns.IS_TRASHED, if (isTrashed) 1 else 0)
        if (context.contentResolver.update(uri, values, null, null) != 1) {
            throw IllegalStateException("failed to mark $uri as trashed: update() returned 0")
        }
        return uri
    }

    /**
     * Mark a [Uri] as favorite.
     *
     * Will throw [SecurityException] if [needRequestFavorite] returns true the [Uri].
     *
     * This method automatically delegates between [MediaStore.markIsFavoriteStatus] and
     * [android.content.ContentResolver.update] as needed (for example if the [Uri]s is writable due
     * to a permission grant and hence [android.content.ContentResolver.update] has to be used with
     * it; or we don't have write permission for but can read thanks to media permission and hence
     * use the [MediaStore.markIsFavoriteStatus] method to set as favorite without write access).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun markIsFavoriteStatus(context: Context, uri: Uri, isFavorite: Boolean,
                             isManager: Boolean? = null, canAccessAudio: Boolean? = null,
                             canAccessVideos: Boolean? = null, canAccessImages: Boolean? = null,
                             mediaType: Int? = null, ownerPackageName: String? = null) {
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 16) {
            if (!(canBecomeManager(context) && (isManager ?:
            Environment.isExternalStorageManager())) && (ownerPackageName == null ||
                    !isOwned(context, ownerPackageName))) {
                // if we got the owner package name and hence know it's our file, we can use that.
                // but we don't need to query for it otherwise, the type is enough.
                var mediaType = mediaType
                queryMissing(context, uri, ownerPackageName, mediaType, null,
                    null, needsOwner = false, needsType = true, needsIsDownload = false,
                    needsFile = false) { _, type, _, _ ->
                    mediaType = type
                }
                if ((mediaType == MEDIA_TYPE_AUDIO || mediaType == MEDIA_TYPE_PLAYLIST) &&
                            canAccessAudio ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && canGetReadAudio(context) && context.checkSelfPermission(
                        Manifest.permission.READ_MEDIA_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.TIRAMISU && (canGetReadExternalStorage(context) &&
                            context.checkSelfPermission(
                                Manifest.permission.READ_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED || hasWriteExternalStorage(context)))
                            || mediaType == MEDIA_TYPE_VIDEO && canAccessVideos ?:
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    canGetReadVideos(context) && context.checkSelfPermission(
                                Manifest.permission.READ_MEDIA_VIDEO) ==
                                    PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                                    Build.VERSION_CODES.TIRAMISU &&
                                    (canGetReadExternalStorage(context) &&
                                            context.checkSelfPermission(
                                                Manifest.permission.READ_EXTERNAL_STORAGE) ==
                                            PackageManager.PERMISSION_GRANTED ||
                                            hasWriteExternalStorage(context)))
                            || mediaType == MEDIA_TYPE_IMAGE && canAccessImages ?:
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    canGetReadImages(context) && context.checkSelfPermission(
                                Manifest.permission.READ_MEDIA_IMAGES) ==
                                    PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                                    Build.VERSION_CODES.TIRAMISU &&
                                    (canGetReadExternalStorage(context) &&
                                            context.checkSelfPermission(
                                                Manifest.permission.READ_EXTERNAL_STORAGE) ==
                                            PackageManager.PERMISSION_GRANTED ||
                                            hasWriteExternalStorage(context)))) {
                    MediaStore.markIsFavoriteStatus(context.contentResolver, listOf(
                        ContentUris.withAppendedId(getBaseUriForMediaType(
                            MediaStore.getVolumeName(uri), mediaType),
                        ContentUris.parseId(uri))),
                        isFavorite)
                    return
                }
            }
        }
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.IS_FAVORITE, if (isFavorite) 1 else 0)
        if (context.contentResolver.update(uri, values, null, null) != 1) {
            throw IllegalStateException("failed to mark $uri as favorite: update() returned 0")
        }
    }

    /**
     * Mark a set of [Uri]s as favorites.
     *
     * Will throw [SecurityException] if [needRequestFavorite] returns true for any of the [Uri]s.
     *
     * This method automatically delegates between [MediaStore.markIsFavoriteStatus] and
     * [android.content.ContentResolver.update] as needed (for example if some of the [Uri]s are
     * writable due to a permission grant and hence [android.content.ContentResolver.update] has to
     * be used with them; and others we don't have write permission for but can read thanks to media
     * permission and hence use the [MediaStore.markIsFavoriteStatus] method to set as favorite
     * without write access).
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun markIsFavoriteStatus(context: Context, uris: Collection<Uri>, isFavorite: Boolean) {
        var urisForUpdate: Collection<Uri>? = uris
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 16) {
            urisForUpdate = null
            var urisForMarkApi: Collection<Uri>? = uris
            if (!canBecomeManager(context) || !Environment.isExternalStorageManager()) {
                val canAccessImages by lazy {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadImages(context)
                            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.TIRAMISU && (canGetReadExternalStorage(context) &&
                            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED || hasWriteExternalStorage(context))
                }
                val canAccessVideos by lazy {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadVideos(context)
                            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.TIRAMISU && canAccessImages
                }
                val canAccessAudio by lazy {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadAudio(context)
                            && context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                            Build.VERSION_CODES.TIRAMISU && canAccessImages
                }
                if (canAccessAudio || canAccessVideos || canAccessImages) {
                    urisForMarkApi = mutableSetOf()
                    urisForUpdate = mutableSetOf()
                    for (it in uris) {
                        var mediaType: Int? = null
                        queryMissing(context, it, null,
                            null, null, null,
                            needsOwner = false, needsType = true, needsIsDownload = false,
                            needsFile = false) { _, mediaTypeH, _, _ ->
                            mediaType = mediaTypeH
                        }
                        val ok = when (mediaType) {
                            MEDIA_TYPE_AUDIO, MEDIA_TYPE_PLAYLIST -> canAccessAudio
                            MEDIA_TYPE_IMAGE -> canAccessImages
                            MEDIA_TYPE_VIDEO -> canAccessVideos
                            else -> false
                        }
                        if (ok) {
                            urisForMarkApi += ContentUris.withAppendedId(
                                getBaseUriForMediaType(
                                    MediaStore.getVolumeName(it), mediaType!!),
                                ContentUris.parseId(it))
                        } else
                            urisForUpdate += it
                    }
                } else {
                    urisForMarkApi = null
                    urisForUpdate = uris
                }
            }
            if (urisForMarkApi?.isNotEmpty() == true) {
                MediaStore.markIsFavoriteStatus(context.contentResolver, urisForMarkApi,
                    isFavorite)
            }
        }
        if (urisForUpdate?.isNotEmpty() == true) {
            val failed = mutableListOf<Exception>()
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.IS_FAVORITE, if (isFavorite) 1 else 0)
            for (uri in urisForUpdate) {
                try {
                    if (context.contentResolver.update(
                            uri, values, null,
                            null
                        ) != 1
                    ) {
                        failed.add(IllegalStateException("failed to mark $uri as favorite:" +
                                "update() returned 0"))
                    }
                } catch (e: SecurityException) {
                    failed.add(e)
                }
            }
            if (failed.size > 1) {
                val main = failed.first()
                failed.subList(1, failed.size).forEach {
                    main.addSuppressed(it)
                }
                throw main
            } else if (failed.size == 1) {
                throw failed.first()
            }
        }
    }

    /**
     * Convenience method to check whether the access required to favorite this item needs to be
     * requested. If this method returns true, use [createFavoriteRequest] to favorite the file,
     * otherwise use [markIsFavoriteStatus].
     *
     * Favorites are not supported before Android 11.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun needRequestFavorite(context: Context, uris: Collection<Uri>): Boolean {
        if (canBecomeManager(context) && Environment.isExternalStorageManager()) {
            return false
        }
        val uris = uris.toMutableSet()
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 16) {
            val canAccessImages by lazy {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadImages(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && (canGetReadExternalStorage(context) &&
                        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED || canGetWriteExternalStorage(context) &&
                        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED)
            }
            val canAccessVideos by lazy {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadVideos(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && canAccessImages
            }
            val canAccessAudio by lazy {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadAudio(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && canAccessImages
            }
            if (canAccessVideos || canAccessImages || canAccessAudio) {
                return uris.find {
                    var mediaType: Int? = null
                    var ownerPackageName: String? = null
                    // values marked as not needed might still get queried to determine values
                    // marked as needed, so if we query them anyway, cache them
                    queryMissing(context, it, null,
                        null, null, null,
                        needsOwner = false, needsType = true, needsIsDownload = false,
                        needsFile = false) { ownerPackageNameH, mediaTypeH, _, _ ->
                        mediaType = mediaTypeH
                        // even if needsOwner is false we may get it as side effect of type query
                        ownerPackageName = ownerPackageNameH
                    }
                    val ok = when (mediaType) {
                        MEDIA_TYPE_AUDIO, MEDIA_TYPE_PLAYLIST -> canAccessAudio
                        MEDIA_TYPE_IMAGE -> canAccessImages
                        MEDIA_TYPE_VIDEO -> canAccessVideos
                        else -> false
                    }
                    // because SDK >=R, all other optional parameters are never used, thus don't add
                    // them here
                    return !ok && needRequestSqlUpdateInternal(context, it,
                        false, ownerPackageName, mediaType)
                } != null
            }
        }
        return uris.find {
            needRequestSqlUpdateInternal(context, it)
        } != null
    }

    /**
     * Convenience method to check whether the access required to favorite this item needs to be
     * requested. If this method returns a non-null value, use [createFavoriteRequest] to favorite
     * the file, otherwise use [markIsFavoriteStatus].
     *
     * Favorites are not supported before Android 11.
     *
     * Throws [IllegalArgumentException] if the media uri is not readable.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    @JvmOverloads
    fun needRequestFavorite(context: Context, mediaUri: Uri,
                            canAccessImages: Boolean?, canAccessVideos: Boolean?,
                            canAccessAudio: Boolean?, isManager: Boolean? = null,
                            ownerPackageName: String? = null, mediaType: Int? = null): RequestToken? {
        if (canBecomeManager(context) && (isManager ?: Environment.isExternalStorageManager())) {
            return null
        }
        var mediaType = mediaType
        var ownerPackageName = ownerPackageName
        if (SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 16) {
            val canAccessImages by lazy { canAccessImages ?: (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadImages(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && (canGetReadExternalStorage(context) &&
                        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED || canGetWriteExternalStorage(context) &&
                        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED))
            }
            val canAccessVideos by lazy { canAccessVideos ?: (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadVideos(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && canAccessImages)
            }
            val canAccessAudio by lazy { canAccessAudio ?: (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canGetReadAudio(context)
                        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT <
                        Build.VERSION_CODES.TIRAMISU && canAccessImages)
            }
            if (canAccessVideos || canAccessImages || canAccessAudio) {
                queryMissing(context, mediaUri, ownerPackageName,
                    mediaType, null, null,
                    needsOwner = false, needsType = true, needsIsDownload = false,
                    needsFile = false) { ownerPackageNameH, mediaTypeH, _, _ ->
                    mediaType = mediaTypeH
                    // value marked as not needed might still get queried to determine value marked
                    // as needed, so if we query them anyway, cache them
                    ownerPackageName = ownerPackageNameH
                }
                if (when (mediaType) {
                    MEDIA_TYPE_AUDIO, MEDIA_TYPE_PLAYLIST -> canAccessAudio
                    MEDIA_TYPE_IMAGE -> canAccessImages
                    MEDIA_TYPE_VIDEO -> canAccessVideos
                    else -> false
                })
                    return null
            }
        }
        return needRequestSqlUpdate(context, mediaUri, UpdateMode.Single,
            ownerPackageName, mediaType, isManager = false)
    }

    /**
     * This method ensures every Uri is a well-defined Uri (see [getBaseUriForMediaType]) as
     * required by this API, but otherwise works the same as the original one. Requires Android 11
     * or later.
     *
     * If [needRequestFavorite] returns false for every [Uri], [markIsFavoriteStatus] can be used
     * directly, otherwise this request method has to be used.
     *
     * Performance: If there is a subtitle uris among the uris, then this method might be slower
     * than using [MediaStore.createFavoriteRequest] directly, because it has to check whether the
     * subtitle uri (which is a Files collection Uri) is actually a subtitle or a generic media file
     * referred to with the wrong Uri type. In all other cases, either it skips the query, or the
     * query is required to fix an actual issue that would cause [MediaStore.createFavoriteRequest]
     * to fail, and hence this method has optimal speed.
     *
     * @see MediaStore.createFavoriteRequest
     */
    @RequiresApi(Build.VERSION_CODES.R)
    @JvmStatic
    fun createFavoriteRequest(context: Context, uris: Collection<Uri>, value: Boolean): PendingIntent {
        val uris = uris.map {
            var mediaType: Int? = null
            // this can infer the type from uri so in most cases we don't actually need to query
            queryMissing(context, it, null, null,
                null, null, needsOwner = false, needsType = true,
                needsIsDownload = false, needsFile = false
            ) { _, type, _, _ ->
                mediaType = type
            }
            val vn = MediaStore.getVolumeName(it)
            // This ensures generic file media Uris, and especially those of playlists, are
            // rewritten to a supported Uri type. (https://issuetracker.google.com/issues/494294577)
            ContentUris.withAppendedId(getBaseUriForMediaType(vn, mediaType!!),
                ContentUris.parseId(it))
        }
        return MediaStore.createFavoriteRequest(context.contentResolver, uris, value)
    }

    /** @see createWriteRequest */
    sealed class RequestToken(
        internal val uri: String?, // >=R MediaStore uri, <R SAF documentId (not uri)
        internal val requestManager: Boolean // >=R MANAGE_E_S, <R WRITE_E_S
    ) {
        internal class Uri(uri: String?, requestManager: Boolean) :
            RequestToken(uri, requestManager)
        internal object Manager : RequestToken(null, true)

        override fun hashCode(): Int {
            var result = requestManager.hashCode()
            result = 31 * result + (uri?.hashCode() ?: 0)
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RequestToken) return false

            if (requestManager != other.requestManager) return false
            if (uri != other.uri) return false

            return true
        }
    }

    interface SafPolicy {
        fun process(inDocumentIds: Set<String>, outYesNoIds: MutableSet<String>,
                    outDocsUiIds: MutableSet<String>)
    }

    internal object DefaultSafPolicy : SafPolicy {
        override fun process(
            inDocumentIds: Set<String>,
            outYesNoIds: MutableSet<String>,
            outDocsUiIds: MutableSet<String>
        ) {
            inDocumentIds.forEach {
                // default policy: prefer yes-no always
                val volume = StorageManagerCompat.getExternalStorageVolumeName(it)
                val volumeId = StorageManagerCompat.buildExternalStorageDocumentId(
                    volume, "")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    outYesNoIds.add(volumeId)
                } else {
                    outDocsUiIds.add(volumeId)
                }
            }
        }
    }

    private fun managerCanSkipAllDialogs() =
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 ||
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 22

    /**
     * Returns whether a write request with this [RequestToken] will show a user-visible dialog.
     *
     * Some situations such as favorites or if the MANAGE_MEDIA permission is granted end up
     * requiring apps to send an intent which does grant permission without asking the user.
     *
     * Refer to [willDeleteRequestBeUserVisible] or [willTrashRequestBeUserVisible] for delete or
     * trash requests respectively.
     *
     * Note: there is no `willFavoriteRequestBeUserVisible` because the answer is always false.
     */
    @JvmStatic
    fun willWriteRequestBeUserVisible(context: Context, token: RequestToken): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || token.requestManager) {
            return true
        }
        if (managerCanSkipAllDialogs() && isManager(context))
            return false
        val mediaStoreUri = token.uri!!.toUri()
        if (willDeleteRequestBeUserVisible(context, mediaStoreUri))
            return true
        // https://issuetracker.google.com/issues/507322763
        if (ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_MEDIA_LOCATION) !=
            PackageManager.PERMISSION_GRANTED)
            return true
        return false
    }

    /**
     * Returns whether a delete request with this [Uri] will show a user-visible dialog.
     *
     * Some situations such as favorites or if the MANAGE_MEDIA permission is granted end up
     * requiring apps to send an intent which does grant permission without asking the user.
     *
     * Refer to [willWriteRequestBeUserVisible] or [willTrashRequestBeUserVisible] for write or
     * trash requests respectively.
     *
     * Note: there is no `willFavoriteRequestBeUserVisible` because the answer is always false.
     */
    @JvmStatic
    fun willDeleteRequestBeUserVisible(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        if (managerCanSkipAllDialogs() && isManager(context))
            return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.MANAGE_MEDIA)
            != PackageManager.PERMISSION_GRANTED)
            return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isManager(context)) {
                var mediaType: Int? = null
                queryMissing(context, uri, null, null,
                    null, null, needsType = true, needsFile = false,
                    needsIsDownload = false, needsOwner = false) { _, type, _, _ ->
                    mediaType = type
                }
                val ok = when (mediaType) {
                    MEDIA_TYPE_AUDIO, MEDIA_TYPE_PLAYLIST -> ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    MEDIA_TYPE_VIDEO -> ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_VIDEO) ==
                            PackageManager.PERMISSION_GRANTED
                    MEDIA_TYPE_IMAGE -> ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_IMAGES) ==
                            PackageManager.PERMISSION_GRANTED
                    MEDIA_TYPE_SUBTITLE -> ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_MEDIA_VIDEO) ==
                            PackageManager.PERMISSION_GRANTED
                    else -> false
                }
                if (!ok)
                    return true
            }
        } else {
            if (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED && !isManager(context))
                return true
        }
        return false
    }

    /**
     * Returns whether a trash request with this [Uri] will show a user-visible dialog.
     *
     * Some situations such as favorites or if the MANAGE_MEDIA permission is granted end up
     * requiring apps to send an intent which does grant permission without asking the user.
     *
     * Refer to [willDeleteRequestBeUserVisible] or [willWriteRequestBeUserVisible] for delete or
     * write requests respectively.
     *
     * Note: there is no `willFavoriteRequestBeUserVisible` because the answer is always false.
     */
    @JvmStatic
    @RequiresApi(Build.VERSION_CODES.R)
    fun willTrashRequestBeUserVisible(context: Context, uri: Uri): Boolean {
        return willDeleteRequestBeUserVisible(context, uri)
    }

    var safPolicy: SafPolicy = DefaultSafPolicy

    /**
     * This can be used to create a batch (or one operation) permission request.
     *
     * It's important that the [Uri]s used to create the tokens, and also to do the operations, are
     * well-defined [Uri]s on Android 10 or later. Refer to [getBaseUriForMediaType] for more
     * information.
     *
     * The following operations are supported:
     * - [create] if [needRequestCreate] returns a token
     * - [ContentResolver.update] if [needRequestSqlUpdate] returns a token
     * - [markIsTrashedStatus] if [needRequestTrash] returns a token
     * - [openFileDescriptor] if [needRequestBytesWrite] returns a token
     * - [efficientMove] if [needRequestEfficientMove] returns a token
     * - [markIsFavoriteStatus] if [needRequestFavorite] returns a token
     *   (the overload for multiple uris can't be used to get tokens for use with
     *   [createWriteRequest], but the other can, and once access is granted, the multi-uri one will
     *   work)
     * - [delete] if [needRequestDelete] returns a token
     *
     * It will _not_ perform any of the operations. This is in contrast to the specialized
     * [createDeleteRequest], [createTrashRequest] and [createFavoriteRequest]
     * operations which will instantly perform the requested actions.
     *
     * You can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris
     * will result in a [IllegalArgumentException].
     */
    @JvmStatic
    fun createWriteRequest(context: Context, tokens: Collection<RequestToken>): PendingIntent {
        val someTokenWantsManager = tokens.find { it.requestManager } != null
        if (someTokenWantsManager && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On R+, Manager means we don't need SAF/WR, so request manager only. Unless something
            // impossible is explicitly requested, we always only use WR, so if we do end up here
            // then we really need manager permission to report ACTION_OK, so no fallback is needed.
            if (!canBecomeManager(context)) {
                throw IllegalStateException("Unreachable - token wants manager but we can't be")
            }
            return PendingIntent.getActivity(context, 0,
                Intent(context, ManagerRequestActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                        or PendingIntent.FLAG_CANCEL_CURRENT)
        } else {
            var uris = tokens.mapNotNull { it.uri }.toSet()
            var out: PendingIntent? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var urisForSaf: MutableSet<String>? = null
                var uris2: List<Uri>
                if (supportsWriteRequestForSidecar()) {
                    uris2 = uris.map { it.toUri() }
                } else {
                    urisForSaf = mutableSetOf()
                    uris2 = uris.mapNotNull { if (it.startsWith("content"))
                        it.toUri() else { urisForSaf += it; null } }
                }
                if (uris2.isNotEmpty() || urisForSaf.isNullOrEmpty()) try {
                    out = MediaStore.createWriteRequest(context.contentResolver, uris2)
                } catch (e: Exception) {
                    if (e is IllegalArgumentException && e.message == "All requested items must" +
                        " be referenced by specific ID")
                        throw IllegalArgumentException("Unsupported uri type: ${uris2.filter { 
                            when (guessMediaTypeFromUri(it)) {
                                MEDIA_TYPE_AUDIO -> false
                                MEDIA_TYPE_VIDEO -> false
                                MEDIA_TYPE_PLAYLIST if supportsWriteRequestForSidecar() -> false
                                MEDIA_TYPE_IMAGE -> false
                                else if supportsWriteRequestForSidecar() && it.pathSegments[1] ==
                                        FILES_EXTERNAL_CONTENT_URI.pathSegments[1] -> false
                                else -> true
                            }
                        }} (Note that subtitles and playlists are supported since Android 12" +
                                " or if sufficient Google Play system updates are installed)", e)
                    if (e is IllegalArgumentException && e.message == "All requested items must" +
                        " be Media items" && supportsWriteRequestForSidecar())
                        throw IllegalArgumentException("Uris should be well-defined: ${uris2.filter {
                            when (it.pathSegments[1]) {
                                FILES_EXTERNAL_CONTENT_URI.pathSegments[1] -> {
                                    var mediaType: Int? = null
                                    try {
                                        queryMissing(
                                            context,
                                            it,
                                            null,
                                            null,
                                            null,
                                            null,
                                            needsOwner = false,
                                            needsType = true,
                                            needsIsDownload = false,
                                            needsFile = false
                                        ) { _, type, _, _ ->
                                            mediaType = type
                                        }
                                        mediaType != MEDIA_TYPE_SUBTITLE
                                    } catch (t: IllegalArgumentException) {
                                        e.addSuppressed(t)
                                        false
                                    }
                                }
                                else -> false
                            }
                        }}", e)
                    throw e
                }
                if (urisForSaf.isNullOrEmpty()) {
                    return out!!
                }
                uris = urisForSaf
            }
            // The activity will always use the "Uris" list to decide what we need to request.
            // These two sets are just used to transmit suggestions on what to request (for
            // yes-no dialog) / starting position (for SAF folder picker) and in what order. If
            // a suggestion is not required because all required permissions have been obtained
            // already, it is silently discarded. (That allows users to pick more specific
            // folder if they really want to.)
            val yesNoUris = mutableSetOf<String>()
            val safPickerUris = mutableSetOf<String>()
            safPolicy.process(uris, yesNoUris,
                safPickerUris)
            // unless uris are the same, we need a different requestCode. easiest way to do it
            // is using current time as request code.
            return PendingIntent.getActivity(context,
                System.nanoTime().toInt(),
                Intent(context, WriteRequestActivity::class.java)
                    .putExtra("NeedManager", someTokenWantsManager)
                    .apply { if (out != null) putExtra("NextIntent", out) }
                    .putExtra("Uris", ArrayList(uris))
                    .putExtra("YesNoSuggest", ArrayList(yesNoUris))
                    .putExtra("SafSuggest", ArrayList(safPickerUris)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                        or PendingIntent.FLAG_CANCEL_CURRENT)
        }
    }

    /**
     * This can be used to create a batch (or one operation) permission request for [delete]ing if
     * [needRequestDelete] returns true.
     *
     * It will perform all the operations when the Intent returns [android.app.Activity.RESULT_OK].
     * This is in contrast to the  general [createWriteRequest] method which will ask for permission
     * but won't do anything. The permission dialog of this method shows specialized text asking the
     * user for permission to delete files on Android 11 and later.
     *
     * Performance: On Android 11 or later, if there is a subtitle uris among the uris, then this
     * method might be slower than using [MediaStore.createDeleteRequest] directly, because it has
     * to check whether the subtitle uri (which is a Files collection Uri) is actually a subtitle or
     * a generic media file referred to with the wrong Uri type. In all other cases, either it skips
     * the query, or the query is required to fix an actual issue that would cause
     * [MediaStore.createDeleteRequest] to fail, and hence this method has optimal speed.
     *
     * You can send a maximum of 2000 uris in each request. Attempting to send more than 2000 uris
     * will result in a [IllegalArgumentException].
     */
    @JvmStatic
    fun createDeleteRequest(context: Context, uris: Collection<Uri>): PendingIntent {
        if (uris.size > 2000)
            throw IllegalArgumentException("Too many URIs: ${uris.size} > 2000")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var isManager: Boolean? = null
            var urisForSaf: MutableSet<Uri>? = null
            if (!supportsWriteRequestForSidecar()) {
                isManager = isManager(context)
                urisForSaf = mutableSetOf()
            }
            val uris = uris.mapNotNull {
                var ownerPackageName: String? = null
                var mediaType: Int? = null
                // this can infer the type from uri so in most cases we don't actually need to query
                queryMissing(context, it, null, null,
                    null, null, needsOwner = isManager == false &&
                            !supportsWriteRequestForSidecar(), needsType = true,
                    needsIsDownload = false, needsFile = false
                ) { owner, type, _, _ ->
                    ownerPackageName = owner
                    mediaType = type
                }
                if (!supportsWriteRequestForSidecar() && !isManager!! && !isOwned(context,
                        ownerPackageName!!) && (mediaType == MEDIA_TYPE_SUBTITLE ||
                            mediaType == MEDIA_TYPE_PLAYLIST)) {
                    urisForSaf!! += it
                    return@mapNotNull null
                }
                val vn = MediaStore.getVolumeName(it)
                // This ensures generic file media Uris, and especially those of playlists, are
                // rewritten to a supported Uri type.
                // (https://issuetracker.google.com/issues/494294577)
                ContentUris.withAppendedId(getBaseUriForMediaType(vn,
                    mediaType!!), ContentUris.parseId(it))
            }
            val out = if (uris.isNotEmpty() || urisForSaf.isNullOrEmpty())
                MediaStore.createDeleteRequest(context.contentResolver, uris) else null
            if (urisForSaf.isNullOrEmpty()) {
                return out!!
            }
            // unless uris are the same, we need a different requestCode. easiest way to do it
            // is using current time as request code.
            return PendingIntent.getActivity(context,
                System.nanoTime().toInt(),
                Intent(context, DeleteRequestActivity::class.java)
                    .apply { if (out != null) putExtra("NextIntent", out) }
                    .putExtra("Uris", ArrayList(urisForSaf)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                        or PendingIntent.FLAG_CANCEL_CURRENT)
        }
        uris.forEach {
            if (it.authority?.equals(MediaStore.AUTHORITY) == false)
                throw IllegalArgumentException("Expected a MediaStore uri: $it")
        }
        return PendingIntent.getActivity(context,
            // unless uris are the same, we need a different requestCode. easiest way to fulfill it
            // is using current time as request code.
            System.nanoTime().toInt(),
            Intent(context, DeleteRequestActivity::class.java)
                .putExtra("Uris", ArrayList(uris)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    or PendingIntent.FLAG_CANCEL_CURRENT)
    }

    private fun findParentTreeWithPersisted(context: Context, documentId: String, forWrite: Boolean?,
                                            persistedUriPermissions: List<UriPermission>): Uri? {
        return DocumentsContract.buildDocumentUriUsingTree(
            persistedUriPermissions.find { prefix ->
                prefix.isReadPermission && (forWrite == false || prefix.isWritePermission) &&
                        DocumentsContract.getTreeDocumentId(prefix.uri).let {
                            documentId.startsWith(it)
                        } && context.checkGrantSelfUriPermission(prefix.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or if (forWrite != false)
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            }?.uri ?: (if (forWrite == null) persistedUriPermissions.find { prefix ->
                prefix.isReadPermission && !prefix.isWritePermission &&
                        DocumentsContract.getTreeDocumentId(prefix.uri).let {
                            documentId.startsWith(it)
                        } && context.checkGrantSelfUriPermission(prefix.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }?.uri else null) ?: return null, documentId
        )
    }

    private fun findUriWithPersisted(context: Context, documentId: String, forWrite: Boolean?,
                                     persistedUriPermissions: List<UriPermission>): Uri? {
        val treeWritable = persistedUriPermissions.find { prefix ->
            prefix.isReadPermission && (forWrite == false || prefix.isWritePermission) &&
                    DocumentsContractCompat.isTreeUri(prefix.uri) &&
                    DocumentsContract.getTreeDocumentId(prefix.uri).let {
                        documentId.startsWith(it)
                    } && context.checkGrantSelfUriPermission(prefix.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or if (forWrite != false)
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        }?.uri
        if (treeWritable == null)
            persistedUriPermissions.find { prefix ->
                prefix.isReadPermission && (forWrite == false || prefix.isWritePermission) &&
                        !DocumentsContractCompat.isTreeUri(prefix.uri) &&
                        documentId == DocumentsContract.getDocumentId(prefix.uri)
                        && context.checkGrantSelfUriPermission(prefix.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or if (forWrite != false)
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            }?.uri?.let { return it }
        return DocumentsContract.buildDocumentUriUsingTree(
            treeWritable ?: (if (forWrite == null) persistedUriPermissions.find { prefix ->
                prefix.isReadPermission && !prefix.isWritePermission &&
                        DocumentsContract.getTreeDocumentId(prefix.uri).let {
                            documentId.startsWith(it)
                        } && context.checkGrantSelfUriPermission(prefix.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }?.uri ?: (persistedUriPermissions.find { prefix ->
                prefix.isReadPermission && !prefix.isWritePermission &&
                        !DocumentsContractCompat.isTreeUri(prefix.uri) &&
                        documentId == DocumentsContract.getDocumentId(prefix.uri)
                        && context.checkGrantSelfUriPermission(prefix.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }?.uri?.let { return it }) else null) ?: return null, documentId
        )
    }

    private fun findParentTreeWithPermission(context: Context, documentId: String,
                                             modeFlags: Int): Uri? {
        val documentUri = DocumentsContract.buildDocumentUri(
            StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE, documentId
        )
        if (context.checkGrantSelfUriPermission(documentUri, modeFlags))
            return documentUri
        val volumeName = StorageManagerCompat.getExternalStorageVolumeName(documentId)
        val fullPath = StorageManagerCompat.getExternalStoragePath(documentId)
        var idx = 0
        var pathFile: String
        do {
            pathFile = if (idx != -1) fullPath.substring(0, idx) else fullPath
            val newId = StorageManagerCompat.buildExternalStorageDocumentId(
                volumeName, pathFile)
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                newId
            )
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            if (context.checkGrantSelfUriPermission(treeDocUri, modeFlags))
                return treeDocUri
            idx = fullPath.indexOf('/', startIndex = pathFile.length + 1)
        } while (pathFile.length < fullPath.length)
        return null
    }

    enum class ResolvePermissions {
        /**
         * Only returns [Uri]s that can be accessed by this app without any modification.
         * This implies automatically detecting the most appropriate tree prefix (or its
         * absence) from the available permission grants.
         *
         * To achieve this, we have to brute-force through all possible tree prefixes with
         * several Binder calls, which is a very expensive operation.
         */
        Full,

        /**
         * Only returns [Uri]s that can be accessed by this app without any modification.
         * This implies automatically detecting the most appropriate tree prefix (or its
         * absence) from the available permission grants.
         *
         * However, this only respects persisted permission grants (created using
         * [android.content.ContentResolver.takePersistableUriPermission]) or a permission
         * grant for a document [Uri] without tree prefix. It requires one additional Binder
         * call per [Uri] (provided that the list of persisted [Uri] permissions is stored into
         * persistedUriPermissionsCache, which requires one additional Binder call in total for a
         * batch of [Uri]s. If this caches is not populated, this information will be queried which
         * will execute one additional Binder call per [Uri]).
         *
         * This mode will detect every Uri the [Full] mode detects, except if the app receives
         * access to a tree [Uri] with [Intent.FLAG_GRANT_PREFIX_URI_PERMISSION] set and does
         * not persist the permission to this [Uri]. Because this mode only needs to call
         * [Context.checkUriPermission] once instead of multiple times, as we do not brute-force
         * the tree prefix, this is several orders of magnitude faster than [Full].
         */
        OnlyPersistedOrDirect,

        /**
         * Only returns [Uri]s that can be accessed by this app without any modification.
         * This implies automatically detecting the most appropriate tree prefix (or its
         * absence) from the available permission grants.
         *
         * However, this only respects persisted permission grants (created using
         * [android.content.ContentResolver.takePersistableUriPermission]) and as such can be
         * used without any additional Binder calls (this requires the list of persisted [Uri]
         * permissions to be stored in persistedUriPermissionsCache, which requires
         * one Binder call for a batch of [Uri]s instead of for every [Uri]. If this cache isn't
         * populated, this information will be queried which will execute one additional Binder
         * call per [Uri]).
         *
         * As such, this is several orders of magnitude faster than the [OnlyPersistedOrDirect]
         * mode and is useful if this app always persists the document permissions it receives,
         * or at least always persists the document tree permissions it receives.
         *
         * This will deliver the same results as the [MediaStore.getDocumentUri] function.
         */
        OnlyPersisted,

        /**
         * Only returns [Uri]s that can be accessed by this app without any modification.
         * This implies automatically detecting the most appropriate tree prefix from the
         * available permission grants. A direct persisted document [Uri] grant will be ignored,
         * unlike [OnlyPersisted] or [OnlyPersistedOrDirect].
         *
         * However, this only respects persisted permission grants (created using
         * [android.content.ContentResolver.takePersistableUriPermission]) and as such can be
         * used without any additional Binder calls (this requires the list of persisted [Uri]
         * permissions to be stored in.persistedUriPermissionsCache, which requires
         * one Binder call for a batch of [Uri]s instead of for every [Uri]. If this cache isn't
         * populated, this information will be queried which will execute one additional Binder
         * call per [Uri]).
         *
         * As such, this is several orders of magnitude faster than the [OnlyPersistedOrDirect]
         * mode and is useful if this app always persists the document tree permissions it
         * receives.
         */
        OnlyPersistedTree,

        /**
         * This may return documents the app cannot access, because this mode does not perform any
         * permission checks at all. The return value is always the document ID only form, that is,
         * a [String].
         */
        Never
    }
}
