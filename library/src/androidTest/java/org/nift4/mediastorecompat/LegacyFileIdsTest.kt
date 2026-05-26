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
@SdkSuppress(maxSdkVersion = 28) // Q added ModernMediaScanner which behaves differently
@RunWith(TestParameterInjector::class)
class LegacyFileIdsTest(
    @param:TestParameter
    private val isSd: Boolean
) : SecondaryStoragePreparer(isSd) {
    private val server by lazy { ShellServer.start() }

    @TestParameter("docx", "mp3", "mp4", "txt", "pls", "m3u", "jpg", "wpl", "xspf")
    private var ext: String = "null"
    
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
        val invalidIsFile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && ext != "pls" && ext != "m3u" && ext != "wpl"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val type = if (invalidIsFile) "file" else when (ext) {
            "mp3" -> "audio"
            "pls", "m3u", "wpl" -> "audio/playlists"
            "docx", "txt", "xspf" -> "file"
            "mp4" -> "video"
            "jpg" -> "images/media"
            else -> throw IllegalArgumentException(ext)
        }
        if (ext == "pls" || ext == "m3u" || ext == "wpl")
            grantStoragePermission()
        val mediaUri = scanFile(context, "${getDir()}/hello.$ext")
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/external/$type/")
        if (ext != "pls" && ext != "m3u" && ext != "wpl")
            grantStoragePermission()
        context.contentResolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA),
            null, null, null).use {
            assertThat(it).isNotNull()
            assertThat(it!!.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo(getDir()
                .resolve("hello.$ext").absolutePath)
        }
        assertThat(mediaUri).isEqualTo(MediaStoreCompat.getMediaUriForFile(context,
            "${getDir()}/HELLO.$ext"))
        assertThat(mediaUri).isEqualTo(scanFile(context, "${getDir()}/HELLO.$ext"))
        executeShellCommand("rm ${getDir()}/hello.$ext")
        if (ext != "pls" && ext != "m3u" && ext != "wpl") {
            // also, test it disappears after being deleted and then scanned
            assertThat(scanFile(context, "${getDir()}/hello.$ext")).isNull()
            context.contentResolver.query(
                mediaUri, arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            ).use {
                assertThat(it).isNotNull()
                assertThat(it!!.moveToFirst()).isFalse()
            }
        } else {
            // playlists do not disappear because the user may have modified the db version
            assertThat(scanFile(context, "${getDir()}/hello.$ext"))
                .isEqualTo(mediaUri)
        }
    }

    @Test
    fun testHiddenFilesHaveId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (ext == "pls" || ext == "m3u" || ext == "wpl")
            grantStoragePermission()
        val mediaUri = scanFile(context, "${getDir()}/.hello.$ext")
        assertThat(mediaUri).isNotNull()
        // Playlists cannot be hidden before Q
        assertThat(mediaUri!!.toString()).startsWith(
            if (ext == "pls" || ext == "m3u" || ext == "wpl")
                "content://media/external/audio/playlists/" else "content://media/external/file/")
        if (ext != "pls" && ext != "m3u" && ext != "wpl") {
            grantStoragePermission()
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
        } else {
            context.contentResolver.query(
                mediaUri, arrayOf(MediaStore.MediaColumns.DATA),
                null, null, null
            ).use {
                assertThat(it).isNotNull()
                assertThat(it!!.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo(
                    getDir()
                        .resolve(".hello.$ext").absolutePath
                )
            }
        }
    }

    @Test
    fun testNomediaFilesHaveId() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (ext == "pls" || ext == "m3u" || ext == "wpl")
            grantStoragePermission()
        val mediaUri = scanFile(context, "${getDir()}/Folder/hello.$ext")
        assertThat(mediaUri).isNotNull()
        assertThat(mediaUri!!.toString()).startsWith("content://media/external/file/")
        if (ext != "pls" && ext != "m3u" && ext != "wpl")
            grantStoragePermission()
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
