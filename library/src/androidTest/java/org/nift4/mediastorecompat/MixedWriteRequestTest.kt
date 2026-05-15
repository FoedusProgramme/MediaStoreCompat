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
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertAbout
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.nift4.mediastorecompat.TestBase.UriSubject.Companion.uris

/**
 * The write request logic doesn't change much based on which action the user requests. So the
 * individual action tests check that correct tokens are generated, it doesn't work without
 * permission, and does work after getting access as per that token in a happy path; while this
 * checks all the unhappy paths related to permissions, too.
 */
@SdkSuppress(minSdkVersion = 23, maxSdkVersion = 29)
@RunWith(TestParameterInjector::class)
class MixedWriteRequestTest : SecondaryStoragePreparer(true) {

    @TestParameter("0", "1", "2", "3", "4")
    private var permRejections = 0

    private fun testManagerAnd(context: Context, tokens: List<MediaStoreCompat.RequestToken>,
                               uriAsserts: List<UriSubject>, result: Int, next: () -> Unit) {
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
        if (uriAsserts.size == 1) {
            uriAsserts.forEach { it.isNotReadableAndWritableFrom(context) }
        }
        if (permRejections == 0) {
            grantStoragePermission()
            assertResultOfWriteRequest(context, tokens, result) {
                next()
            }
        }
        for (perm in 0..<permRejections) {
            val allow = perm == permRejections - 1
            val result = if (allow) result else Activity.RESULT_CANCELED
            if (perm == 0) {
                if (allow) {
                    assertResultOfWriteRequest(context, tokens, result) {
                        answerPermDialogWithUiAutomator(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            true
                        )
                        next()
                    }
                } else {
                    assertResultOfWriteRequest(context, tokens, result) {
                        answerPermDialogWithUiAutomator(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            false
                        )
                    }
                }
            }
            if (perm == 1) {
                if (allow) {
                    assertResultOfWriteRequest(context, tokens, result) {
                        answerPermDialogWithUiAutomator(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            true
                        )
                        next()
                    }
                } else {
                    assertResultOfWriteRequest(context, tokens, result) {
                        answerPermDialogWithUiAutomator(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            null
                        )
                    }
                }
            }
            if (perm == 2 || perm == 3) {
                assertResultOfWriteRequest(context, tokens, result) {
                    answerPermSettingsWithUiAutomator(allow)
                    if (allow) {
                        next()
                    }
                }
            }
            if (!allow) {
                assertSelfPermissionGranted(context,
                    Manifest.permission.READ_EXTERNAL_STORAGE).isFalse()
                assertSelfPermissionGranted(context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE).isFalse()
            }
            if (result != Activity.RESULT_OK && uriAsserts.size == 1) {
                uriAsserts.forEach { it.isNotReadableAndWritableFrom(context) }
            }
        }
        assertSelfPermissionGranted(context,
            Manifest.permission.READ_EXTERNAL_STORAGE).isTrue()
        assertSelfPermissionGranted(context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE).isTrue()
        if (result == Activity.RESULT_OK) {
            uriAsserts.forEach { it.isReadableAndWritableFrom(context) }
        }
    }

    private fun testManagerAnd(context: Context, result: Int, next: () -> Unit) {
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""), true)), listOf(assertAbout(uris())
                .that(getSafUriOnSd("", ""))), result, next)
    }

    private fun assertResultOfWriteRequest(context: Context, desiredResult: Int, callback: () -> Unit) {
        val token = MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false)
        assertResultOfWriteRequest(context, token, desiredResult, callback)
    }

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
    fun testAllowYesno() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
    }

    @Test
    fun testAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testAllowDocsUiIrrelevantThenReal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testAllowDocsUiIrrelevant7ThenReal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testStDenyIrrelevantAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testRequestPartialAllowFullDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isNotReadableAndWritableFrom(context)
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), true),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)), listOf(assertAbout(uris())
                    .that(getSafUriOnSd("Target1", "")), assertAbout(uris())
                        .that(getSafUriOnSd("Target2", ""))),
            Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testRequestPartialAllowIrrelevantThenFullDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", ""))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", ""))
            .isNotReadableAndWritableFrom(context)
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), true),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)), listOf(assertAbout(uris())
            .that(getSafUriOnSd("Target1", "")), assertAbout(uris())
            .that(getSafUriOnSd("Target2", ""))), Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testRequestPartialAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), true),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)), listOf(assertAbout(uris())
            .that(getSafUriOnSd("Target1", "Target1")), assertAbout(uris())
            .that(getSafUriOnSd("Target2", "Target2"))),
            Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Target1"))
            selectSafFolderWithUiAutomator(listOf("Target2"))
        }
    }

    @Test
    fun testRequestPartialAllowIrrelevantThenRealDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), true),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)), listOf(assertAbout(uris())
            .that(getSafUriOnSd("Target1", "Target1")), assertAbout(uris())
            .that(getSafUriOnSd("Target2", "Target2"))),
            Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Irrelevant"))
            selectSafFolderWithUiAutomator(listOf("Target1"))
            selectSafFolderWithUiAutomator(listOf("Target2"))
        }
    }

    @Test
    fun testRequestPartialStAllowHalfDenyThenAllowDocsUi(@TestParameter tokenReuse: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertAbout(uris()).that(getSafUriOnSd("Target1", "Target1"))
            .isNotReadableAndWritableFrom(context)
        assertAbout(uris()).that(getSafUriOnSd("Target2", "Target2"))
            .isNotReadableAndWritableFrom(context)
        testManagerAnd(context, listOf(MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd("Target1"), true),
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)),  listOf(assertAbout(uris())
            .that(getSafUriOnSd("Target1", "Target1")), assertAbout(uris())
            .that(getSafUriOnSd("Target2", "Target2"))),
            Activity.RESULT_CANCELED) {
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
        testManagerAnd(context, if (tokenReuse) listOf(
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target1"),
                true), MediaStoreCompat.RequestToken.Uri(
                getSafIdOnSd("Target2"), true)) else listOf(
            MediaStoreCompat.RequestToken.Uri(getSafIdOnSd("Target2"),
                true)),  listOf(assertAbout(uris())
            .that(getSafUriOnSd("Target1", "Target1")), assertAbout(uris())
            .that(getSafUriOnSd("Target2", "Target2"))),
            Activity.RESULT_OK) {
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

    @Test
    fun testStDenyAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @Test
    fun testDenyAllowStDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(null)
        }
        testManagerAnd(context, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(false)
            }
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testStDenyAllowYesno() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testDenyAllowStYesno() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        testManagerAnd(context, Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testDenyStDenyAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        testManagerAnd(context, Activity.RESULT_OK) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testStDenyDenyAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testStDenyDenyDenyDenyAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testDenyDenyDenyDenyStAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(null)
        }
        testManagerAnd(context, Activity.RESULT_OK) {
            selectSafFolderWithUiAutomator(emptyList())
        }
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testDenyDenyStDenyDenyAllowDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertResultOfWriteRequest(context, Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        testManagerAnd(context, Activity.RESULT_CANCELED) {
            dontAskAgainYesNoDialogWithUiAutomator()
            selectSafFolderWithUiAutomator(null)
        }
        assertResultOfWriteRequest(context, Activity.RESULT_OK) {
            selectSafFolderWithUiAutomator(emptyList())
        }
    }
}