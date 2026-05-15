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
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class CreateInternalTest : TestBase() {
    // TODO: expand this test a lot
    @SdkSuppress(maxSdkVersion = 22) // Runtime permissions don't exist
    @Test
    fun createFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(
            MediaStoreCompat.needRequestCreate(
                context,
                getInternalStorage(),
                "Music"
            )
        ).isNull()
        val uri = MediaStoreCompat.create(
            context, getInternalStorage(), "Music/test.mp3"
        )
        assertThat(uri).isNotNull()
        MediaStoreCompat.openOutputStream(context, uri!!).use {
            it!!.writer().use { writer ->
                writer.write("hello world")
            }
        }
        MediaStoreCompat.finishCreate(context, uri)
        assertThat(
            executeShellCommand("cat /sdcard/Music/test.mp3")
        ).isEqualTo("hello world")
    }

    @SdkSuppress(minSdkVersion = 23, maxSdkVersion = 28) // min: runtime perm, max: before scoped
    @Test
    fun createFileSinceMBeforeQ() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertThat(ContextCompat.checkSelfPermission(context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE)).isEqualTo(
            PackageManager.PERMISSION_DENIED)
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.create(
                context, getInternalStorage(), "Music/test.mp3"
            )
        }
        val token = MediaStoreCompat.needRequestCreate(context,
            getInternalStorage(),
            "Music")
        assertThat(token).isNotNull()
        assertThat(token!!.requestManager).isTrue()
        assertThat(token.uri).isNull()
        assertThrows(SecurityException::class.java) {
            MediaStoreCompat.create(
                context, getInternalStorage(), "Music/test.mp3"
            )
        }
        grantStoragePermission()
        createFile()
    }

    @After
    fun cleanUp() {
        executeShellCommand("rm /sdcard/Music/test.mp3")
    }
}