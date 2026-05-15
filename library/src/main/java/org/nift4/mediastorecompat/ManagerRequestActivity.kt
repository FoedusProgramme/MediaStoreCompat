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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.window.OnBackAnimationCallback
import androidx.annotation.RequiresApi
import androidx.core.net.toUri

@RequiresApi(Build.VERSION_CODES.R)
internal class ManagerRequestActivity : Activity() {
    private var didAsk = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        didAsk = savedInstanceState?.getBoolean("DidAsk") ?: false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(1000, object : OnBackAnimationCallback {
                override fun onBackInvoked() {
                    // nothing
                }
            })
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(1000) {
                // nothing
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("DidAsk", didAsk)
    }

    override fun onResume() {
        super.onResume()
        if (MediaStoreCompat.isManager(this)) {
            setResult(RESULT_OK)
            finish()
            return
        }
        if (didAsk) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.setData("package:$packageName".toUri())
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("ManagerRequestActivity", "can't ask for manager", e)
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        didAsk = true
        return
    }

    @Suppress("deprecation")
    @Deprecated("backward compatible only")
    override fun onBackPressed() {
        return
    }
}