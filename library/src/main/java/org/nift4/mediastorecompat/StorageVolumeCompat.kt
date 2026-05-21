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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.DeprecatedSinceApi
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import java.io.File
import java.util.Locale

/** Backport of some features of [StorageVolume] using hidden API on Android versions before 11. */
class StorageVolumeCompat {
    /** @see StorageVolume.getUuid */
    @JvmField val uuid: String?
    /** @see StorageVolume.getState */
    @JvmField val state: String
    /**
     * Unlike the platform API, this will be null if the volume is invisible (that is, can neither
     * be read from nor written to using the File API, and is not mounted under /storage at all).
     * Invisible volumes (such as non-adoptable/unreliable SD cards, or USB drivers) can only be
     * interacted with using Storage Access Framework.
     *
     * @see StorageVolume.getDirectory
     */
    @JvmField val directory: File?
    /** The result of [File.getCanonicalFile] on [directory]. */
    @JvmField val canonicalDirectory: File?
    /**
     * MediaStore started having separate indexes per volume in Android 10, therefore this is
     * only available on Android 10 and later. On versions before that, there is just the combined
     * external view [MediaStoreCompat.VOLUME_EXTERNAL].
     *
     * @see StorageVolume.getMediaStoreVolumeName
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @JvmField val mediaStoreVolumeName: String?
    /**
     * Return the volume name that can be used to interact with this storage device through
     * the documents provider with the authority [StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE].
     */
    val documentsProviderVolumeName: String
        get() = if (isEmulated) StorageManagerCompat.EXTERNAL_STORAGE_VOLUME_PRIMARY else uuid!!
    /** @see StorageVolume.isPrimary */
    @JvmField val isPrimary: Boolean
    /** @see StorageVolume.isEmulated */
    @JvmField val isEmulated: Boolean
    /**
     * The backing [StorageVolume].
     *
     * This field is populated with the hidden API object on versions before Nougat.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    @JvmField val real: StorageVolume
    private val descriptionLegacy: String?
    private val realLegacy: Any
        get() = @SuppressLint("NewApi") real

    constructor(real: StorageVolume) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.uuid = real.uuid
            this.isEmulated = real.isEmulated
            this.isPrimary = real.isPrimary
            this.real = real
            this.state = real.state
            descriptionLegacy = null
        } else {
            this.uuid = getUuid.invoke(real) as String?
            this.state = getState.invoke(real) as String
            this.isEmulated = isEmulatedMethod.invoke(real) as Boolean
            this.isPrimary = isPrimaryMethod.invoke(real) as Boolean
            @SuppressLint("NewApi")
            this.real = real
            this.descriptionLegacy = getUserLabel.invoke(real) as String?
        }
        // Note that getExternalStorageState() does not return the same storage state, but
        // instead has two differences:
        // - verifies that this volume is visible, otherwise returns Environment.MEDIA_UNKNOWN
        // - that we have storage permission, otherwise returns Environment.MEDIA_UNMOUNTED
        // We use this to filter out totally inaccessible directories (because the volume is
        // invisible) and use null instead, but we also have to be careful to not trust it for
        // mounted state because it pretends unmounted state based on lack of storage permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.directory = if (real.directory != null && Environment.getExternalStorageState(
                real.directory) != Environment.MEDIA_UNKNOWN) real.directory else null
            this.mediaStoreVolumeName = real.mediaStoreVolumeName
        } else {
            this.directory = if (state == Environment.MEDIA_MOUNTED ||
                state == Environment.MEDIA_MOUNTED_READ_ONLY
            ) {
                val dir = getPathFile.invoke(real) as File?
                if (dir != null && Environment.getExternalStorageState(dir) !=
                    Environment.MEDIA_UNKNOWN) dir else null
            } else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                this.mediaStoreVolumeName = if (real.isPrimary) {
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
                } else real.uuid?.lowercase(Locale.US)
            } else if (directory != null) {
                @SuppressLint("NewApi")
                this.mediaStoreVolumeName = MediaStoreCompat.VOLUME_EXTERNAL
            } else {
                @SuppressLint("NewApi")
                this.mediaStoreVolumeName = null
            }
        }
        canonicalDirectory = directory?.canonicalFile // does I/O, hence cached
    }

    private fun ensureDirectory() {
        if (directory != null) {
            return
        }
        if (state != Environment.MEDIA_MOUNTED &&
            state != Environment.MEDIA_MOUNTED_READ_ONLY)
            throw IllegalStateException("Expected volume to be mounted in order to get path: $this")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (real.directory == null) {
                throw IllegalStateException("Expected volume to have a path instead of null: $this")
            }
            throw IllegalStateException("Expected volume to be visible in order to get path: $this")
        }
        getPathFile.invoke(realLegacy) as File?
            ?: throw IllegalStateException("Expected volume to have a path instead of null: $this")
        throw IllegalStateException("Expected volume to be visible in order to get path: $this")
    }

    fun requireDirectory(): File {
        ensureDirectory()
        return directory!!
    }

    fun requireCanonicalDirectory(): File {
        ensureDirectory()
        return canonicalDirectory!!
    }

    /** @see StorageVolume.getDescription */
    fun getDescription(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            real.getDescription(context)
        } else {
            descriptionLegacy ?:
                getDescription.invoke(realLegacy, context) as String
        }
    }

    /**
     * This creates a [Intent] with the action [Intent.ACTION_OPEN_DOCUMENT_TREE] that is configured
     * to show internal storage devices in the document provider selection navigation drawer by
     * default.
     *
     * Since Android 8, this will set the initial location of the document navigation to the root of
     * this [StorageVolumeCompat].
     *
     * @see [StorageVolume.createOpenDocumentTreeIntent]
     */
    fun createOpenDocumentTreeIntent(folder: String? = null): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val intent = real.createOpenDocumentTreeIntent()
            if (!folder.isNullOrEmpty()) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                    IntentCompat.getParcelableExtra(intent,
                        DocumentsContract.EXTRA_INITIAL_URI, Uri::class.java)!!.let {
                        val volume = DocumentsContract.getRootId(it)
                        DocumentsContract.buildDocumentUri(
                            StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                            StorageManagerCompat.buildExternalStorageDocumentId(
                                volume, folder
                            )
                        )
                    })
            }
            return intent
        } else {
            // AOSP uses root uri for Q+ but I found that only document uri works on older vers
            val rootUri = DocumentsContract.buildDocumentUri(
                StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                StorageManagerCompat.buildExternalStorageDocumentId(
                    documentsProviderVolumeName, ""
                )
            )
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootUri)
            } else { // try our luck
                intent.putExtra("android.provider.extra.INITIAL_URI", rootUri)
            }
            // there seem to be both versions out there
            intent.putExtra("android.provider.extra.SHOW_ADVANCED", true)
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
            return intent
        }
    }

    /** @see [StorageVolume.createAccessIntent] */
    @RequiresApi(Build.VERSION_CODES.N)
    @DeprecatedSinceApi(Build.VERSION_CODES.Q,
        "use StorageVolume#createOpenDocumentTreeIntent() instead")
    fun createAccessIntent(directoryName: String?): Intent? {
        @Suppress("deprecation")
        return real.createAccessIntent(directoryName)
    }

    override fun toString(): String {
        return "StorageVolumeCompat(uuid=$uuid, state='$state', directory=$directory, " +
                "canonicalDirectory=$canonicalDirectory, mediaStoreVolumeName=" +
                "${@SuppressLint("NewApi") mediaStoreVolumeName}, " +
                "documentsProviderVolumeName=${if (isEmulated || uuid != null)
                    "'$documentsProviderVolumeName'" else "null"}, isPrimary=$isPrimary" +
                ", isEmulated=$isEmulated, descriptionLegacy=$descriptionLegacy, real=$realLegacy)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StorageVolumeCompat

        if (isPrimary != other.isPrimary) return false
        if (isEmulated != other.isEmulated) return false
        if (uuid != other.uuid) return false
        if (directory != other.directory) return false
        if (canonicalDirectory != other.canonicalDirectory) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (mediaStoreVolumeName != other.mediaStoreVolumeName) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = isPrimary.hashCode()
        result = 31 * result + isEmulated.hashCode()
        result = 31 * result + (uuid?.hashCode() ?: 0)
        result = 31 * result + (directory?.hashCode() ?: 0)
        result = 31 * result + (canonicalDirectory?.hashCode() ?: 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result = 31 * result + (mediaStoreVolumeName?.hashCode() ?: 0)
        }
        return result
    }

    companion object {
        private val getPathFile by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("getPathFile")
        }
        private val getUuid by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("getUuid")
        }
        private val getState by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("getState")
        }
        private val isEmulatedMethod by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("isEmulated")
        }
        private val isPrimaryMethod by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("isPrimary")
        }
        private val getUserLabel by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("getUserLabel")
        }
        private val getDescription by lazy {
            @SuppressLint("NewApi")
            StorageVolume::class.java.getMethod("getDescription",
                Context::class.java)
        }
    }
}