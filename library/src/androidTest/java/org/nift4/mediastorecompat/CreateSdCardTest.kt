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

import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class CreateSdCardTest : SecondaryStoragePreparer(true) {
    // TODO: expand this test a lot
    @SdkSuppress(maxSdkVersion = 28)
    @Test
    fun createFileBeforeQ() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var token = MediaStoreCompat.needRequestCreate(
            context,
            getSdCard(),
            "Music/test.mp3", "audio/mpeg",
            false
        )
        assertThat(token).isNotNull()
        assertThat(token!!.requestManager).isTrue()
        assertThat(token.uri).isEqualTo(getSafIdOnSd(""))
        // first, assert that our early check catches this issue
        assertThat(assertThrows(SecurityException::class.java) {
            MediaStoreCompat.create(
                context, getSdCard(),
                "Music/test.mp3", "audio/mpeg",
            )
        }).hasMessageThat().isEqualTo(
            "WRITE_EXTERNAL_STORAGE has to be granted for " +
                    "Android 9 and earlier to create files"
        )
        // then, ensure system actually doesn't let us do this
        val spy = spyContextWithStorageGranted(context)
        assertThat(assertThrows(SecurityException::class.java) {
            MediaStoreCompat.mediaProviderSdCachedValue = true // avoid poisoning cache due to spy
            MediaStoreCompat.create(
                spy, getSdCard(),
                "Music/test.mp3", "audio/mpeg",
            )
        }).hasMessageThat().also {
            it.endsWith(
                "requires android.permission.WRITE_EXTERNAL_STORAGE, or" +
                        " grantUriPermission()"
            )
            it.startsWith(
                "Permission Denial: writing com.android.providers.media.MediaProvider " +
                        "uri content://media/external/file from "
            )
        }
        MediaStoreCompat.mediaProviderSdCachedValue = null
        grantStoragePermission()
        token = MediaStoreCompat.needRequestCreate(
            context,
            getSdCard(),
            "Music/test.mp3", "audio/mpeg",
            false
        )
        assertThat(token).isNotNull()
        assertThat(token!!.requestManager).isFalse()
        assertThat(token.uri).isEqualTo(getSafIdOnSd(""))
        // ensure that granting storage permission didn't fix it
        assertThat(assertThrows(SecurityException::class.java) {
            MediaStoreCompat.create(
                context, getSdCard(),
                "Music/test.mp3", "audio/mpeg",
            )
        }).hasMessageThat().isEqualTo(
            "no permission to create " +
                    "${getSdPath()}/Music/test.mp3"
        )
        gainAccessToTokenHappyPath(context, listOf(token))
        // now that we finally have permission it really should work
        token = MediaStoreCompat.needRequestCreate(context,
            getSdCard(),
            "Music")
        assertThat(token).isNull()
        val uri = MediaStoreCompat.create(
            context, getSdCard(), "Music/folder123/test.mp3",
            "audio/mpeg"
        )
        assertThat(uri).isNotNull()
        MediaStoreCompat.openOutputStream(context, uri!!).use {
            it!!.writer().use { writer ->
                writer.write("hello world")
            }
        }
        MediaStoreCompat.finishCreate(context, uri)
        assertThat(
           executeShellCommand("cat ${getSdPath()}/Music/folder123/test.mp3")
        ).isEqualTo("\"hello world\"")
    }

    @After
    fun cleanUp() {
        executeShellCommand("rm ${getSdPath()}/Music/folder123/test.mp3")
    }
}