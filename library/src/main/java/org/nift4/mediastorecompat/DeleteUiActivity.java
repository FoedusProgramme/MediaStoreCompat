/*
 * Copyright (C) 2018 The Android Open Source Project
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

package org.nift4.mediastorecompat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.ImageDecoder.ImageInfo;
import android.graphics.ImageDecoder.Source;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.icu.text.MessageFormat;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.core.util.Function;
import androidx.core.widget.TextViewCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Permission dialog that asks for user confirmation before performing a
 * specific action, such as granting access for a narrow set of media files to
 * the calling app.
 *
 * @see MediaStore#createWriteRequest
 * @see MediaStore#createTrashRequest
 * @see MediaStore#createFavoriteRequest
 * @see MediaStore#createDeleteRequest
 */
abstract class DeleteUiActivity extends Activity {
    private static final String TAG = "DeleteUiActivity";

    private static final String HEIGHT = "height";
    private static final String WIDTH = "width";
    private static final String HEIGHT_RATIO = "heightRatio";

    protected List<Uri> uris;
    protected boolean isTrash;
    protected boolean doTrash;

    private CharSequence label;
    private String data;

    private AlertDialog actionDialog;
    private AsyncTask<Void, Void, Void> positiveActionTask;
    private Dialog progressDialog;
    private TextView titleView;
    private Handler mHandler;
    private final Runnable mShowProgressDialogRunnable = () -> {
        // We will show the progress dialog, add the dim effect back.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        progressDialog.show();
    };

    private static final Long LEAST_SHOW_PROGRESS_TIME_MS = 300L;
    private static final Long BEFORE_SHOW_PROGRESS_TIME_MS = 300L;

    private static final String DATA_AUDIO = "audio";
    private static final String DATA_VIDEO = "video";
    private static final String DATA_IMAGE = "image";
    private static final String DATA_GENERIC = "generic";

    // Use to sort the thumbnails.
    private static final int ORDER_IMAGE = 1;
    private static final int ORDER_VIDEO = 2;
    private static final int ORDER_AUDIO = 3;
    private static final int ORDER_GENERIC = 4;

    private static final int MAX_THUMBS = 3;
    private View mThumbFull;
    private int mOriginalHeight = 0;
    private int mOriginalWidth = 0;
    private float mHeightRatio = 1f;
    private Rect mCurrentWindowMetrics;
    private Rect mMaximumWindowMetrics;
    private Bundle mDimensionBundle;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Strategy borrowed from PermissionController
        setFinishOnTouchOutside(false);
        // remove the dim effect
        // We may not show the progress dialog, if we don't remove the dim effect,
        // it may have flicker.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.0f);
        if (savedInstanceState != null && savedInstanceState.containsKey("shouldShowDialog")) {
            doStart(savedInstanceState.getBoolean("shouldShowDialog"), savedInstanceState);
        }
    }

    protected void start(boolean shouldShowActionDialog) {
        doStart(shouldShowActionDialog, null);
    }

    private void doStart(boolean shouldShowActionDialog, Bundle savedInstanceState) {
        // All untrusted input values here were validated when generating the
        // original PendingIntent
        try {
            ApplicationInfo appInfo = resolveCallingAppInfo();
            label = resolveAppLabel(appInfo);
            data = resolveData();
        } catch (Exception e) {
            Log.w(TAG, "failed to load app label", e);
            finish();
            return;
        }

        mHandler = new Handler(getMainLooper());
        // Create Progress dialog
        createProgressDialog();

        if (!shouldShowActionDialog) {
            onPositiveAction(null, 0);
            return;
        }

        // Kick off async loading of description to show in dialog
        final View bodyView = getLayoutInflater().inflate(R.layout.permission_body, null, false);
        handleImageViewVisibility(bodyView, uris);
        new DescriptionTask(bodyView).execute(uris);

        // Initialising the custom dialog title
        @SuppressLint("InflateParams") final View dialogTitleView = getLayoutInflater().inflate(
                R.layout.dialog_title, null, false);
        final TextView dialogTitleTextView = dialogTitleView.findViewById(
                R.id.dialog_title);
        if (dialogTitleTextView == null) {
            Log.e(TAG, "Could not inflate custom dialog title view");
        } else {
            dialogTitleTextView.setText(resolveTitleText());
            TextViewCompat.setTextAppearance(dialogTitleTextView, R.style.PermissionAlertDialogTitle);
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCustomTitle(dialogTitleTextView);
        builder.setPositiveButton(R.string.allow, this::onPositiveAction);
        builder.setNegativeButton(R.string.deny, this::onNegativeAction);
        builder.setCancelable(false);
        builder.setView(bodyView);

        actionDialog = builder.show();

        mThumbFull = bodyView.findViewById(R.id.thumb_full);
        mCurrentWindowMetrics = BoundsHelper.Companion.getInstance().currentWindowBounds(this);
        mMaximumWindowMetrics = BoundsHelper.Companion.getInstance().maximumWindowBounds(this);
        mDimensionBundle = savedInstanceState;
        if (savedInstanceState != null) {
            // Resizing on window size change is only done for thumb_full ImageView
            resizeImageView(savedInstanceState);
        }

        // Hunt around to find the title of our newly created dialog so we can
        // adjust accessibility focus once descriptions have been loaded
        titleView = (TextView) findViewByPredicate(actionDialog.getWindow().getDecorView(),
                (view) -> (view instanceof TextView) && view.isImportantForAccessibility());
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The activity is not recreated on screen size changes in free-form mode, hence we use this
        // method to resize the thumbnail.
        if (mDimensionBundle != null) {
            resizeImageView(mDimensionBundle);
        }
    }

    private void resizeImageView(Bundle savedInstanceState) {
        mOriginalHeight = savedInstanceState.getInt(HEIGHT);
        mOriginalWidth = savedInstanceState.getInt(WIDTH);
        mHeightRatio = savedInstanceState.getFloat(HEIGHT_RATIO);

        final boolean isHeightLessThanScreenHeight = mCurrentWindowMetrics.height()
                < mMaximumWindowMetrics.height();
        final boolean isHeightEqualToScreenHeight = mCurrentWindowMetrics.height()
                == mMaximumWindowMetrics.height();

        // Resizing the alert dialog thumbnail is needed only when all the following are true:
        // 2. R.id.thumb_full has its visibility set to View.VISIBLE
        // 3. Activity's height is less than the screen height
        if (mThumbFull.getVisibility() == View.VISIBLE
                && isHeightLessThanScreenHeight) {
            int newHeight = (int) (mHeightRatio * mCurrentWindowMetrics.height());
            float aspectRatio = (float) mOriginalWidth / mOriginalHeight;
            int newWidth = (int) (aspectRatio * newHeight);
            mThumbFull.getLayoutParams().height = newHeight;
            mThumbFull.getLayoutParams().width = newWidth;
            // This handles all the cases when the activity is destroyed and then recreated
            // but resizing the thumbnail is not needed
        } else if (mOriginalWidth != 0 || isHeightEqualToScreenHeight) {
            mThumbFull.getLayoutParams().height = mOriginalHeight;
            mThumbFull.getLayoutParams().width = mOriginalWidth;
        }
    }

    private void createProgressDialog() {
        final ProgressBar progressBar = new ProgressBar(this);
        final int padding = getResources().getDimensionPixelOffset(R.dimen.dialog_space);

        progressBar.setIndeterminate(true);
        progressBar.setPadding(0, padding / 2, 0, padding);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle(resolveProgressMessageText())
                .setView(progressBar)
                .setCancelable(false)
                .create();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mThumbFull != null) {
            outState.putBoolean("shouldShowDialog", positiveActionTask == null);
            // Save original dimensions when the activity is in full-screen mode on the first launch
            // In subsequent calls of this method it is ensured that the saved dimensions stay the same
            // throughout, i.e. the newly calculated dimensions are never stored
            if (mOriginalWidth == 0) {
                outState.putInt(HEIGHT, mThumbFull.getHeight());
                outState.putInt(WIDTH, mThumbFull.getWidth());
                // Ideally, we should calculate this ratio using the AlertDialog's height instead of the
                // window height. However, accessing the AlertDialog's dimensions in onCreate
                // returns 0 because the dialog hasn't been drawn yet.
                outState.putFloat(HEIGHT_RATIO,
                        (float) mThumbFull.getHeight() / mCurrentWindowMetrics.height());
            } else {
                outState.putInt(HEIGHT, mOriginalHeight);
                outState.putInt(WIDTH, mOriginalWidth);
                outState.putFloat(HEIGHT_RATIO, mHeightRatio);
            }
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mHandler != null) {
            mHandler.removeCallbacks(mShowProgressDialogRunnable);
        }
        // Cancel and interrupt the AsyncTask of the positive action. This avoids
        // calling the old activity during "onPostExecute", but the AsyncTask could
        // still finish its background task. For now we are ok with:
        // 1. the task potentially runs again after the configuration is changed
        // 2. the task completed successfully, but the activity doesn't return
        // the response.
        if (positiveActionTask != null) {
            positiveActionTask.cancel(true /* mayInterruptIfRunning */);
        }
        // Dismiss the dialogs to avoid the window is leaked
        if (actionDialog != null) {
            actionDialog.dismiss();
        }
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
    }

    protected abstract @Nullable Exception doIt();

    private void onPositiveAction(@Nullable DialogInterface dialog, int which) {
        // Disable the buttons
        if (dialog != null) {
            ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }

        final long startTime = System.currentTimeMillis();

        mHandler.postDelayed(mShowProgressDialogRunnable, BEFORE_SHOW_PROGRESS_TIME_MS);

        positiveActionTask = new AsyncTask<Void, Void, Void>() {
            Exception error = null;

            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User allowed grant for " + uris);
                try {
                    error = doIt();
                } catch (Exception e) {
                    Log.w(TAG, "failed to do action", e);
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                if (error == null) {
                    setResult(Activity.RESULT_OK);
                } else {
                    setResult(Activity.RESULT_FIRST_USER, new Intent()
                            .putExtra("ErrorMsg", error.getMessage())
                            .putExtra("StackTrace", Log.getThrowableString(error)));
                }
                mHandler.removeCallbacks(mShowProgressDialogRunnable);

                if (!progressDialog.isShowing()) {
                    finish();
                } else {
                    // Don't dismiss the progress dialog too quick, it will cause bad UX.
                    final long duration =
                            System.currentTimeMillis() - startTime - BEFORE_SHOW_PROGRESS_TIME_MS;
                    if (duration > LEAST_SHOW_PROGRESS_TIME_MS) {
                        progressDialog.dismiss();
                        finish();
                    } else {
                        mHandler.postDelayed(() -> {
                            progressDialog.dismiss();
                            finish();
                        }, LEAST_SHOW_PROGRESS_TIME_MS - duration);
                    }
                }
            }
        }.execute();
    }

    private void onNegativeAction(DialogInterface dialog, int which) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.d(TAG, "User declined request for " + uris);
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }.execute();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Strategy borrowed from PermissionController
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Strategy borrowed from PermissionController
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    private void handleImageViewVisibility(View bodyView, List<Uri> uris) {
        if (uris.isEmpty()) {
            return;
        }
        if (uris.size() == 1) {
            // Set visible to the thumb_full to avoid the size
            // changed of the dialog in full decoding.
            final ImageView thumbFull = bodyView.findViewById(R.id.thumb_full);
            thumbFull.setVisibility(View.VISIBLE);
        } else {
            // If the size equals 2, we will remove thumb1 later.
            // Set visible to the thumb2 and thumb3 first to avoid
            // the size changed of the dialog.
            ImageView thumb = bodyView.findViewById(R.id.thumb2);
            thumb.setVisibility(View.VISIBLE);
            thumb = bodyView.findViewById(R.id.thumb3);
            thumb.setVisibility(View.VISIBLE);
            // If the count of thumbs equals to MAX_THUMBS, set visible to thumb1.
            if (uris.size() == MAX_THUMBS) {
                thumb = bodyView.findViewById(R.id.thumb1);
                thumb.setVisibility(View.VISIBLE);
            } else if (uris.size() > MAX_THUMBS) {
                // If the count is larger than MAX_THUMBS, set visible to
                // thumb_more_container.
                final View container = bodyView.findViewById(R.id.thumb_more_container);
                container.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Resolve a label that represents the app denoted by given {@link ApplicationInfo}.
     */
    private @NonNull CharSequence resolveAppLabel(final ApplicationInfo ai)
            throws NameNotFoundException {
        final PackageManager pm = getPackageManager();
        final CharSequence callingLabel = pm.getApplicationLabel(ai);
        if (TextUtils.isEmpty(callingLabel)) {
            throw new NameNotFoundException("Missing calling package");
        }

        return callingLabel;
    }

    /**
     * Resolve the application info of the calling app.
     */
    private @NonNull ApplicationInfo resolveCallingAppInfo() throws NameNotFoundException {
        String callingPackage = getCallingPackage();
        if (callingPackage == null) {
            callingPackage = getPackageName();
        }
        return getPackageManager().getApplicationInfo(callingPackage, 0);
    }

    /**
     * Resolve what kind of data this permission request is asking about. If the
     * requested data is of mixed types, this returns {@link #DATA_GENERIC}.
     */
    private @NonNull String resolveData() {
        final int firstMatch = matchUri(uris.get(0));

        for (int i = 1; i < uris.size(); i++) {
            final int match = matchUri(uris.get(i));
            if (match != firstMatch) {
                // If we don't need to check new permission, we can return DATA_GENERIC here. We
                // don't need to resolve the other uris.
                return DATA_GENERIC;
                // Any mismatch means we need to use generic strings
            }
        }

        switch (firstMatch) {
            case AUDIO_MEDIA_ID:
                return DATA_AUDIO;
            case VIDEO_MEDIA_ID:
                return DATA_VIDEO;
            case IMAGES_MEDIA_ID:
                return DATA_IMAGE;
            default:
                return DATA_GENERIC;
        }
    }

    /**
     * Resolve the dialog title string to be displayed to the user. All
     * arguments have been bound and this string is ready to be displayed.
     */
    private @Nullable CharSequence resolveTitleText() {
        int resId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            switch (data) {
                case DATA_VIDEO:
                    resId = isTrash ? (doTrash ? R.string.permission_trash_video :
                            R.string.permission_untrash_video) : R.string.permission_delete_video;
                    break;
                case DATA_AUDIO:
                    resId = isTrash ? (doTrash ? R.string.permission_trash_audio :
                            R.string.permission_untrash_audio) : R.string.permission_delete_audio;
                    break;
                case DATA_IMAGE:
                    resId = isTrash ? (doTrash ? R.string.permission_trash_image :
                            R.string.permission_untrash_image) : R.string.permission_delete_image;
                    break;
                case DATA_GENERIC:
                    resId = isTrash ? (doTrash ? R.string.permission_trash_generic :
                            R.string.permission_untrash_generic) : R.string.permission_delete_generic;
                    break;
            }
        } else {
            switch (data) {
                case DATA_VIDEO:
                    resId = isTrash ? (doTrash ? R.plurals.permission_trash_video :
                            R.plurals.permission_untrash_video) : R.plurals.permission_delete_video;
                    break;
                case DATA_AUDIO:
                    resId = isTrash ? (doTrash ? R.plurals.permission_trash_audio :
                            R.plurals.permission_untrash_audio) : R.plurals.permission_delete_audio;
                    break;
                case DATA_IMAGE:
                    resId = isTrash ? (doTrash ? R.plurals.permission_trash_image :
                            R.plurals.permission_untrash_image) : R.plurals.permission_delete_image;
                    break;
                case DATA_GENERIC:
                    resId = isTrash ? (doTrash ? R.plurals.permission_trash_generic :
                            R.plurals.permission_untrash_generic) : R.plurals.permission_delete_generic;
                    break;
            }
        }
        if (resId != 0) {
            final int count = uris.size();
            final CharSequence text = getICUFormatString(getResources(), count, resId);
            return TextUtils.expandTemplate(text, label, String.valueOf(count));
        } else {
            // We always need a string to prompt the user with
            throw new IllegalStateException("Invalid resource: " + data);
        }
    }

    /**
     * Resolve the progress message string to be displayed to the user. All
     * arguments have been bound and this string is ready to be displayed.
     */
    private @Nullable CharSequence resolveProgressMessageText() {
        int resId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            switch (data) {
                case DATA_VIDEO:
                    resId = isTrash ? (doTrash ? R.string.permission_progress_trash_video :
                            R.string.permission_progress_untrash_video) : R.string.permission_progress_delete_video;
                    break;
                case DATA_AUDIO:
                    resId = isTrash ? (doTrash ? R.string.permission_progress_trash_audio :
                            R.string.permission_progress_untrash_audio) : R.string.permission_progress_delete_audio;
                    break;
                case DATA_IMAGE:
                    resId = isTrash ? (doTrash ? R.string.permission_progress_trash_image :
                            R.string.permission_progress_untrash_image) : R.string.permission_progress_delete_image;
                    break;
                case DATA_GENERIC:
                    resId = isTrash ? (doTrash ? R.string.permission_progress_trash_generic :
                            R.string.permission_progress_untrash_generic) : R.string.permission_progress_delete_generic;
                    break;
            }
        } else {
            switch (data) {
                case DATA_VIDEO:
                    resId = isTrash ? (doTrash ? R.plurals.permission_progress_trash_video :
                            R.plurals.permission_progress_untrash_video) : R.plurals.permission_progress_delete_video;
                    break;
                case DATA_AUDIO:
                    resId = isTrash ? (doTrash ? R.plurals.permission_progress_trash_audio :
                            R.plurals.permission_progress_untrash_audio) : R.plurals.permission_progress_delete_audio;
                    break;
                case DATA_IMAGE:
                    resId = isTrash ? (doTrash ? R.plurals.permission_progress_trash_image :
                            R.plurals.permission_progress_untrash_image) : R.plurals.permission_progress_delete_image;
                    break;
                case DATA_GENERIC:
                    resId = isTrash ? (doTrash ? R.plurals.permission_progress_trash_generic :
                            R.plurals.permission_progress_untrash_generic) : R.plurals.permission_progress_delete_generic;
                    break;
            }
        }
        if (resId != 0) {
            final int count = uris.size();
            final CharSequence text = getICUFormatString(getResources(), count, resId);
            return TextUtils.expandTemplate(text, String.valueOf(count));
        } else {
            throw new IllegalStateException("Invalid resource: " + data);
        }
    }

    /**
     * Recursively walk the given view hierarchy looking for the first
     * {@link View} which matches the given predicate.
     */
    private static @Nullable View findViewByPredicate(@NonNull View root,
                                                      @NonNull Predicate<View> predicate) {
        if (predicate.test(root)) {
            return root;
        }
        if (root instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                final View res = findViewByPredicate(group.getChildAt(i), predicate);
                if (res != null) {
                    return res;
                }
            }
        }
        return null;
    }

    interface Predicate<T> {
        boolean test(T obj);
    }

    /**
     * Task that will load a set of {@link Description} to be eventually
     * displayed in the body of the dialog.
     */
    private class DescriptionTask extends AsyncTask<List<Uri>, Void, List<Description>> {
        private final View bodyView;
        private final Context context;
        private final Resources res;

        public DescriptionTask(@NonNull View bodyView) {
            this.bodyView = bodyView;
            this.context = bodyView.getContext();
            this.res = context.getResources();
        }

        @Override
        protected List<Description> doInBackground(List<Uri>... params) {
            final ArrayList<Uri> uris = new ArrayList<>(params[0]);
            final List<Description> res = new ArrayList<>();

            // If the size is zero, return the res directly.
            if (uris.isEmpty()) {
                return res;
            }

            // Default information that we'll load for each item
            int loadFlags = Description.LOAD_THUMBNAIL | Description.LOAD_CONTENT_DESCRIPTION;
            int neededThumbs = MAX_THUMBS;

            // If we're only asking for single item, load the full image
            if (uris.size() == 1) {
                loadFlags |= Description.LOAD_FULL;
            }

            // Sort the uris in DATA_GENERIC case (Image, Video, Audio, Others)
            if (TextUtils.equals(data, DATA_GENERIC) && uris.size() > 1) {
                final Function<Uri, Integer> score = (uri) -> {
                    final int match = matchUri(uri);

                    switch (match) {
                        case AUDIO_MEDIA_ID:
                            return ORDER_AUDIO;
                        case VIDEO_MEDIA_ID:
                            return ORDER_VIDEO;
                        case IMAGES_MEDIA_ID:
                            return ORDER_IMAGE;
                        default:
                            return ORDER_GENERIC;
                    }
                };
                //noinspection ComparatorCombinators
                Collections.sort(uris, (c1, c2) -> Integer.compare(score.apply(c1), score.apply(c2)));
            }

            for (Uri uri : uris) {
                try {
                    final Description desc = new Description(context, uri, loadFlags);
                    res.add(desc);

                    // Once we've loaded enough information to bind our UI, we
                    // can skip loading data for remaining requested items, but
                    // we still need to create them to show the correct counts
                    if (desc.isVisual()) {
                        neededThumbs--;
                    }
                    if (neededThumbs == 0) {
                        loadFlags = 0;
                    }
                } catch (Exception e) {
                    // Keep rolling forward to try getting enough descriptions
                    Log.w(TAG, "failed to get description", e);
                }
            }
            return res;
        }

        @Override
        protected void onPostExecute(List<Description> results) {
            // Decide how to bind results based on how many are visual
            final ArrayList<Description> visualResults = new ArrayList<>();
            for (Description result : results) {
                if (result.isVisual())
                    visualResults.add(result);
            }
            if (results.size() == 1 && visualResults.size() == 1) {
                bindAsFull(results.get(0));
            } else if (!visualResults.isEmpty()) {
                bindAsThumbs(results, visualResults);
            } else {
                bindAsText(results);
            }

            // This is pretty hacky, but somehow our dynamic loading of content
            // can confuse accessibility focus, so refocus on the actual dialog
            // title to announce ourselves properly
            titleView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        }

        /**
         * Bind dialog as a single full-bleed image. If there is no image, use
         * the icon of Mime type instead.
         */
        private void bindAsFull(@NonNull Description result) {
            final ImageView thumbFull = bodyView.findViewById(R.id.thumb_full);
            if (result.full != null) {
                result.bindFull(thumbFull);
            } else if (result.thumbnail != null) {
                result.bindThumbnail(thumbFull, /* shouldClip */ false);
            } else if (result.mimeIcon != null) {
                thumbFull.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumbFull.setBackground(new ColorDrawable(ContextCompat.getColor(
                        DeleteUiActivity.this, R.color.thumb_gray_color)));
                result.bindMimeIcon(thumbFull);
            }
        }

        /**
         * Bind dialog as a list of multiple thumbnails. If there is no thumbnail for some
         * items, use the icons of the MIME type instead.
         */
        private void bindAsThumbs(@NonNull List<Description> results,
                                  @NonNull List<Description> visualResults) {
            final List<ImageView> thumbs = new ArrayList<>();
            thumbs.add(bodyView.findViewById(R.id.thumb1));
            thumbs.add(bodyView.findViewById(R.id.thumb2));
            thumbs.add(bodyView.findViewById(R.id.thumb3));

            // We're going to show the "more" tile when we can't display
            // everything requested, but we have at least one visual item
            final boolean showMore = (visualResults.size() != results.size())
                    || (visualResults.size() > MAX_THUMBS);
            if (showMore) {
                final View thumbMoreContainer = bodyView.findViewById(R.id.thumb_more_container);
                final ImageView thumbMore = bodyView.findViewById(R.id.thumb_more);
                final TextView thumbMoreText = bodyView.findViewById(R.id.thumb_more_text);
                final View gradientView = bodyView.findViewById(R.id.thumb_more_gradient);

                // Since we only want three tiles displayed maximum, swap out
                // the first tile for our "more" tile
                thumbs.remove(0);
                thumbs.add(thumbMore);

                final int shownCount = Math.min(visualResults.size(), MAX_THUMBS - 1);
                final int moreCount = results.size() - shownCount;
                final CharSequence moreText =
                        TextUtils.expandTemplate(
                                getICUFormatString(
                                        res, moreCount,
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                                                R.string.permission_more_thumb
                                        :  R.plurals.permission_more_thumb),
                                String.valueOf(moreCount));
                thumbMoreText.setText(moreText);
                thumbMoreContainer.setVisibility(View.VISIBLE);
                gradientView.setVisibility(View.VISIBLE);
            }

            // Trim off extra thumbnails from the front of our list, so that we
            // always bind any "more" item last
            while (thumbs.size() > visualResults.size()) {
                thumbs.remove(0);
            }

            // Finally we can bind all our thumbnails into place
            for (int i = 0; i < thumbs.size(); i++) {
                final Description desc = visualResults.get(i);
                final ImageView imageView = thumbs.get(i);
                if (desc.thumbnail != null) {
                    desc.bindThumbnail(imageView, /* shouldClip */ true);
                } else if (desc.mimeIcon != null) {
                    desc.bindMimeIcon(imageView);
                }
            }
        }

        /**
         * Bind dialog as a list of text descriptions, typically when there's no
         * visual representation of the items.
         */
        private void bindAsText(@NonNull List<Description> results) {
            final List<CharSequence> list = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                if (TextUtils.isEmpty(results.get(i).contentDescription)) {
                    continue;
                }
                list.add(results.get(i).contentDescription);

                if (list.size() >= MAX_THUMBS && results.size() > list.size()) {
                    final int moreCount = results.size() - list.size();
                    final CharSequence moreText =
                            TextUtils.expandTemplate(
                                    getICUFormatString(
                                            res, moreCount, Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ?
                                                    R.string.permission_more_text
                                                    :  R.plurals.permission_more_text),
                                    String.valueOf(moreCount));
                    list.add(moreText);
                    break;
                }
            }
            if (!list.isEmpty()) {
                final TextView text = bodyView.findViewById(R.id.list);
                text.setText(TextUtils.join("\n", list));
                text.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Description of a single media item.
     */
    private static class Description {
        public @Nullable CharSequence contentDescription;
        public @Nullable Bitmap thumbnail;
        public @Nullable Bitmap full;
        public @Nullable Drawable mimeIcon;

        public static final int LOAD_CONTENT_DESCRIPTION = 1;
        public static final int LOAD_THUMBNAIL = 1 << 1;
        public static final int LOAD_FULL = 1 << 2;

        public Description(Context context, Uri uri, int loadFlags) {
            final Resources res = context.getResources();
            final ContentResolver resolver = context.getContentResolver();

            try {
                // Load description first so that we'll always have something
                // textual to display in case we have image trouble below
                if ((loadFlags & LOAD_CONTENT_DESCRIPTION) != 0) {
                    try (Cursor c = resolver.query(uri,
                            new String[]{MediaColumns.DISPLAY_NAME}, null,
                            null, null, null)) {
                        assert c != null;
                        if (c.moveToFirst()) {
                            contentDescription = c.getString(0);
                        }
                    }
                }
                if ((loadFlags & LOAD_THUMBNAIL) != 0) {
                    //noinspection SuspiciousNameCombination
                    final Size size = new Size(res.getDisplayMetrics().widthPixels,
                            res.getDisplayMetrics().widthPixels);
                    thumbnail = MediaStoreCompat.loadThumbnail(context, uri, size, null);
                }
                if ((loadFlags & LOAD_FULL) != 0) {
                    // Only offer full decodes when a supported file type;
                    // otherwise fall back to using thumbnail
                    final String mimeType = resolver.getType(uri);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mimeType != null &&
                            ImageDecoder.isMimeTypeSupported(mimeType)) {
                        full = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                                new Resizer(context.getResources().getDisplayMetrics()));
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && mimeType != null &&
                            mimeType.startsWith("image/")) {
                        ParcelFileDescriptor pfdInput = resolver.openFileDescriptor(uri, "r");
                        if (pfdInput != null) {
                            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                            final int maxSize = Math.max(metrics.widthPixels, metrics.heightPixels);
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inJustDecodeBounds = true;
                            BitmapFactory.decodeFileDescriptor(pfdInput.getFileDescriptor(),
                                    null, opts);
                            opts.inJustDecodeBounds = false;
                            final int widthSample = opts.outWidth / maxSize;
                            final int heightSample = opts.outHeight / maxSize;
                            opts.inSampleSize = Math.max(widthSample, heightSample);
                            full = BitmapFactory.decodeFileDescriptor(
                                    pfdInput.getFileDescriptor(), null, opts);
                            pfdInput.close();
                            if (full == null) {
                                full = thumbnail;
                            }
                        } else {
                            full = thumbnail;
                        }
                    } else {
                        full = thumbnail;
                    }
                }
            } catch (IOException e) {
                Log.w(TAG, "failed decode thumbnail", e);
                if (thumbnail == null && full == null) {
                    final String mimeType = resolver.getType(uri);
                    if (mimeType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        mimeIcon = resolver.getTypeInfo(mimeType).getIcon().loadDrawable(context);
                    } else if (mimeType != null) {
                        try {
                            //noinspection JavaReflectionMemberAccess
                            mimeIcon = (Drawable) ContentResolver.class
                                    .getMethod("getTypeDrawable", String.class)
                                            .invoke(context.getContentResolver(), mimeType);
                        } catch (Exception ex) {
                            Log.w(TAG, "failed to load mime drawable", ex);
                        }
                    }
                }
            }
        }

        public boolean isVisual() {
            return thumbnail != null || full != null || mimeIcon != null;
        }

        public void bindThumbnail(ImageView imageView, boolean shouldClip) {
            Objects.requireNonNull(thumbnail);
            imageView.setImageBitmap(thumbnail);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
            imageView.setClipToOutline(shouldClip);
        }

        public void bindFull(ImageView imageView) {
            Objects.requireNonNull(full);
            imageView.setImageBitmap(full);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
        }

        public void bindMimeIcon(ImageView imageView) {
            Objects.requireNonNull(mimeIcon);
            imageView.setImageDrawable(mimeIcon);
            imageView.setContentDescription(contentDescription);
            imageView.setVisibility(View.VISIBLE);
            imageView.setClipToOutline(true);
        }
    }

    /**
     * Utility that will speed up decoding of large images, since we never need
     * them to be larger than the screen dimensions.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private static class Resizer implements ImageDecoder.OnHeaderDecodedListener {
        private final int maxSize;

        public Resizer(DisplayMetrics metrics) {
            this.maxSize = Math.max(metrics.widthPixels, metrics.heightPixels);
        }

        @Override
        public void onHeaderDecoded(@NonNull ImageDecoder decoder, ImageInfo info, @NonNull Source source) {
            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final int widthSample = info.getSize().getWidth() / maxSize;
            final int heightSample = info.getSize().getHeight() / maxSize;
            final int sample = Math.max(widthSample, heightSample);
            if (sample > 1) {
                decoder.setTargetSampleSize(sample);
            }
        }
    }

    /**
     * Returns the formatted ICU format string corresponding to the provided resource ID and count
     * number of entities in the plural string.
     */
    private static String getICUFormatString(Resources resources, int count, int resourceID) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return resources.getQuantityString(resourceID, count);
        }
        MessageFormat msgFormat = new MessageFormat(
                resources.getString(resourceID),
                Locale.getDefault());
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", count);
        return msgFormat.format(arguments);
    }

    private static final int AUDIO_MEDIA_ID = 1;
    private static final int AUDIO_PLAYLISTS_ID = 2;
    private static final int VIDEO_MEDIA_ID = 3;
    private static final int IMAGES_MEDIA_ID = 4;
    private static final int FILES_ID = 5;

    private static int matchUri(Uri uri) {
        if (uri.getPathSegments().get(1).equals(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.getPathSegments().get(1))) {
            if (uri.getPathSegments().get(2).equals(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.getPathSegments().get(2))) {
                return AUDIO_MEDIA_ID;
            } else {
                return AUDIO_PLAYLISTS_ID;
            }
        } else if (uri.getPathSegments().get(1).equals(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.getPathSegments().get(1))) {
            return VIDEO_MEDIA_ID;
        } else if (uri.getPathSegments().get(1).equals(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI.getPathSegments().get(1))) {
            return IMAGES_MEDIA_ID;
        } else if (uri.getPathSegments().get(1).equals(
                MediaStoreCompat.FILES_EXTERNAL_CONTENT_URI.getPathSegments().get(1))) {
            return FILES_ID;
        }
        throw new IllegalArgumentException("Bad uri: " + uri);
    }
}