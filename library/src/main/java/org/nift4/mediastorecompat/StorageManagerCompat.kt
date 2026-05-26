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

    // TODO: consider supporting /sdcard or /storage/self/primary or other weird paths
    @JvmStatic fun isVolumeForPath(volume: StorageVolumeCompat, path: File): Boolean? {
        return volume.canonicalDirectory?.let { dir ->
            var dirPath = dir.absolutePath
            val filePath = path.absolutePath
            if (dirPath.equals(filePath, ignoreCase = true)) {
                return@let true
            }
            if (!dirPath.endsWith("/")) {
                dirPath += "/"
            }
            return@let filePath.startsWith(dirPath, ignoreCase = true)
        }
    }

    private val getVolumeList by lazy {
        StorageManager::class.java.getMethod("getVolumeList")
    }
}

/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */

private fun File.toComponents(): List<String> {
    val subPath = path.substring(1)
    return if (subPath.isEmpty()) listOf() else subPath.split('/')
}

private fun File.toRelativeStringCaseInsensitive(base: File): String {
    // Check roots
    if (!isAbsolute) {
        throw IllegalStateException("$this is not an absolute path, can't relativize to $base")
    }
    if (!base.isAbsolute) {
        throw IllegalStateException("$base is not an absolute path, can't relativize $this")
    }
    val thisComponents = this.normalize().toComponents()
    val baseComponents = base.normalize().toComponents()

    val baseCount = baseComponents.size
    val thisCount = thisComponents.size

    val sameCount = run countSame@{
        var i = 0
        val maxSameCount = minOf(thisCount, baseCount)
        while (i < maxSameCount && thisComponents[i].equals(baseComponents[i], ignoreCase = true))
            i++
        return@countSame i
    }

    // Annihilate differing base components by adding required number of .. parts
    val res = StringBuilder()
    for (i in baseCount - 1 downTo sameCount) {
        if (baseComponents[i] == "..") {
            throw IllegalStateException("Can't escape root while relativizing $this to $base")
        }

        res.append("..")

        if (i != sameCount) {
            res.append(File.separatorChar)
        }
    }

    // Add remaining this components
    if (sameCount < thisCount) {
        // If some .. were appended
        if (sameCount < baseCount)
            res.append(File.separatorChar)

        thisComponents.drop(sameCount).joinTo(res, File.separator)
    }

    return res.toString()
}

// (Kotlin code end)

// TODO: consider supporting /sdcard or /storage/self/primary or other weird paths

fun File.toRelativeString(volumeCompat: StorageVolumeCompat) =
    toRelativeStringCaseInsensitive(volumeCompat.requireCanonicalDirectory()).also {
        if (it.startsWith("../")) {
            throw IllegalArgumentException("$this not inside $volumeCompat")
        }
    }

fun File.relativeTo(volumeCompat: StorageVolumeCompat) =
    File(toRelativeString(volumeCompat))

fun File.assertVolumeIs(volumeCompat: StorageVolumeCompat) {
    toRelativeString(volumeCompat)
}