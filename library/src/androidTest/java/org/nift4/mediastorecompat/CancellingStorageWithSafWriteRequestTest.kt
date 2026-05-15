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
import org.junit.Test
import org.junit.runner.RunWith
import org.nift4.mediastorecompat.TestBase.UriSubject.Companion.uris

/** This test enforces that storage permission is invariant of any possible SAF state. */
@SdkSuppress(maxSdkVersion = 29)
@RunWith(TestParameterInjector::class)
class CancellingStorageWithSafWriteRequestTest : SecondaryStoragePreparer(true) {
    @TestParameter("1", "2", "3", "4")
    private var permRejections = 0

    @Test
    fun testCancelStorageAfterAllowingSafDocsUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
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
        tail(context)
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testCancelStorageAfterAllowingSafYesno() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_OK) {
            answerYesNoDialogWithUiAutomator(true)
        }
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isReadableAndWritableFrom(context)
        tail(context)
    }

    @Test
    fun testCancelStorageAfterRejectionOfSaf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
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
        tail(context)
    }

    @SdkSuppress(minSdkVersion = 24, maxSdkVersion = 28)
    @Test
    fun testCancelStorageAfterDualRejectionOfSaf() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
        assertAbout(uris()).that(getSafUriOnSd("", ""))
            .isNotReadableAndWritableFrom(context)
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            false), Activity.RESULT_CANCELED) {
            answerYesNoDialogWithUiAutomator(false)
            selectSafFolderWithUiAutomator(null)
        }
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
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
        tail(context)
    }

    private fun tail(context: Context) {
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
        if (permRejections > 1) {
            assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
                Activity.RESULT_CANCELED) {
                answerPermDialogWithUiAutomator(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, false)
            }
            assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                .isFalse()
            assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .isFalse()
        }
        if (permRejections > 2) {
            assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
                Activity.RESULT_CANCELED) {
                answerPermDialogWithUiAutomator(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, null)
            }
            assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                .isFalse()
            assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .isFalse()
        }
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Uri(
            getSafIdOnSd(""),
            true), Activity.RESULT_CANCELED) {
            if (permRejections < 3) {
                answerPermDialogWithUiAutomator(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, false)
            } else {
                answerPermSettingsWithUiAutomator(if (permRejections == 4) null else false)
            }
        }
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
    }
}