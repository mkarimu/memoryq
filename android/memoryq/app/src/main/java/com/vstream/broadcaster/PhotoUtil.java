package com.vstream.broadcaster;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Opens a photo from the phone, turns it the right way up, shrinks it and saves it as a JPEG for uploading. */
final class PhotoUtil {

    static final class Photo {
        final byte[] jpeg;
        final Bitmap bitmap;

        Photo(byte[] jpeg, Bitmap bitmap) {
            this.jpeg = jpeg;
            this.bitmap = bitmap;
        }
    }

    private PhotoUtil() {
    }

    /** Call off the main thread. Returns null if the photo cannot be opened. */
    static Photo load(Context c, Uri uri, int maxSide, int maxBytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            if (b == null) {
                return null;
            }
            int rotate = 0;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL);
                    rotate = o == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                            : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                            : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
                }
            } catch (Exception e) {
                rotate = 0;
            }
            float scale = Math.min(1f, maxSide / (float) Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f || rotate != 0) {
                Matrix m = new Matrix();
                m.postScale(scale, scale);
                m.postRotate(rotate);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int quality = 85;
            b.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
            while (bytes.size() > maxBytes && quality > 40) {
                quality -= 15;
                bytes.reset();
                b.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
            }
            return new Photo(bytes.toByteArray(), b);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}