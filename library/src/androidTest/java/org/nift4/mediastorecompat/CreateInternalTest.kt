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
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class CreateInternalTest : TestBase() {
    @TestParameter(/*"docx", "mp3", "mp4", "txt",*/ "pls", "m3u", "jpg", "wpl", "xspf")
    private var ext: String = "null"

    @TestParameter("Music", "DCIM", "Pictures", "Playlists", "Download", "Documents",
        "Movies", "Podcasts", "Customfolder123")
    private var folder: String = "null"

    @TestParameter("true", "false")
    private var withPermission = false

    private fun isValid(withPermission: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return true
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && withPermission)
            return true
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            if (ext == "pls" || ext == "wpl" || ext == "m3u") return false
        }
        if (folder == "Documents" || folder == "Download") {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R
                && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) < 2) {
                return !(ext == "pls" || ext == "wpl" || ext == "m3u" || ext == "srt" ||
                        ext == "ttml" || ext == "lrc" || ext == "xspf")
            }
            return true
        }
        if (ext == "mp3" && folder == "Music") return true
        if (ext == "mp3" && folder == "Podcasts") return true
        if (ext == "mp4" && folder == "Movies") return true
        if (ext == "mp4" && folder == "DCIM") return true
        if (ext == "jpg" && folder == "DCIM") return true
        if (ext == "jpg" && folder == "Pictures") return true
        if (ext == "pls" && folder == "Music") return true
        if (ext == "wpl" && folder == "Music") return true
        if (ext == "m3u" && folder == "Music") return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ext == "xspf" && folder == "Music") return true
            if (ext == "xspf" && folder == "Movies") return true
            if (ext == "pls" && folder == "Movies") return true
            if (ext == "wpl" && folder == "Movies") return true
            if (ext == "m3u" && folder == "Movies") return true
        }
        return false
    }

    @Test
    fun createFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scanFile(context, getInternalStorage().requireCanonicalDirectory()
            .resolve("$folder/test.$ext").absolutePath)
        scanFile(context, getInternalStorage().requireCanonicalDirectory()
            .resolve(folder).absolutePath)
        if (withPermission) {
            assume().that(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M).isTrue()
            assertThat(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            ).isEqualTo(
                PackageManager.PERMISSION_DENIED
            )
            if (!isValid(false)) {
                assertThrows(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    IllegalArgumentException::class.java else SecurityException::class.java) {
                    MediaStoreCompat.create(
                        context, "$folder/test.$ext", getInternalStorage()
                    )
                }
                val token = MediaStoreCompat.needRequestCreate(
                    context,
                    "$folder/test.$ext", getInternalStorage()
                )
                assertThat(token).isNotNull()
                assertThat(token!!.requestManager).isTrue()
                assertThat(token.uri).isNull()
                assertThrows(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    IllegalArgumentException::class.java else SecurityException::class.java) {
                    MediaStoreCompat.create(
                        context, "$folder/test.$ext", getInternalStorage()
                    )
                }
            }
            grantStoragePermission()
        }
        if (!isValid(withPermission)) {
            val token = MediaStoreCompat.needRequestCreate(
                context,
                "$folder/test.$ext", getInternalStorage()
            )
            assertThat(token).isNotNull()
            assertThat(token!!.requestManager).isTrue()
            assertThat(token.uri).isNull()
            assertThrows(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                IllegalArgumentException::class.java else SecurityException::class.java) {
                MediaStoreCompat.create(
                    context, "$folder/test.$ext", getInternalStorage()
                )
            }
            return
        }
        assertThat(
            MediaStoreCompat.needRequestCreate(
                context,
                "$folder/test.$ext", getInternalStorage(),
            )
        ).isNull()
        val uri = MediaStoreCompat.create(
            context, "$folder/test.$ext", getInternalStorage()
        )
        assertThat(uri).isNotNull()
        MediaStoreCompat.openOutputStream(context, uri!!).use {
            it!!.writer().use { writer ->
                writer.write("hello world")
            }
        }
        MediaStoreCompat.finishCreate(context, uri)
        assertThat(
            executeShellCommand("cat ${getInternalPath()}/$folder/test.$ext")
        ).isEqualTo("hello world")
    }

    @After
    fun cleanUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = MediaStoreCompat.scanFile(context, "${getInternalPath()}/$folder/test.$ext")
        executeShellCommand("rm ${getInternalPath()}/$folder/test.$ext")
        if (uri != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            MediaStoreCompat.delete(context, uri)
        if (folder == "Customfolder123")
            executeShellCommand("rmdir ${getInternalPath()}/$folder")
        MediaStoreCompat.scanFile(context, "${getInternalPath()}/$folder/test.$ext")
        MediaStoreCompat.scanFile(context, "${getInternalPath()}/$folder")
    }
}