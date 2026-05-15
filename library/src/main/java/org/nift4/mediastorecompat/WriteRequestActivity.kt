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
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.core.provider.DocumentsContractCompat
import org.nift4.mediastorecompat.MediaStoreCompat.checkGrantSelfUriPermission

/** Only used for R and lower. */
internal class WriteRequestActivity : Activity() {
    companion object {
        private const val TAG = "WriteRequestActivity"
    }

    private var needManager = false
    private var permanentlyDeniedNeedAppInfo = false
    private var rationaleState = false
    private lateinit var uris: ArrayList<String>
    private lateinit var yesNoIds: ArrayList<String>
    private lateinit var safIds: ArrayList<String>
    private var nextIntent: PendingIntent? = null
    private var newUri: String? = null
    private var nextIsSaf = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.e(TAG, "Wrong activity for version")
            finish()
            return
        }
        if (savedInstanceState?.containsKey("Saved") == true) {
            val saved = savedInstanceState.getBundle("Saved")!!
            needManager = saved.getBoolean("NeedManager")
            uris = saved.getStringArrayList("Uris")!!
            yesNoIds = saved.getStringArrayList("YesNoSuggest")!!
            safIds = saved.getStringArrayList("SafSuggest")!!
            newUri = saved.getString("NewUri")
            nextIsSaf = saved.getBoolean("NextIsSaf")
            rationaleState = saved.getBoolean("rationaleState")
            permanentlyDeniedNeedAppInfo = saved.getBoolean("permanentlyDeniedNeedAppInfo")
            nextIntent = BundleCompat.getParcelable(saved, "NextIntent",
                PendingIntent::class.java)
        } else {
            if (!intent!!.hasExtra("NeedManager")) {
                Log.e(TAG, "missing extra NeedManager")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            this.needManager = intent!!.getBooleanExtra("NeedManager", false)
            if (needManager && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)) {
                Log.e(TAG, "need manager is true before M or after R")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            val uris = intent!!.getStringArrayListExtra("Uris")
            if (uris == null || (uris.isEmpty() && !needManager)) {
                Log.e(TAG, "null/empty list of uris: $uris")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            val yesNoIds = intent!!.getStringArrayListExtra("YesNoSuggest")
            if (yesNoIds == null) {
                Log.e(TAG, "null list of yes no ids: $yesNoIds")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            val safIds = intent!!.getStringArrayListExtra("SafSuggest")
            if (safIds == null) {
                Log.e(TAG, "null list of saf ids: $safIds")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            if (safIds.isEmpty() && yesNoIds.isEmpty() && !needManager) {
                Log.e(TAG, "empty list of saf and yes no ids: $safIds + $yesNoIds")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            nextIntent = IntentCompat.getParcelableExtra(intent, "NextIntent",
                PendingIntent::class.java)
            this.uris = uris
            this.yesNoIds = yesNoIds
            this.safIds = safIds
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle("Saved", Bundle().apply {
            putBoolean("NeedManager", needManager)
            putStringArrayList("Uris", uris)
            putStringArrayList("YesNoSuggest", yesNoIds)
            putStringArrayList("SafSuggest", safIds)
            putString("NewUri", newUri)
            putBoolean("NextIsSaf", nextIsSaf)
            putBoolean("rationaleState", rationaleState)
            putBoolean("permanentlyDeniedNeedAppInfo", permanentlyDeniedNeedAppInfo)
            putParcelable("NextIntent", nextIntent)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 1) { // yes-no
            // https://github.com/d4rken-org/sdmaid/issues/1496
            val id = yesNoIds.removeAt(0)
            if (resultCode == RESULT_OK && data?.data != null) {
                val newUri = data.data!!
                Log.i(TAG, "got access to $newUri")
                // Let's persist this so that we don't have to ask again next time.
                maybeTakePersistableUriPermission(
                    this,
                    newUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
                this.newUri = DocumentsContract.getTreeDocumentId(newUri)
            } else {
                // this is a yes-no dialog box which the user can permanently dismiss for a given
                // folder ("don't ask again"). however it's not possible for the user to undo this
                // decision, so we need to fall back to another API if we get canceled result. the
                // user may have accidentally perma-rejected access for this folder and would be
                // stuck with broken state if so. so we ask using the classic SAF folder picker (but
                // we should keep using yes-no for other volumes for better UX).
                safIds.add(0, id)
                nextIsSaf = true
            }
        } else if (requestCode == 2) { // folder picker
            if (resultCode != RESULT_OK) {
                // ignore non-OK for classic picker, user may have changed his mind
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            // this is the classic SAF folder picker, which may end up in selecting:
            // 1. a folder high enough to fulfill all delete requests (if all uris are on same
            //    volume)
            // 2. a folder too low to fulfill all of these, but useful for part of them (will
            //    always happen if the uris are not all on the same volume, but we need to ask
            //    for two or more volumes)
            // 3. something completely irrelevant (which we may or may not want to persist anyway)
            val newUri = DocumentsContract.getTreeDocumentId(data!!.data!!).let { id ->
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R)
                StorageManagerCompat.buildExternalStorageDocumentId(
                    StorageManagerCompat.getExternalStorageVolumeName(id),
                    StorageManagerCompat.getExternalStoragePath(id).let {
                        if (it.startsWith("./")) it.substring(2) else
                            if (it == ".") "" else it
                    }
                ) else id
            }
            val volumeId = StorageManagerCompat.getExternalStorageVolumeName(newUri)
            val isHelpful = safIds.remove(newUri) ||
                    uris.find { it.startsWith(newUri) } != null
            if (isHelpful || (volumeId != "primary" && newUri == "$volumeId:"
                        && StorageManagerCompat.getStorageVolumes(this)
                    .find {
                        it.documentsProviderVolumeName == volumeId &&
                                it.canonicalDirectory != null
                    } != null)
            ) {
                // if it's helpful to this deletion, let's take it. if it's the root of a SD card
                // that's not helpful, take it anyway because it's an opportunity to not have to ask
                // again when the user deletes something else. don't if it's not the root as we have
                // limited slots for persistable uris.
                maybeTakePersistableUriPermission(
                    this,
                    data.data!!,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
                this.newUri = newUri
            }
        } else if (requestCode == 4) { // next intent
            if (resultCode != RESULT_OK) {
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            this.nextIntent = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String?>,
        grantResults: IntArray
    ) {
        if (requestCode == 3) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                needManager = false
            } else {
                val needRationaleR = shouldShowRequestPermissionRationale(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                val needRationaleW = shouldShowRequestPermissionRationale(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                if ((!needRationaleR || !needRationaleW) && !rationaleState) {
                    permanentlyDeniedNeedAppInfo = true
                } else {
                    setResult(RESULT_CANCELED)
                    finish()
                    return
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (needManager && MediaStoreCompat.isManager(this))
            needManager = false
        if (needManager && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (permanentlyDeniedNeedAppInfo) {
                if (rationaleState) { // we already asked to open app info
                    val granted = checkSelfPermission(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED && checkSelfPermission(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                            PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        needManager = false
                    } else {
                        setResult(RESULT_CANCELED)
                        finish()
                        return
                    }
                } else {
                    rationaleState = true
                    Toast.makeText(this, getString(R.string.open_tree_button,
                        getString(R.string.permgrouplab_storage)),
                        Toast.LENGTH_LONG).show()
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.setData("package:$packageName".toUri())
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Log.e(TAG, "failed to recover from perma deny", e)
                        setResult(RESULT_CANCELED)
                        finish()
                        return
                    }
                    return
                }
            } else {
                val needRationaleR = shouldShowRequestPermissionRationale(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                )
                val needRationaleW = shouldShowRequestPermissionRationale(
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                rationaleState = needRationaleR && needRationaleW
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ), 3
                )
                return
            }
        }
        // Update stale data...
        if (newUri != null) {
            uris.removeAll { it.startsWith(newUri!!) }
            newUri = null
        }
        val persistedUriPermissionsCache = contentResolver.persistedUriPermissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nextIsSaf &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && yesNoIds.isNotEmpty()) {
            val yesNoIdsToClean = yesNoIds.iterator()
            var questionId = yesNoIdsToClean.next()
            while (true) {
                val requestedId = uris.find { it.startsWith(questionId) }
                if (requestedId == null) {
                    yesNoIds.remove(questionId)
                    if (!yesNoIdsToClean.hasNext())
                        break
                    questionId = yesNoIdsToClean.next()
                    continue
                }
                val hasAccess = MediaStoreCompat.getPrefixForDocument(
                    this, requestedId, true,
                    MediaStoreCompat.ResolvePermissions.OnlyPersistedTree,
                    persistedUriPermissionsCache
                ) != null
                if (hasAccess)
                    uris.remove(requestedId)
                else
                    break
            }
        }
        if (safIds.isNotEmpty() && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nextIsSaf &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && yesNoIds.isNotEmpty())) {
            val safIdsToClean = safIds.iterator()
            var questionId = safIdsToClean.next()
            while (true) {
                val requestedId = uris.find { it.startsWith(questionId) }
                if (requestedId == null) {
                    safIds.remove(questionId)
                    if (!safIdsToClean.hasNext())
                        break
                    questionId = safIdsToClean.next()
                    continue
                }
                val hasAccess = MediaStoreCompat.getPrefixForDocument(
                    this, requestedId, true,
                    MediaStoreCompat.ResolvePermissions.OnlyPersistedTree,
                    persistedUriPermissionsCache
                ) != null
                if (hasAccess)
                    uris.remove(requestedId)
                else
                    break
            }
        }
        // Time for the next round of telling the user what to do
        val volumes = StorageManagerCompat.getStorageVolumes(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nextIsSaf &&
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && yesNoIds.isNotEmpty()
        ) {
            val id = yesNoIds.first()
            val volumeId = StorageManagerCompat.getExternalStorageVolumeName(id)
            val folder = StorageManagerCompat.getExternalStoragePath(id)
            val volume = volumes.find { it.documentsProviderVolumeName == volumeId }
            if (volume == null) {
                Log.e(TAG, "volume missing: $volumeId")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            val intent = volume.createAccessIntent(folder.takeIf { it.isNotEmpty() })
                ?: throw IllegalStateException("got null accessIntent asking for $volume/$folder")
            startActivityForResult(intent, 1)
        } else if (safIds.isNotEmpty()) {
            val id = safIds.first()
            val volumeId = StorageManagerCompat.getExternalStorageVolumeName(id)
            val volume = volumes.find { it.documentsProviderVolumeName == volumeId }
            if (volume == null) {
                Log.e(TAG, "volume missing: $volumeId")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
            val folder = StorageManagerCompat.getExternalStoragePath(id).let {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && it.isEmpty()) {
                    "./"
                } else it
            }
            val intent = volume.createOpenDocumentTreeIntent(folder)
            Toast.makeText(this, getString(R.string.open_tree_button,
                volume.getDescription(this)), Toast.LENGTH_LONG)
                .show()
            startActivityForResult(intent, 2)
        } else {
            if (uris.isNotEmpty()) {
                throw IllegalStateException("Saf policy has logic error - $uris left over " +
                        "without strategy")
            }
            if (nextIntent == null) {
                setResult(RESULT_OK)
                finish()
                return
            }
            startIntentSenderForResult(nextIntent!!.intentSender, 4,
                null, 0, 0, 0)
        }
        nextIsSaf = false
    }

    // we only have limited (128-512) slots for persisted uri permissions, so try to be efficient
    private fun maybeTakePersistableUriPermission(context: Context, uri: Uri, flags: Int) {
        if (uri.authority != StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE
            || !DocumentsContractCompat.isTreeUri(uri))
            throw IllegalArgumentException("not tree uri: $uri")
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val persistedUriPermissions = context.contentResolver.persistedUriPermissions
        // If we have a parent uri with enough permission already, we don't need this at all
        if (persistedUriPermissions
            .find { it.uri.authority == StorageManagerCompat.AUTHORITY_EXTERNAL_STORAGE
                    && DocumentsContractCompat.isTreeUri(it.uri) &&
                    (it.isWritePermission ||
                    (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
                    && (it.isReadPermission ||
                    (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) &&
                    DocumentsContract.getTreeDocumentId(it.uri)
                        .let { id -> id.startsWith(treeId) && id != treeId } &&
                    context.checkGrantSelfUriPermission(it.uri, flags /* check prefix */
                        or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) } != null)
            return
        val maxSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 512 else 128
        if (persistedUriPermissions.size == maxSize && // we need to get rid of some.
            persistedUriPermissions.find { it.uri == uri } == null) {
            val grant = persistedUriPermissions.minBy { it.persistedTime }
            // Imitates system behavior, but avoids unsafe release issue at least.
            Log.w(TAG, "Removing unrelated persisted grant: $grant")
            safelyReleasePersistablePermission(context, grant)
        }
        // If we reach the limit, it will take away the oldest permission unsafely
        context.contentResolver.takePersistableUriPermission(uri, flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }

    private fun safelyReleasePersistablePermission(context: Context, it: UriPermission) {
        // Caution: if we call releasePersistableUriPermission after reboot, it will instantly
        // take our permission away. This could sabotage other (possibly non-library) threads that
        // still try to use URIs. To avoid complex locking, just give ourselves an in-memory grant
        // to the old child tree (that will last until next reboot, after which we won't be trying
        // to use the old URI anymore; because it won't be in persistedUriPermissions list and all
        // old operations have completed one way or another with process death due to reboot).
        try {
            // we must set either FLAG_GRANT_PREFIX_URI_PERMISSION or
            // FLAG_GRANT_PERSISTABLE_URI_PERMISSION to avoid automatically getting sorted out due
            // to already having permissions. and as there is no API to detect whether we currently
            // have a prefix grant we have to brute force here.
            context.grantUriPermission(
                context.packageName,
                it.uri,
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                        or (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0)
                        or (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            )
        } catch (_: SecurityException) {
            // the irony of having to use FLAG_GRANT_PERSISTABLE_URI_PERMISSION isn't lost on me
            context.grantUriPermission(
                context.packageName,
                it.uri,
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        or (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0)
                        or (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            )
        }
        context.contentResolver.releasePersistableUriPermission(it.uri,
            (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0)
                    or (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
    }

    @Suppress("deprecation")
    @Deprecated("backward compatible only")
    override fun onBackPressed() {
        return
    }
}