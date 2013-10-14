package com.koushikdutta.ion;

import android.graphics.Bitmap;
import android.util.Log;

import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.http.ResponseCacheMiddleware;
import com.koushikdutta.async.http.libcore.DiskLruCache;
import com.koushikdutta.ion.bitmap.BitmapInfo;
import com.koushikdutta.ion.bitmap.Transform;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;

class BitmapToBitmapInfo extends BitmapCallback implements FutureCallback<BitmapInfo> {
    ArrayList<Transform> transforms;

    public static void getBitmapSnapshot(final Ion ion, final String transformKey) {
        ion.getServer().getExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                final LoadBitmap callback = new LoadBitmap(ion, transformKey, true, -1, -1, Loader.LoaderEmitter.LOADED_FROM_CACHE);

                try {
                    DiskLruCache.Snapshot snapshot = ion.getResponseCache().getDiskLruCache().get(transformKey);
                    try {
                        InputStream in = snapshot.getInputStream(0);
                        assert in instanceof FileInputStream;
                        int available = in.available();
                        ByteBuffer b = ByteBufferList.obtain(available);
                        new DataInputStream(in).readFully(b.array(), 0, available);
                        b.limit(available);
                        callback.onCompleted(null, new ByteBufferList(b));
                    }
                    finally {
                        snapshot.close();
                    }
                }
                catch (Exception e) {
                    callback.onCompleted(e, null);
                    try {
                        ion.getResponseCache().getDiskLruCache().remove(transformKey);
                    }
                    catch (Exception ex) {
                    }
                }
            }
        });
    }

    String downloadKey;
    public BitmapToBitmapInfo(Ion ion, String transformKey, String downloadKey, ArrayList<Transform> transforms) {
        super(ion, transformKey, true);
        this.transforms = transforms;
        this.downloadKey = downloadKey;

        ion.bitmapsPending.tag(transformKey, this);
    }

    @Override
    public void onCompleted(Exception e, final BitmapInfo result) {
        if (e != null) {
            report(e, null);
            return;
        }

        if (ion.bitmapsPending.tag(key) != this) {
            Log.d("IonBitmapLoader", "Bitmap transform cancelled (no longer needed)");
            return;
        }

        ion.getServer().getExecutorService().execute(new Runnable() {
            @Override
            public void run() {
                BitmapInfo info = new BitmapInfo();
                try {
                    Bitmap tmpBitmap = result.bitmap;
                    for (Transform transform : transforms) {
//                            builder.request.logd("applying transform: " + transform.key());
                        tmpBitmap = transform.transform(tmpBitmap);
                    }
                    info.loadedFrom = result.loadedFrom;
                    info.bitmap = tmpBitmap;
                    info.key = key;
                    report(null, info);
                } catch (Exception e) {
                    report(e, null);
                    return;
                }
                // the transformed bitmap was successfully load it, let's toss it into
                // the disk lru cache.
                try {
                    DiskLruCache cache = ion.getResponseCache().getDiskLruCache();
                    if (cache == null)
                        return;
                    DiskLruCache.Editor editor = cache.edit(key);
                    if (editor == null)
                        return;
                    try {
                        for (int i = 1; i < ResponseCacheMiddleware.ENTRY_COUNT; i++) {
                            editor.set(i, key);
                        }
                        OutputStream out = editor.newOutputStream(0);
                        Bitmap.CompressFormat format = info.bitmap.hasAlpha() ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                        info.bitmap.compress(format, 100, out);
                        out.close();
                        editor.commit();
                    }
                    catch (Exception ex) {
                        editor.abort();
                    }
                }
                catch (Exception e) {
                }
            }
        });
    }
}