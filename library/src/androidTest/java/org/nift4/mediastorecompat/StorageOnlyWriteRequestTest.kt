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
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class StorageOnlyWriteRequestTest : TestBase() {

    @SdkSuppress(maxSdkVersion = 22)
    @Test
    fun testLegacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isTrue()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isTrue()
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
            Activity.RESULT_OK) {}
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isTrue()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isTrue()
    }

    @SdkSuppress(minSdkVersion = 23, maxSdkVersion = 29)
    @Test
    fun testRequests(@TestParameter("0", "1", "2", "3", "4", "5", "6") allow: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isFalse()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isFalse()
        if (allow < 1) {
            grantStoragePermission()
        }
        if (allow > 1) {
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
        if (allow > 2) {
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
        if (allow > 3) {
            assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
                Activity.RESULT_CANCELED) {
                answerPermSettingsWithUiAutomator(null)
            }
            assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                .isFalse()
            assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .isFalse()
        }
        if (allow > 5) {
            assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
                Activity.RESULT_CANCELED) {
                answerPermSettingsWithUiAutomator(false)
            }
            assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                .isFalse()
            assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                .isFalse()
        }
        assertResultOfWriteRequest(context, MediaStoreCompat.RequestToken.Manager,
            Activity.RESULT_OK) {
            if (allow >= 1) {
                if (allow < 3) {
                    answerPermDialogWithUiAutomator(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE, true
                    )
                } else {
                    answerPermSettingsWithUiAutomator(true)
                }
            }
        }
        assertSelfPermissionGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            .isTrue()
        assertSelfPermissionGranted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .isTrue()
    }
}