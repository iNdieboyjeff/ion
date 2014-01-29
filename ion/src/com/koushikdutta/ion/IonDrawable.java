package com.koushikdutta.ion;

import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.animation.Animation;
import android.widget.ImageView;

import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;
import com.koushikdutta.ion.bitmap.BitmapInfo;

import java.lang.ref.WeakReference;

/**
 * Created by koush on 6/8/13.
 */
class IonDrawable extends Drawable {
    private Paint paint;
    private BitmapInfo info;
    private int placeholderResource;
    private Drawable placeholder;
    private int errorResource;
    private Drawable error;
    private Resources resources;
    private int loadedFrom;
    private IonDrawableCallback callback;
    private boolean disableFadeIn;
    private int resizeWidth;
    private int resizeHeight;
    private boolean mipmap;

    public IonDrawable cancel() {
        requestCount++;
        return this;
    }

    public SimpleFuture<ImageView> getFuture() {
        return callback.imageViewFuture;
    }
    
    public IonDrawable setDisableFadeIn(boolean disableFadeIn) {
        this.disableFadeIn = disableFadeIn;
        return this;
    }

    public IonDrawable setInAnimation(Animation inAnimation, int inAnimationResource) {
        callback.inAnimation = inAnimation;
        callback.inAnimationResource = inAnimationResource;
        return this;
    }

    // create an internal static class that can act as a callback.
    // dont let it hold strong references to anything.
    static class IonDrawableCallback implements FutureCallback<BitmapInfo> {
        private WeakReference<IonDrawable> ionDrawableRef;
        private WeakReference<ImageView> imageViewRef;
        private String bitmapKey;
        private SimpleFuture<ImageView> imageViewFuture = new SimpleFuture<ImageView>();
        private Animation inAnimation;
        private int inAnimationResource;
        private int requestId;

        public IonDrawableCallback(IonDrawable drawable, ImageView imageView) {
            ionDrawableRef = new WeakReference<IonDrawable>(drawable);
            imageViewRef = new WeakReference<ImageView>(imageView);
        }

        @Override
        public void onCompleted(Exception e, BitmapInfo result) {
            assert Thread.currentThread() == Looper.getMainLooper().getThread();
            assert result != null;
            // see if the imageview is still alive and cares about this result
            ImageView imageView = imageViewRef.get();
            if (imageView == null)
                return;

            IonDrawable drawable = ionDrawableRef.get();
            if (drawable == null)
                return;

            if (imageView.getDrawable() != drawable)
                return;

            // see if the ImageView is still waiting for the same request
            if (drawable.requestCount != requestId)
                return;

            imageView.setImageDrawable(null);
            drawable.setBitmap(result, result.loadedFrom);
            imageView.setImageDrawable(drawable);
            IonBitmapRequestBuilder.doAnimation(imageView, inAnimation, inAnimationResource);

            if (!IonRequestBuilder.checkContext(imageView.getContext())) {
                imageViewFuture.cancel();
                return;
            }

            imageViewFuture.setComplete(e, imageView);
        }
    }

    int requestCount;
    public void register(Ion ion, String bitmapKey) {
        callback.requestId = ++requestCount;
        String previousKey = callback.bitmapKey;
        if (TextUtils.equals(previousKey, bitmapKey))
            return;
        callback.bitmapKey = bitmapKey;
        ion.bitmapsPending.add(bitmapKey, callback);
        if (previousKey == null)
            return;

        // unregister this drawable from the bitmaps that are
        // pending.
        Object owner = ion.bitmapsPending.removeItem(previousKey, callback);

        // if this drawable was the only thing waiting for this bitmap,
        // then the removeItem call will return the TransformBitmap/LoadBitmap instance
        // that was providing the result.

        // cancel/remove the transform
        if (owner instanceof TransformBitmap) {
            TransformBitmap info = (TransformBitmap)owner;
            // this transform is also backed by a LoadBitmap, grab that
            // if it is the only waiter
            ion.bitmapsPending.removeItem(info.downloadKey, info);
        }

        ion.processDeferred();
    }

    private static final int DEFAULT_PAINT_FLAGS = Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG;

    public IonDrawable(Resources resources, ImageView imageView) {
        this.resources = resources;
        paint = new Paint(DEFAULT_PAINT_FLAGS);
        callback = new IonDrawableCallback(this, imageView);
    }

    int currentFrame;
    private boolean invalidateScheduled;
    public IonDrawable setBitmap(BitmapInfo info, int loadedFrom) {
        this.loadedFrom = loadedFrom;
        requestCount++;

        if (this.info == info)
            return this;

        invalidateSelf();

        this.info = info;
        currentFrame = 0;
        invalidateScheduled = false;
        if (info == null) {
            callback.bitmapKey = null;
            return this;
        }

        callback.bitmapKey = info.key;
        return this;
    }

    public IonDrawable setSize(int resizeWidth, int resizeHeight) {
        if (this.resizeWidth == resizeWidth && this.resizeHeight == resizeHeight)
            return this;
        this.resizeWidth = resizeWidth;
        this.resizeHeight = resizeHeight;
        invalidateSelf();
        return this;
    }

    public IonDrawable setError(int resource, Drawable drawable) {
        if ((drawable != null && drawable == this.error) || (resource != 0 && resource == errorResource))
            return this;

        this.errorResource = resource;
        this.error = drawable;
        invalidateSelf();
        return this;
    }

    public IonDrawable setPlaceholder(int resource, Drawable drawable) {
        if ((drawable != null && drawable == this.placeholder) || (resource != 0 && resource == placeholderResource))
            return this;

        this.placeholderResource = resource;
        this.placeholder = drawable;
        invalidateSelf();

        return this;
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        paint.setFilterBitmap(filter);
        invalidateSelf();
    }

    @Override
    public void setDither(boolean dither) {
        paint.setDither(dither);
        invalidateSelf();
    }

    private Drawable tryGetErrorResource() {
        if (error != null)
            return error;
        if (errorResource == 0)
            return null;
        return error = resources.getDrawable(errorResource);
    }

    @Override
    public int getIntrinsicWidth() {
        if (info != null) {
            if (info.bitmaps != null)
                return info.bitmaps[0].getScaledWidth(resources.getDisplayMetrics().densityDpi);
            if (info.mipmap != null)
                return info.originalSize.x;
        }
        if (resizeWidth > 0)
            return resizeWidth;
        if (info != null) {
            Drawable error = tryGetErrorResource();
            if (error != null)
                return error.getIntrinsicWidth();
        }
        if (placeholder != null) {
            return placeholder.getIntrinsicWidth();
        } else if (placeholderResource != 0) {
            Drawable d = resources.getDrawable(placeholderResource);
            assert d != null;
            return d.getIntrinsicWidth();
        }
        return -1;
    }

    @Override
    public int getIntrinsicHeight() {
        if (info != null) {
            if (info.bitmaps != null)
                return info.bitmaps[0].getScaledHeight(resources.getDisplayMetrics().densityDpi);
            if (info.mipmap != null)
                return info.originalSize.y;
        }
        if (resizeHeight > 0)
            return resizeHeight;
        if (info != null) {
            if (error != null) {
                return error.getIntrinsicHeight();
            } else if (errorResource != 0) {
                Drawable d = resources.getDrawable(errorResource);
                assert d != null;
                return d.getIntrinsicHeight();
            }
        }
        if (placeholder != null) {
            return placeholder.getIntrinsicHeight();
        } else if (placeholderResource != 0) {
            Drawable d = resources.getDrawable(placeholderResource);
            assert d != null;
            return d.getIntrinsicHeight();
        }
        return -1;
    }

    public static final long FADE_DURATION = 200;
    private Runnable invalidate = new Runnable() {
        @Override
        public void run() {
            invalidateScheduled = false;
            currentFrame++;
            invalidateSelf();
        }
    };

    public IonDrawable setMipmap(boolean mipmap) {
        this.mipmap = mipmap;
        return this;
    }

    @Override
    public void draw(Canvas canvas) {
        if (info == null) {
            if (placeholder == null && placeholderResource != 0)
                placeholder = resources.getDrawable(placeholderResource);
            if (placeholder != null) {
                placeholder.setBounds(getBounds());
                placeholder.draw(canvas);
            }
            return;
        }

        if (info.drawTime == 0)
            info.drawTime = SystemClock.uptimeMillis();

        long destAlpha = 0xFF;

        if(!disableFadeIn) {
            destAlpha = ((SystemClock.uptimeMillis() - info.drawTime) << 8) / FADE_DURATION;
            destAlpha = Math.min(destAlpha, 0xFF);
        }

        if (destAlpha != 255) {
            if (placeholder == null && placeholderResource != 0)
                placeholder = resources.getDrawable(placeholderResource);
            if (placeholder != null) {
                placeholder.setBounds(getBounds());
                placeholder.draw(canvas);
            }
        }

        if (info.bitmaps != null) {
            paint.setAlpha((int)destAlpha);
            canvas.drawBitmap(info.bitmaps[currentFrame % info.bitmaps.length], null, getBounds(), paint);
            paint.setAlpha(0xFF);
            if (info.delays != null) {
                int delay = info.delays[currentFrame % info.delays.length];
                if (!invalidateScheduled) {
                    invalidateScheduled = true;
                    unscheduleSelf(invalidate);
                    scheduleSelf(invalidate, SystemClock.uptimeMillis() + Math.max(delay, 100));
                }
            }
        }
        else if (info.mipmap != null) {
            // zoom 0: entire image fits in a 256x256 square


            // figure out zoom level
            // figure out which tiles need rendering
            // fetch anything that needs fetching
            // draw stuff that needs drawing
            // use parent level tiles for tiles that do not exist
            // crossfading?
            System.out.println(info.mipmap);

            Rect clip = canvas.getClipBounds();
            Rect bounds = getBounds();

            float zoom = (float)canvas.getWidth() / (float)clip.width();
//            double level = Math.abs(Math.round(Math.log(zoom) / Math.log(2)));

            float zoomWidth = zoom * bounds.width();
            float zoomHeight = zoom * bounds.height();

            double wlevel = Math.log(zoomWidth / 256) / Math.log(2);
            double hlevel = Math.log(zoomHeight/ 256) / Math.log(2);
            double level = Math.max(wlevel, hlevel);


            Point p = new Point(canvas.getWidth(), canvas.getHeight());

//            System.out.println("width: " + clip.width());
//            System.out.println("height: " + clip.height());
//            System.out.println("clip: " + clip);
//            System.out.println("zoom: " + zoom);
            System.out.println("bounds: " + bounds);
            System.out.println("clip: " + clip);
            System.out.println("canvas: " + p);
            System.out.println("zoomWidth: " + zoomWidth);
            System.out.println("zoomHeight: " + zoomHeight);
            System.out.println("level: " + level);




            paint.setColor(Color.RED);
            canvas.drawRect(getBounds(), paint);
            paint.reset();
        }
        else {
            Drawable error = tryGetErrorResource();
            if (error != null) {
                error.setAlpha((int)destAlpha);
                error.setBounds(getBounds());
                error.draw(canvas);
                error.setAlpha(0xFF);
            }
        }

        if (destAlpha != 255)
            invalidateSelf();

        if (true)
            return;

        // stolen from picasso
        canvas.save();
        canvas.rotate(45);

        paint.setColor(Color.WHITE);
        canvas.drawRect(0, -10, 7.5f, 10, paint);

        int sourceColor;
        switch (loadedFrom) {
            case Loader.LoaderEmitter.LOADED_FROM_CACHE:
                sourceColor = Color.CYAN;
                break;
            case Loader.LoaderEmitter.LOADED_FROM_CONDITIONAL_CACHE:
                sourceColor = Color.YELLOW;
                break;
            case Loader.LoaderEmitter.LOADED_FROM_MEMORY:
                sourceColor = Color.GREEN;
                break;
            default:
                sourceColor = Color.RED;
                break;
        }

        paint.setColor(sourceColor);
        canvas.drawRect(0, -9, 6.5f, 9, paint);

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
       paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return (info == null || info.bitmaps == null || info.bitmaps[0].hasAlpha() || paint.getAlpha() < 255) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    static IonDrawable getOrCreateIonDrawable(ImageView imageView) {
        Drawable current = imageView.getDrawable();
        IonDrawable ret;
        if (current == null || !(current instanceof IonDrawable))
            ret = new IonDrawable(imageView.getResources(), imageView);
        else
            ret = (IonDrawable)current;
        // invalidate self doesn't seem to trigger the dimension check to be called by imageview.
        // are drawable dimensions supposed to be immutable?
        imageView.setImageDrawable(null);
        return ret;
    }
}