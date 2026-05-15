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
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SuppressLint("SdCardPath", "InlinedApi")
@RunWith(TestParameterInjector::class)
class FolderIdTest(
    @param:TestParameter
    private val isSd: Boolean
) : SecondaryStoragePreparer(isSd) {

    private fun getDir(): File {
        return (if (isSd) getSdCard() else getInternalStorage()).requireCanonicalDirectory()
    }

    private fun getMsv(): String {
        return (if (isSd) getSdCard() else getInternalStorage()).mediaStoreVolumeName!!
    }

    @Before
    fun prepare() {
        executeShellCommand("mkdir ${getDir()}/Folder")
        executeShellCommand("touch ${getDir()}/Folder/.nomedia")
        executeShellCommand("mkdir ${getDir()}/Folder2")
    }

    @After
    fun cleanUp() {
        executeShellCommand("rmdir ${getDir()}/Folder2")
        executeShellCommand("rm ${getDir()}/Folder/.nomedia")
        executeShellCommand("rmdir ${getDir()}/Folder")
    }

    @Test
    fun testFoldersHaveId() {
        // == Folder (nomedia) ==
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaUri = scanFile(context, "${getDir()}/Folder/")
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/${getMsv()}/file/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gainAccessToTokenHappyPath(context, MediaStoreCompat.RequestToken.Manager)
        } else {
            grantStoragePermission()
        }
        context.contentResolver.query(mediaUri, arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE),
            null, null, null).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(getDir()
                .resolve("Folder").absolutePath)
            assertThat(it.getInt(1)).isEqualTo(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
        }
        // == Folder2 (media) ==
        val mediaUri2 = scanFile(context, "${getDir()}/Folder2/")
        assertThat(mediaUri2).isNotNull()
        assertThat(mediaUri2!!.toString()).startsWith("content://media/${getMsv()}/file/")
        context.contentResolver.query(mediaUri2, arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE),
            null, null, null).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(getDir()
                .resolve("Folder2").absolutePath)
            assertThat(it.getInt(1)).isEqualTo(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
        }
    }
}
