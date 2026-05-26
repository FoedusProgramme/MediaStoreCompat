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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.shell.Shell
import androidx.test.shell.ShellServer
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@SuppressLint("SdCardPath", "InlinedApi")
@SdkSuppress(minSdkVersion = 29) // Q added ModernMediaScanner which behaves differently
@RunWith(TestParameterInjector::class)
// In a lot of methods, Q is the only outlier. That is indeed because Q doesn't scan hidden/nomedia/
// some invalid files and refuses to give out IDs, even removing them from the db if they have an ID
// at the moment. (We can still see them with File API). Meanwhile, Android R requires the manager
// permission to even see them, but they _are_ in the database.
class ModernFileIdsTest(
    @param:TestParameter
    private val isSd: Boolean
) : SecondaryStoragePreparer(isSd) {
    private val server by lazy { ShellServer.start() }

    @TestParameter("docx", "mp3", "mp4", "txt", "pls", "m3u", "jpg", "wpl", "xspf")
    private var ext: String = "null"

    private fun getMsv(): String {
        return (if (isSd) getSdCard() else getInternalStorage()).mediaStoreVolumeName!!
    }
    
    private fun getDir(): File {
        return (if (isSd) getSdCard() else getInternalStorage()).requireCanonicalDirectory()
    }

    @Before
    fun prepare() {
        Shell.setShellServer(server)
        executeShellCommand("mkdir ${getDir()}/Folder")
        executeShellCommand("touch ${getDir()}/Folder/.nomedia")
        executeShellCommand("touch ${getDir()}/Folder/hello.$ext")
        executeShellCommand("touch ${getDir()}/hello.$ext")
        Shell.command("echo 123 > ${getDir()}/hello.$ext").waitForCompletion()
        assertThat(executeShellCommand("cat ${getDir()}/hello.$ext")).isEqualTo("123\n")
        executeShellCommand("touch ${getDir()}/.hello.$ext")
    }

    @After
    fun cleanUp() {
        executeShellCommand("rm ${getDir()}/hello.$ext")
        executeShellCommand("rm ${getDir()}/.hello.$ext")
        executeShellCommand("rm ${getDir()}/Folder/hello.$ext")
        executeShellCommand("rm ${getDir()}/Folder/.nomedia")
        executeShellCommand("rmdir ${getDir()}/Folder")
        server.close()
    }

    @Test
    fun testInvalidFilesHaveId() {
        // https://cs.android.com/android/_/android/platform/frameworks/base/+/bab75909fac9c65b71012f4c06302503c2f72be1
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val type = when (ext) {
            "xspf" if Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> "file"
            "mp3" -> "audio"
            "pls", "m3u", "wpl", "xspf" -> "audio/playlists"
            "docx", "txt" -> "file"
            "mp4" -> "video"
            "jpg" -> "images/media"
            else -> throw IllegalArgumentException(ext)
        }
        var mediaUri = scanFile(context, "${getDir()}/hello.$ext")
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q &&
            (type == "audio" || type == "video")) {
            assertThat(mediaUri).isNull()
            grantStoragePermission()
            mediaUri = MediaStoreCompat.getMediaUriForFile(context, "${getDir()}/hello.$ext")
        }
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/${getMsv()}/$type/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gainAccessToTokenHappyPath(context, MediaStoreCompat.RequestToken.Manager)
        } else if (type != "audio" && type != "video") {
            grantStoragePermission()
        }
        assertThat(mediaUri).isEqualTo(MediaStoreCompat.getMediaUriForFile(context,
            "${getDir()}/HELLO.$ext"))
        assertThat(mediaUri).isEqualTo(scanFile(context, "${getDir()}/HELLO.$ext"))
        context.contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(getDir()
                .resolve("hello.$ext").absolutePath)
        }
        executeShellCommand("rm ${getDir()}/hello.$ext")
        // also, test it disappears after being deleted and then scanned
        assertThat(scanFile(context, "${getDir()}/hello.$ext")).isNull()
        context.contentResolver.query(
            mediaUri, arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null
        ).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isFalse()
        }
    }

    @Test
    fun testHiddenFilesHaveId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var mediaUri = scanFile(context, "${getDir()}/.hello.$ext")
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            assertThat(mediaUri).isNull()
            grantStoragePermission()
            mediaUri = MediaStoreCompat.getMediaUriForFile(context, "${getDir()}/.hello.$ext")
        }
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/${getMsv()}/file/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gainAccessToTokenHappyPath(context, MediaStoreCompat.RequestToken.Manager)
        }
        context.contentResolver.query(
            mediaUri, arrayOf(
                MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE
            ),
            null, null, null
        ).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(
                getDir()
                    .resolve(".hello.$ext").absolutePath
            )
            assertThat(it.getInt(1)).isEqualTo(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
            )
        }
    }

    @Test
    fun testNomediaFilesHaveId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var mediaUri = scanFile(context, "${getDir()}/Folder/hello.$ext")
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            assertThat(mediaUri).isNull()
            grantStoragePermission()
            mediaUri = MediaStoreCompat.getMediaUriForFile(context,
                "${getDir()}/Folder/hello.$ext")
        }
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/${getMsv()}/file/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gainAccessToTokenHappyPath(context, MediaStoreCompat.RequestToken.Manager)
        }
        context.contentResolver.query(mediaUri, arrayOf(
            MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE),
            null, null, null).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(getDir()
                .resolve("Folder/hello.$ext").absolutePath)
            assertThat(it.getInt(1)).isEqualTo(
                MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
        }
    }
}
