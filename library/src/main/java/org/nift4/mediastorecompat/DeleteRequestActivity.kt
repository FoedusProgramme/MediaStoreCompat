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

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.IntentCompat

/** Only used for R and lower. */
internal class DeleteRequestActivity : DeleteUiActivity() {
    companion object {
        private const val TAG = "DeleteRequestActivity"
    }

    private var alive = true
    private var haveAsked = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG, "Wrong activity for version")
            finish()
            return
        }
        isTrash = intent!!.hasExtra("Trash")
        if (isTrash) {
            doTrash = intent!!.getBooleanExtra("Trash", false)
        }
        uris = IntentCompat.getParcelableArrayListExtra(intent, "Uris", Uri::class.java)
        if (uris.isNullOrEmpty()) {
            Log.e(TAG, "null/empty list of uris: $uris")
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null && savedInstanceState.containsKey("haveAsked")) {
            haveAsked = savedInstanceState.getInt("haveAsked")
            return
        }
        // do I/O and binder calls in background
        Thread {
            val isManager = MediaStoreCompat.isManager(this)
            val volumes = StorageManagerCompat.getStorageVolumes(this)
            val persistedUriPermissions = contentResolver.persistedUriPermissions
            // this only contains uris that have to be managed manually (-> on R: only those where we
            // must use SAF), the other uris are hidden away in NextIntent, and we don't need to bother
            val tokens = uris.mapNotNull {
                if (isTrash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStoreCompat.needRequestTrash(
                        this, it,
                        isManager = isManager, volumesCache = volumes,
                        persistedUriPermissionsCache = persistedUriPermissions
                    )
                } else {
                    MediaStoreCompat.needRequestDelete(
                        this, it, isManager = isManager, volumesCache = volumes,
                        persistedUriPermissionsCache = persistedUriPermissions
                    )
                }
            }
            if (tokens.isEmpty()) {
                Handler(Looper.getMainLooper()).post {
                    if (!alive) return@post // stale event
                    startQuestion()
                }
                return@Thread
            }
            val pi = MediaStoreCompat.createWriteRequest(this, tokens)
            Handler(Looper.getMainLooper()).post {
                if (!alive) return@post // stale event
                startIntentSenderForResult(
                    pi.intentSender, 1, null,
                    0, 0, 0
                )
                haveAsked = 1
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (haveAsked > 0) {
            outState.putInt("haveAsked", haveAsked)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) {
                startQuestion()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        } else if (requestCode == 2) {
            if (resultCode == RESULT_OK) {
                haveAsked = 2
                start(false)
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun startQuestion() {
        if (intent.hasExtra("NextIntent")) {
            startIntentSenderForResult(IntentCompat.getParcelableExtra(intent, "NextIntent",
                PendingIntent::class.java)!!.intentSender, 2, null,
                0, 0, 0)
        } else {
            haveAsked = 2
            start(true)
        }
    }

    protected override fun doIt(): Exception? {
        val isManager = MediaStoreCompat.isManager(this)
        val volumes = StorageManagerCompat.getStorageVolumes(this)
        val persistedUriPermissions = contentResolver.persistedUriPermissions
        val errors = uris.mapNotNull {
            try {
                if (isTrash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    MediaStoreCompat.markIsTrashedStatus(
                        this,
                        it,
                        doTrash,
                        isManager = isManager,
                        volumesCache = volumes,
                        persistedUriPermissionsCache = persistedUriPermissions
                    )
                } else {
                    MediaStoreCompat.delete(
                        this, it, isManager = isManager, volumesCache = volumes,
                        persistedUriPermissionsCache = persistedUriPermissions
                    )
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "failed to trash/delete $it", e)
                e
            }
        }
        val first = errors.firstOrNull()
        if (errors.size > 1) {
            errors.subList(1, errors.size).forEach {
                first!!.addSuppressed(it)
            }
        }
        return first
    }

    override fun onDestroy() {
        this.alive = false
        super.onDestroy()
    }

    @Suppress("deprecation")
    @Deprecated("backward compatible only")
    override fun onBackPressed() {
        return
    }
}