/*
 * Copyright (C) 2009 The Android Open Source Project
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

import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT;
import static android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH;
import static android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC;
import static android.os.Environment.MEDIA_UNKNOWN;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.ImageDecoder.ImageInfo;
import android.graphics.ImageDecoder.Source;
import android.graphics.Matrix;
//noinspection ExifInterface
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Size;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.util.Function;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

/**
 * Utilities for generating visual thumbnails from files.
 * <p>
 * The advantage over {@link MediaStoreCompat#loadThumbnail(Context, Uri, Size)} is that it
 * supports high-resolution thumbnails by decoding files directly. The disadvantage is that you do
 * not profit from MediaStore's disk cache when using this.
 *
 * @see ThumbnailUtils
 */
public class ThumbnailUtilsCompat {
    private static final String TAG = "ThumbnailUtilsCompat";

    @RequiresApi(api = Build.VERSION_CODES.P)
    private static class Resizer implements ImageDecoder.OnHeaderDecodedListener {
        private final Size size;
        private final CancellationSignal signal;

        public Resizer(Size size, CancellationSignal signal) {
            this.size = size;
            this.signal = signal;
        }

        @Override
        public void onHeaderDecoded(@NonNull ImageDecoder decoder, @NonNull ImageInfo info,
                                    @NonNull Source source) {
            // One last-ditch check to see if we've been canceled.
            if (signal != null) signal.throwIfCanceled();

            // We don't know how clients will use the decoded data, so we have
            // to default to the more flexible "software" option.
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);

            // We requested a rough thumbnail size, but the remote size may have
            // returned something giant, so defensively scale down as needed.
            final int widthSample = info.getSize().getWidth() / size.getWidth();
            final int heightSample = info.getSize().getHeight() / size.getHeight();
            final int sample = Math.max(widthSample, heightSample);
            if (sample > 1) {
                decoder.setTargetSampleSize(sample);
            }
        }
    }

    /**
     * Create a thumbnail for given audio file.
     * <p>
     * This method should only be used for files that you have direct access to;
     * if you'd like to work with media hosted outside your app, consider using
     * {@link MediaStoreCompat#loadThumbnail(Context, Uri, Size)}
     * which enables remote providers to efficiently cache and invalidate
     * thumbnails.
     *
     * @param file The audio file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q, message = "since Q, this just calls platform")
    public static @NonNull Bitmap createAudioThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createAudioThumbnail(file, size, signal);
        }
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(file.getAbsolutePath());
            final byte[] raw = retriever.getEmbeddedPicture();
            if (raw != null) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(
                            ByteBuffer.wrap(raw)), new Resizer(size, signal));
                } else {
                    Bitmap out = decodeBitmap(raw, size, signal);
                    if (out == null) {
                        throw new IOException("Failed to decode bitmap");
                    }
                    return out;
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }

        // Only poke around for files on external storage
        if (MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(file))) {
            throw new IOException("No embedded album art found");
        }

        // Ignore "Downloads" or top-level directories
        final File parent = file.getParentFile();
        final File grandParent = parent != null ? parent.getParentFile() : null;
        if (parent != null
                && parent.getName().equals(Environment.DIRECTORY_DOWNLOADS)) {
            throw new IOException("No thumbnails in Downloads directories");
        }
        if (grandParent != null
                && MEDIA_UNKNOWN.equals(Environment.getExternalStorageState(grandParent))) {
            throw new IOException("No thumbnails in top-level directories");
        }

        // If no embedded image found, look around for best standalone file
        final File[] found = defeatNullable(Objects.requireNonNull(file.getParentFile())
                .listFiles((dir, name) -> {
                    final String lower = name.toLowerCase();
                    return (lower.endsWith(".jpg") || lower.endsWith(".png"));
                }));

        //noinspection ExtractMethodRecommender
        final Function<File, Integer> score = (f) -> {
            // Slightly modified to match MediaProvider.getCompressedAlbumArt() from P
            final String lower = f.getName().toLowerCase();
            if (lower.equals("albumart.jpg")) return 4;
            if (lower.startsWith("albumart") && lower.endsWith("large.jpg")) return 3;
            if (lower.contains("albumart") && lower.endsWith(".jpg")) return 2;
            if (lower.endsWith(".jpg")) return 1;
            return 0;
        };
        @SuppressWarnings("ComparatorCombinators")
        final Comparator<File> bestScore = (a, b) -> score.apply(a) - score.apply(b);

        final File bestFile = found.length > 0 ? Collections.max(Arrays.asList(found), bestScore)
                : null;
        if (bestFile == null) {
            throw new IOException("No album art found");
        }

        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(bestFile),
                    new Resizer(size, signal));
        } else {
            Bitmap out = decodeBitmap(bestFile, size, signal);
            if (out == null) {
                throw new IOException("Failed to decode bitmap");
            }
            return out;
        }
    }

    /**
     * Create a thumbnail for given image file.
     * <p>
     * This method should only be used for files that you have direct access to;
     * if you'd like to work with media hosted outside your app, consider using
     * {@link MediaStoreCompat#loadThumbnail(Context, Uri, Size)}
     * which enables remote providers to efficiently cache and invalidate
     * thumbnails.
     *
     * @param file The image file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q, message = "since Q, this just calls platform")
    public static @NonNull Bitmap createImageThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createImageThumbnail(file, size, signal);
        }
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        final String mimeType = MediaStoreCompat.guessMimeTypeFromFileName(file.getName());
        Bitmap bitmap = null;
        ExifInterface exif = null;
        int orientation = 0;

        // get orientation
        if (MediaStoreCompat.getMediaTypeForMime(mimeType) == MediaStoreCompat.MEDIA_TYPE_IMAGE) {
            exif = new ExifInterface(file.getAbsolutePath());
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    orientation = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    orientation = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    orientation = 270;
                    break;
            }
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && (mimeType.equals("image/heif")
                || mimeType.equals("image/heif-sequence")
                || mimeType.equals("image/heic")
                || mimeType.equals("image/heic-sequence")
                || mimeType.equals("image/avif"))) {
            try {
                @SuppressWarnings("JavaReflectionMemberAccess")
                Method m = ThumbnailUtils.class.getMethod(
                        "createThumbnailFromMetadataRetriever", String.class, Integer.class,
                        Integer.class);
                m.setAccessible(true);
                bitmap = (Bitmap) m.invoke(null, file.getAbsolutePath(), size.getWidth(),
                        size.getWidth() * size.getHeight());
            } catch (Exception e) {
                Log.w(TAG, "failed to get heif/avif thumb", e);
            }
        }

        if (bitmap == null && exif != null) {
            byte[] raw;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                raw = exif.getThumbnailBytes();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    long[] offset = exif.getThumbnailRange();
                    if (offset != null && offset[0] > 0 && offset[1] <= 64 * 1024) {
                        raw = new byte[(int) offset[1]];
                        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file,
                                ParcelFileDescriptor.MODE_READ_ONLY);
                        Os.lseek(fd.getFileDescriptor(), offset[0], OsConstants.SEEK_SET);
                        if (Os.read(fd.getFileDescriptor(), raw, 0, raw.length)
                                != raw.length) {
                            raw = null;
                        }
                        fd.close();
                    } else {
                        raw = exif.getThumbnail();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "failed to get thumbnail from offset", e);
                    raw = exif.getThumbnail();
                }
            } else {
                raw = exif.getThumbnail();
            }
            if (raw != null) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                    try {
                        bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(
                                ByteBuffer.wrap(raw)), new Resizer(size, signal));
                    } catch (ImageDecoder.DecodeException e) {
                        Log.w(TAG, "failed to decode bitmap", e);
                    }
                } else {
                    bitmap = decodeBitmap(raw, size, signal);
                }
            }
        }

        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        if (bitmap == null) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file),
                        new Resizer(size, signal));
            } else {
                Bitmap out = decodeBitmap(file, size, signal);
                if (out == null) {
                    throw new IOException("Failed to decode bitmap");
                }
                return out;
            }
        }

        // Transform the bitmap if the orientation of the image is not 0.
        if (orientation != 0) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();

            final Matrix m = new Matrix();
            m.setRotate(orientation, width / 2f, height / 2f);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, m, false);
        }

        return bitmap;
    }

    /**
     * Create a thumbnail for given video file.
     * <p>
     * This method should only be used for files that you have direct access to;
     * if you'd like to work with media hosted outside your app, consider using
     * {@link MediaStoreCompat#loadThumbnail(Context, Uri, Size)}
     * which enables remote providers to efficiently cache and invalidate
     * thumbnails.
     *
     * @param file The video file.
     * @param size The desired thumbnail size.
     * @throws IOException If any trouble was encountered while generating or
     *             loading the thumbnail, or if
     *             {@link CancellationSignal#cancel()} was invoked.
     */
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q, message = "since Q, this just calls platform")
    public static @NonNull Bitmap createVideoThumbnail(@NonNull File file, @NonNull Size size,
            @Nullable CancellationSignal signal) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ThumbnailUtils.createVideoThumbnail(file, size, signal);
        }
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();

        try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
            mmr.setDataSource(file.getAbsolutePath());

            // Try to retrieve thumbnail from metadata
            final byte[] raw = mmr.getEmbeddedPicture();
            if (raw != null) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(
                            ByteBuffer.wrap(raw)), new Resizer(size, signal));
                } else {
                    Bitmap out = decodeBitmap(raw, size, signal);
                    if (out == null) {
                        throw new IOException("Failed to decode bitmap");
                    }
                    return out;
                }
            }

            final int width = Integer.parseInt(Objects.requireNonNull(
                    mmr.extractMetadata(METADATA_KEY_VIDEO_WIDTH)));
            final int height = Integer.parseInt(Objects.requireNonNull(
                    mmr.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)));
            // Returns whatever frame the implementation considers representative.
            final long thumbnailTimeUs = -1;

            // If we're okay with something larger than native format, just
            // return a frame without up-scaling it
            if (size.getWidth() > width && size.getHeight() > height) {
                return Objects.requireNonNull(
                        mmr.getFrameAtTime(thumbnailTimeUs, OPTION_CLOSEST_SYNC));
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    return Objects.requireNonNull(
                            mmr.getScaledFrameAtTime(thumbnailTimeUs, OPTION_CLOSEST_SYNC,
                            size.getWidth(), size.getHeight()));
                } else {
                    Bitmap bitmap = Objects.requireNonNull(
                            mmr.getFrameAtTime(thumbnailTimeUs, OPTION_CLOSEST_SYNC));
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, size.getWidth(),
                            size.getHeight(), true);
                    bitmap.recycle();
                    return scaledBitmap;
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to create thumbnail", e);
        }
    }

    private static @NonNull File[] defeatNullable(@Nullable File[] val) {
        return (val != null) ? val : new File[0];
    }

    private static @Nullable Bitmap decodeBitmap(@NonNull File file, @NonNull Size size,
                                                 @Nullable CancellationSignal signal) {
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();
        final int maxSize = Math.max(size.getWidth(), size.getHeight());
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        opts.inJustDecodeBounds = false;
        final int widthSample = opts.outWidth / maxSize;
        final int heightSample = opts.outHeight / maxSize;
        opts.inSampleSize = Math.max(widthSample, heightSample);
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static @Nullable Bitmap decodeBitmap(@NonNull byte[] raw, @NonNull Size size,
                                                 @Nullable CancellationSignal signal) {
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();
        final int maxSize = Math.max(size.getWidth(), size.getHeight());
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
        opts.inJustDecodeBounds = false;
        final int widthSample = opts.outWidth / maxSize;
        final int heightSample = opts.outHeight / maxSize;
        opts.inSampleSize = Math.max(widthSample, heightSample);
        // Checkpoint before going deeper
        if (signal != null) signal.throwIfCanceled();
        return BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    }
}
