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

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertAbout
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nift4.mediastorecompat.TestBase.UriSubject.Companion.uris

@SdkSuppress(maxSdkVersion = 30)
@RunWith(TestParameterInjector::class)
class SafOnlyWriteRequestTest : SecondaryStoragePreparer(true) {

    // TODO: on sdk 30, check internal storage and bug 258270138 workaround

    @Before
    fun prepare() {
        executeShellCommand("mkdir ${getSdPath()}/Irrelevant")
        executeShellCommand("mkdir ${getSdPath()}/Target1")
        executeShellCommand("mkdir ${getSdPath()}/Target2")
    }

    @After
    fun cleanUp() {
        executeShellCommand("rmdir ${getSdPath()}/Irrelevant")
        executeShellCommand("rmdir ${getSdPath()}/Target1")
        executeShellCommand("rmdir ${getSdPath()}/Target2")
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testRequestAllowYesno() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowDocsUiThenRevokePersisted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantStoragePermission()
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
        context.contentResolver.releasePersistableUriPermission(
            DocumentsContract.buildTreeDocumentUri(
                StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                getSafIdOnSd("")
            ),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowDocsUiThenUseThenRevokePersisted(
        @TestParameter("Full", "OnlyPersistedOrDirect", "OnlyPersisted",
            "OnlyPersistedTree") resolvePermissions: MediaStoreCompat.ResolvePermissions
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        grantStoragePermission()
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
        assertThat(MediaStoreCompat.getDocumentUriEx(
            context, null, getSdCard().canonicalDirectory,
            resolvePermissions = resolvePermissions))
            .isEqualTo(getSafUriOnSd("", ""))
        context.contentResolver.releasePersistableUriPermission(
            DocumentsContract.buildTreeDocumentUri(
                StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                getSafIdOnSd("")
            ),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Because we used getDocumentUriEx() which uses checkGrantSelfUriPermission
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowIrrelevantThenRealDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowIrrelevant4ThenRealDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestPartialAllowFullDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), false),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestPartialAllowIrrelevantThenFullDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), false),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestPartialAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), false),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Target1"))
            selectSafFolderWithUiAutomator(listOf("Target2"))
        }
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestPartialAllowIrrelevantThenRealDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), false),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Target1"))
            selectSafFolderWithUiAutomator(listOf("Target2"))
        }
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestPartialAllowHalfDenyThenAllowDocsUi(@TestParameter tokenReuse: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), false),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Target1"))
            selectSafFolderWithUiAutomator(null)
        }
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, if (tokenReuse) listOf(
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target1"),
                false), MediaStoreCompat.RequestToken.Uri(
                getSafIdOnSd("Target2"), false)) else listOf(
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                false)), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Target2"))
        }
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isReadableAndWritableFrom(context)
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testRequestAllowYesnoAfterRejection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @Test
    fun testRequestAllowDocsUiAfterRejection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(null)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testRequestAllowDocsUiAfterPermaRejection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_CANCELED) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(null)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            selectSafFolderWithUiAutomator(emptyList())
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
    }
}