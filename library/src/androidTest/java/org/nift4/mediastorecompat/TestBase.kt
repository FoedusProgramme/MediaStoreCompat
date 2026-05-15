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
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract.buildDocumentUriUsingTree
import android.provider.DocumentsContract.buildTreeDocumentUri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.shell.Shell
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.uiAutomator
import com.google.common.truth.BooleanSubject
import com.google.common.truth.Fact.simpleFact
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

abstract class TestBase {
    protected var overrideSdUuid: String? = null
    protected fun getInternalStorage(): StorageVolumeCompat {
        return StorageManagerCompat.getStorageVolumes(InstrumentationRegistry.getInstrumentation()
            .targetContext).first(StorageVolumeCompat::isPrimary)
    }

    protected fun getSdCard(): StorageVolumeCompat {
        if (overrideSdUuid != null) {
            return StorageManagerCompat.getStorageVolumes(InstrumentationRegistry.getInstrumentation()
                .targetContext).first { it.uuid == overrideSdUuid }
        }
        return StorageManagerCompat.getStorageVolumes(InstrumentationRegistry.getInstrumentation()
            .targetContext).first { v -> !v.isPrimary }
    }

    protected fun startIntentSenderForResult(sender: IntentSender, callback: () -> Unit): Instrumentation.ActivityResult {
        val scenario = ActivityScenario.launchActivityForResult(TestActivity::class.java)
        scenario.moveToState(Lifecycle.State.RESUMED)
        scenario.onActivity {
            it.startIntentSenderForResult(sender, 1,
                null, 0, 0, 0)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        callback.invoke()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val start = System.currentTimeMillis()
        while (try { scenario.state } catch (_: NullPointerException) { Lifecycle.State.STARTED
        /* billion-dollar company btw */ } != Lifecycle.State.DESTROYED &&
            System.currentTimeMillis() - start < 20000L) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
        // To avoid https://github.com/android/android-test/issues/676 we ensure DESTROYED first
        val result = scenario.result
        scenario.close()
        return result
    }

    protected fun getSafUriOnSd(path: String, tree: String): Uri {
        val volumeCompat = getSdCard()
        return buildDocumentUriUsingTree(
            buildTreeDocumentUri(
                StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE,
                StorageManagerCompat.buildExternalStorageDocumentId(
                    volumeCompat, tree
                )
            ),
            StorageManagerCompat.buildExternalStorageDocumentId(
                volumeCompat, path
            )
        )
    }

    protected fun getSafIdOnSd(path: String): String {
        return StorageManagerCompat.buildExternalStorageDocumentId(getSdCard(), path)
    }

    protected fun spyContextWithStorageGranted(context: Context): Context {
        val mockContext = spyk(ContextWrapper(context))
        every { mockContext.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE,
            Process.myPid(), Process.myUid()) } returns PackageManager.PERMISSION_GRANTED
        every { mockContext.checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Process.myPid(), Process.myUid()) } returns PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            every { mockContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) }
                .returns(PackageManager.PERMISSION_GRANTED)
            every { mockContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
                .returns(PackageManager.PERMISSION_GRANTED)
        }
        return mockContext
    }

    private fun scanFileInternal(context: Context, file: String): Uri? {
        val result = AtomicReference<Uri>(null)
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(
            context, arrayOf(file),
            null
        ) { _, mediaUri -> result.set(mediaUri); latch.countDown() }
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
        return result.get()
    }

    protected fun scanFile(context: Context, file: String): Uri? {
        val ext = File(file).extension
        val isPlaylist = ext == "m3u" || ext == "pls" || ext == "wpl"
        if (!isPlaylist || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return scanFileInternal(context, file)
        }
        scanFileInternal(context, file)?.let {
            return it // if it works, it sure was worth the try, but usually it fails
        }
        val latch = CountDownLatch(1)
        val finishReceiver = object : BroadcastReceiver() {
            override fun onReceive(p0: Context?, p1: Intent?) {
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, finishReceiver, IntentFilter(
            Intent.ACTION_MEDIA_SCANNER_FINISHED).apply {
            addDataScheme("file")
        }, ContextCompat.RECEIVER_EXPORTED)
        // Playlist files have a bug where they only get updated on volume scan
        context.startService(Intent("android.media.IMediaScannerService")
            .setClassName("com.android.providers.media",
            "com.android.providers.media.MediaScannerService")
            .putExtra("volume", "external"))
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue()
        context.unregisterReceiver(finishReceiver)
        return context.contentResolver.query(MediaStoreCompat.FILES_EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.Files.FileColumns.DATA} = ?",
            arrayOf(file),
            null).use {
            if (it != null && it.moveToFirst())
                ContentUris.withAppendedId(
                    MediaStoreCompat.getBaseUriForMediaType(
                        MediaStoreCompat.VOLUME_EXTERNAL,
                        it.getInt(1)
                    ), it.getLong(0))
            else null
        }
    }

    protected fun answerManagerRequest(allow: Boolean) {
        uiAutomator {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                onElement { packageName == "com.android.settings" && isCheckable }
            } else {
                onElement {
                    packageName == "com.android.settings" &&
                            viewIdResourceName == "android:id/switch_widget"
                }
            }
                .let { if (allow) it.click() }
            pressBack()
        }
    }

    protected fun gainAccessToTokenHappyPath(context: Context, token: MediaStoreCompat.RequestToken) {
        gainAccessToTokenHappyPath(context, listOf(token))
    }

    protected fun gainAccessToTokenHappyPath(context: Context, tokens: List<MediaStoreCompat.RequestToken>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (tokens.find { it.requestManager } != null) {
                if (MediaStoreCompat.isManager(context)) {
                    // https://github.com/android/android-test/issues/1658 manager is not cleared
                    // with data wipe and I don't know of any workaround at the moment
                    return
                }
                assertResultOfWriteRequest(context, tokens, Activity.RESULT_OK) {
                    answerManagerRequest(true)
                }
            } else {
                assertResultOfWriteRequest(context, tokens, Activity.RESULT_OK) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        answerWriteRequest(true)
                    } else {
                        if (tokens.find { it.uri?.startsWith("content") == false } != null) {
                            selectSafFolderWithUiAutomator(listOf())
                        }
                        if (tokens.find { it.uri?.startsWith("content") == true } != null) {
                            answerWriteRequest(true)
                        }
                    }
                }
            }
            return
        }
        assertThat(tokens.map { it.requestManager }).doesNotContain(true)
        assertResultOfWriteRequest(context, tokens, Activity.RESULT_OK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                answerYesNoDialogWithUiAutomator(true)
            } else {
                selectSafFolderWithUiAutomator(emptyList())
            }
        }
    }

    protected fun answerPermDialogWithUiAutomator(permission: String, allow: Boolean?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        uiAutomator {
            onElement { if (Build.VERSION.SDK_INT >= 30)
                packageName == "com.android.permissioncontroller"
                    || packageName == "com.google.android.permissioncontroller"
            else packageName == "com.android.packageinstaller"
                    || packageName == "com.google.android.packageinstaller" }
            assertThat(ContextCompat.checkSelfPermission(context, permission)).isEqualTo(
                PackageManager.PERMISSION_DENIED)
            val act = when (allow) {
                true -> "permission_allow_button"
                false -> "permission_deny_button"
                null -> "do_not_ask_checkbox"
            }
            onElement { viewIdResourceName == when {
                Build.VERSION.SDK_INT <= 29 ->
                    "com.android.packageinstaller:id/$act"
                else ->
                    "com.android.permissioncontroller:id/$act"
            } }.click()
            if (allow == null) {
                onElement { viewIdResourceName == when {
                    Build.VERSION.SDK_INT <= 29 ->
                        "com.android.packageinstaller:id/permission_deny_button"
                    else ->
                        "com.android.permissioncontroller:id/permission_deny_button"
                } }.click()
            }
            val retry = System.currentTimeMillis()
            if (allow == true) {
                while (ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED && System.currentTimeMillis() - retry < 500
                ) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
            }
            val desired = if (allow == true) PackageManager.PERMISSION_GRANTED else
                PackageManager.PERMISSION_DENIED
            assertThat(ContextCompat.checkSelfPermission(context, permission)).isEqualTo(
                desired)
        }
    }

    protected fun answerPermSettingsWithUiAutomator(allow: Boolean?) {
        uiAutomator {
            onElement { packageName == "com.android.settings" &&
                    viewIdResourceName == "android:id/title" &&
                    text == "Permissions" /*TODO i18n*/ }.let {
                if (allow != null) {
                    it.click()
                    onElement {
                        (packageName == "com.android.packageinstaller" ||
                                packageName == "com.google.android.packageinstaller") &&
                                viewIdResourceName == "android:id/switch_widget"
                    }.let {
                        if (allow) it.click()
                    }
                    pressBack()
                    onElement { packageName == "com.android.settings" &&
                            viewIdResourceName == "android:id/title" &&
                            text == "Permissions" /*TODO i18n*/ }
                }
            }
            pressBack()
        }
    }

    protected fun Shell.CommandOutput.waitForCompletion() {
        runBlocking {
            withTimeout(1000) {
                withContext(Dispatchers.IO) {
                    stdOut
                }
            }
        }
    }

    protected fun answerYesNoDialogWithUiAutomator(allow: Boolean?) {
        uiAutomator {
            onElement { packageName == "com.android.documentsui"
                        || packageName == "com.google.android.documentsui"}
            if (allow != null) {
                val act = if (allow) "1" else "2"
                onElement { (packageName == "com.android.documentsui"
                        || packageName == "com.google.android.documentsui") &&
                        viewIdResourceName == "android:id/button$act" }.click()
            } else {
                pressBack()
            }
        }
    }

    protected fun dontAskAgainYesNoDialogWithUiAutomator() {
        uiAutomator {
            onElement { packageName == "com.android.documentsui"
                    || packageName == "com.google.android.documentsui" }
            onElement { (packageName == "com.android.documentsui"
                    || packageName == "com.google.android.documentsui") &&
                    viewIdResourceName == "com.android.documentsui:id/do_not_ask_checkbox" }.click()
            onElement { (packageName == "com.android.documentsui"
                    || packageName == "com.google.android.documentsui") &&
                    viewIdResourceName == "android:id/button2" }.click()
        }
    }

    protected fun answerWriteRequest(allow: Boolean) {
        uiAutomator {
            val act = if (allow) "1" else "2"
            onElement { (packageName == "com.android.providers.media.module" ||
                    packageName == "com.google.android.providers.media.module") &&
                    viewIdResourceName == "android:id/button$act" }.click()
        }
    }

    /** empty=use default, null=deny, list with content=click on these folders before allow */
    protected fun selectSafFolderWithUiAutomator(folders: List<String>?) {
        uiAutomator {
            onElements { packageName == "com.android.documentsui" &&
                    viewIdResourceName == "com.android.documentsui:id/toolbar"
                    || packageName == "com.google.android.documentsui" &&
                    viewIdResourceName == "com.google.android.documentsui:id/toolbar" }
            if (folders != null) {
                folders.forEach {
                    if (it == "..")
                        pressBack()
                    else
                        onElement {
                            (packageName == "com.android.documentsui"
                                    || packageName == "com.google.android.documentsui") &&
                                    viewIdResourceName == "android:id/title" &&
                                    text?.contains(it) == true
                        }.click()
                }
                onElement { viewIdResourceName == "android:id/button1" }.click()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    onElement { viewIdResourceName == "android:id/message" }
                    onElement { viewIdResourceName == "android:id/button1" }.click()
                }
            } else {
                while (onElementOrNull(100, 10) {
                    packageName == "com.android.documentsui" &&
                            viewIdResourceName == "com.android.documentsui:id/toolbar"
                            || packageName == "com.google.android.documentsui" &&
                            viewIdResourceName == "com.google.android.documentsui:id/toolbar" }
                    != null) {
                    pressBack()
                }
            }
        }
    }

    protected fun getSdPath(): String {
        return getSdCard().requireCanonicalDirectory().absolutePath
    }

    protected fun getDevice(): UiDevice {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @SuppressLint("DiscouragedApi")
    protected fun executeShellCommand(command: String): String {
        return getDevice().executeShellCommand(command)
    }

    protected fun grantPermission(permission: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            assertThat(ContextCompat.checkSelfPermission(instrumentation.targetContext, permission))
                .isEqualTo(PackageManager.PERMISSION_GRANTED)
            return
        }
        assertThat(ContextCompat.checkSelfPermission(instrumentation.targetContext, permission))
            .isEqualTo(PackageManager.PERMISSION_DENIED)
        // This is quite stupid, but GrantPermissionRule is a nice wrapper around "pm grant" that
        // handles waiting for it to be done. If it's already there, let's use it, makes life easy.
        GrantPermissionRule.grant(permission).apply(object : Statement() {
            override fun evaluate() {}
        }, Description.createSuiteDescription(javaClass)).evaluate()
        assertThat(ContextCompat.checkSelfPermission(instrumentation.targetContext, permission))
            .isEqualTo(PackageManager.PERMISSION_GRANTED)
    }

    protected fun grantStoragePermission() {
        assertThat(Build.VERSION.SDK_INT).isAtMost(Build.VERSION_CODES.S_V2)
        grantPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            grantPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    protected fun grantMediaOrStoragePermission(mediaPermission: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantPermission(mediaPermission)
        } else {
            grantStoragePermission()
        }
    }

    protected fun assertSelfPermissionGranted(context: Context, permission: String): BooleanSubject {
        return assertThat(ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED)
    }

    protected class UriSubject(metadata: FailureMetadata, private val actual: Uri?) : Subject(metadata, actual) {
        companion object {
            @JvmStatic
            fun uris(): Factory<UriSubject, Uri> {
                return { a, b -> UriSubject(a, b) }
            }
        }

        fun permission(context: Context, flags: Int): BooleanSubject {
            if (actual == null) {
                failWithActual(simpleFact("expected not to be null"))
                return ignoreCheck().that(false)
            }
            return check("checkSelfUriPermission(${when (flags) {
                Intent.FLAG_GRANT_READ_URI_PERMISSION -> "Intent.FLAG_GRANT_READ_URI_PERMISSION"
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION -> "Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION ->
                    "Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION"
                else -> "$flags"
            }})").that(context.checkSelfUriPermission(actual, flags))
        }

        fun isNotReadableAndWritableFrom(context: Context) {
            permission(context, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isFalse()
        }

        fun isReadableAndWritableFrom(context: Context) {
            permission(context, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isTrue()
        }

        private fun Context.checkSelfUriPermission(uri: Uri, flags: Int): Boolean {
            return checkUriPermission(uri, android.os.Process.myPid(), Process.myUid(),
                flags) == PackageManager.PERMISSION_GRANTED
        }
    }

    protected fun assertResultOfWriteRequest(context: Context,
                                           token: MediaStoreCompat.RequestToken,
                                           desiredResult: Int,
                                           callback: () -> Unit) {
        assertResultOfWriteRequest(context, listOf(token), desiredResult, callback)
    }

    protected fun assertResultOfWriteRequest(context: Context,
                                           tokens: List<MediaStoreCompat.RequestToken>,
                                           desiredResult: Int,
                                           callback: () -> Unit) {
        assertThat(startIntentSenderForResult(MediaStoreCompat.createWriteRequest(context, tokens)
            .intentSender, callback).resultCode).isEqualTo(desiredResult)
    }
}