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

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.getSystemService
import java.io.File

/**
 * Backport of storage volume functionality of [StorageManager], and some APIs for interacting
 * with ExternalStorageProvider.
 */
object StorageManagerCompat {

    const val AUTHORITY_EXTERNAL_STORAGE = "com.android.externalstorage.documents"
    internal const val EXTERNAL_STORAGE_VOLUME_PRIMARY = "primary"

    @JvmStatic fun getStorageVolumes(context: Context): List<StorageVolumeCompat> {
        val storageManager = context.getSystemService<StorageManager>()!!
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager.storageVolumes.map { StorageVolumeCompat(it) }
        } else {
            @Suppress("UNCHECKED_CAST")
            (getVolumeList.invoke(storageManager)!! as Array<StorageVolume>)
                .map { StorageVolumeCompat(it) }
        }
    }

    @JvmStatic fun buildExternalStorageDocumentId(volumeName: String, path: String): String {
        return "$volumeName:$path"
    }

    @JvmStatic fun buildExternalStorageDocumentId(volume: StorageVolumeCompat, path: String): String {
        return buildExternalStorageDocumentId(volume.documentsProviderVolumeName, path)
    }

    @JvmStatic fun buildExternalStorageDocumentId(volume: StorageVolumeCompat, path: File): String {
        return buildExternalStorageDocumentId(volume.documentsProviderVolumeName, path.path)
    }

    @JvmStatic fun getExternalStorageVolumeName(documentId: String): String {
        return documentId.substringBefore(':')
    }

    @JvmStatic fun getExternalStoragePath(documentId: String): String {
        return documentId.substringAfter(':', "")
    }

    @JvmStatic fun getVolumeForPath(volumes: List<StorageVolumeCompat>, path: File): StorageVolumeCompat {
        return volumes.find {
            isVolumeForPath(it, path) == true
        } ?: throw IllegalArgumentException("There is no volume for path: $path in: $volumes")
    }

    @JvmStatic fun isVolumeForPath(volume: StorageVolumeCompat, path: File): Boolean? {
        return volume.canonicalDirectory?.let { dir ->
            var dirPath = dir.absolutePath
            val filePath = path.absolutePath
            if (dirPath == filePath) {
                return@let true
            }
            if (!dirPath.endsWith("/")) {
                dirPath += "/"
            }
            return@let filePath.startsWith(dirPath)
        }
    }

    private val getVolumeList by lazy {
        StorageManager::class.java.getMethod("getVolumeList")
    }
}