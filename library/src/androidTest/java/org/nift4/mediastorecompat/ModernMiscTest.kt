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
import android.app.Activity
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Write request grant is, for all possible file types, tested in the action-specific tests.
 * We just need to cover denial.
 */
@SuppressLint("SdCardPath", "InlinedApi")
@SdkSuppress(minSdkVersion = 30)
@RunWith(TestParameterInjector::class)
class ModernMiscTest : TestBase() {
    @Before
    fun prepare() {
        executeShellCommand("touch /sdcard/Music/hello.mp3")
        executeShellCommand("touch /sdcard/Music/hello.docx")
        executeShellCommand("mkdir /sdcard/Folder")
    }

    @After
    fun cleanUp() {
        executeShellCommand("rm /sdcard/Music/hello.mp3")
        executeShellCommand("rm /sdcard/Music/hello.docx")
        executeShellCommand("rm /sdcard/Download/Abcdefg.txt")
        executeShellCommand("rm /sdcard/Folder/Abcdefg.txt")
        executeShellCommand("rmdir /sdcard/Download/SoonTopLevel")
        executeShellCommand("rmdir /sdcard/TopLevel")
        executeShellCommand("rmdir /sdcard/Folder")
        executeShellCommand("rmdir /sdcard/Android/Abcdefg")
    }

    @Test
    fun testDenyingWriteRequest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaUri = scanFile(context, "/sdcard/Music/hello.mp3")
        assertThat(mediaUri!!).isNotNull()
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
        grantMediaOrStoragePermission(Manifest.permission.READ_MEDIA_AUDIO)
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
        var token = MediaStoreCompat.needRequestBytesWrite(context, mediaUri)
        assertThat(token).isNotNull()
        assertThat(token!!.requestManager).isFalse()
        assertThat(token.uri).isEqualTo(mediaUri.toString())
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
        assertResultOfWriteRequest(context, token, Activity.RESULT_CANCELED) {
            answerWriteRequest(false)
        }
        token = MediaStoreCompat.needRequestBytesWrite(context, mediaUri)
        assertThat(token).isNotNull()
        assertThat(token!!.requestManager).isFalse()
        assertThat(token.uri).isEqualTo(mediaUri.toString())
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
    }

    @Test
    fun testDenyingManagerRequest(@TestParameter perm: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (perm) {
            grantMediaOrStoragePermission(Manifest.permission.READ_MEDIA_AUDIO)
        }
        val mediaUri = scanFile(context, "/sdcard/Music/hello.docx")
        assertThat(mediaUri!!).isNotNull()
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            mediaUri.toString(), true), Activity.RESULT_CANCELED) {
            answerManagerRequest(false)
        }
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.openOutputStream(
                context, mediaUri
            )
        }
    }

    @Test
    fun cannotCreateTopLevelFileByFuse() {
        assertThat(getInternalStorage().requireCanonicalDirectory().isDirectory).isTrue()
        val srcAndroid = getInternalStorage().requireCanonicalDirectory()
            .resolve("Download/Abcdefg.txt")
        val goalAndroid = getInternalStorage().requireCanonicalDirectory()
            .resolve("Abcdefg.txt")
        assertThat(srcAndroid.exists()).isFalse()
        assertThat(goalAndroid.exists()).isFalse()
        assertThrows(IOException::class.java) {
            goalAndroid.outputStream().close()
        }
        assertThat(goalAndroid.exists()).isFalse()
        srcAndroid.outputStream().close()
        assertThat(srcAndroid.exists()).isTrue()
        assertThat(srcAndroid.renameTo(goalAndroid)).isFalse()
        assertThat(srcAndroid.delete()).isTrue()
    }

    @Test
    fun canCreateFileInWrongTopLevelFolderByFuse() {
        // This is technically a bug in Android, finders keepers? :)
        assertThat(getInternalStorage().requireCanonicalDirectory().isDirectory).isTrue()
        val srcAndroid = getInternalStorage().requireCanonicalDirectory()
            .resolve("Download/Abcdefg.txt")
        val goalAndroid = getInternalStorage().requireCanonicalDirectory()
            .resolve("Folder/Abcdefg.txt")
        assertThat(getInternalStorage().requireCanonicalDirectory()
            .resolve("Folder").isDirectory).isTrue()
        assertThat(srcAndroid.exists()).isFalse()
        assertThat(goalAndroid.exists()).isFalse()
        assertThrows(IOException::class.java) {
            goalAndroid.outputStream().close()
        }
        assertThat(goalAndroid.exists()).isFalse()
        srcAndroid.outputStream().close()
        assertThat(srcAndroid.exists()).isTrue()
        assertThat(srcAndroid.renameTo(goalAndroid)).isTrue()
        assertThat(goalAndroid.delete()).isTrue()
    }

    @Test
    fun cannotCreateTopLevelFolderByFuse() {
        assertThat(getInternalStorage().requireCanonicalDirectory().isDirectory).isTrue()
        val recordings = getInternalStorage().requireCanonicalDirectory()
            .resolve("Abcdefg")
        val androidRecordings = getInternalStorage().requireCanonicalDirectory()
            .resolve("Android/Abcdefg")
        assertThat(recordings.isDirectory).isFalse()
        assertThat(recordings.mkdir()).isFalse()
        assertThat(androidRecordings.mkdir()).isTrue()
        assertThat(androidRecordings.renameTo(recordings)).isFalse()
        assertThat(recordings.isDirectory).isFalse()
        assertThat(androidRecordings.delete()).isTrue()
    }
}
