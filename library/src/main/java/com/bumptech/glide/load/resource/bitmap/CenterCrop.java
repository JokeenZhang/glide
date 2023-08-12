package com.bumptech.glide.load.resource.bitmap;

import android.content.Context;
import android.graphics.Bitmap;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;

/**
 * Scale the image so that either the width of the image matches the given width and the height of the image is
 * greater than the given height or vice versa, and then crop the larger dimension to match the given dimension.
 *
 * Does not maintain the image's aspect ratio
 */
public class CenterCrop extends BitmapTransformation {

    public CenterCrop(Context context) {
        super(context);
    }

    public CenterCrop(BitmapPool bitmapPool) {
        super(bitmapPool);
    }

    // Bitmap doesn't implement equals, so == and .equals are equivalent here.
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    @Override
    protected Bitmap transform(BitmapPool pool, Bitmap toTransform, int outWidth, int outHeight) {
        //从缓存池里尝试获取一个可重用的Bitmap
        final Bitmap toReuse = pool.get(outWidth, outHeight, toTransform.getConfig() != null
                ? toTransform.getConfig() : Bitmap.Config.ARGB_8888);
        Bitmap transformed = TransformationUtils.centerCrop(toReuse, toTransform, outWidth, outHeight);
        //调用put，将复用的Bitmap放到缓存池里，而不是将裁剪Bitmap添加到池子里
        if (toReuse != null && toReuse != transformed && !pool.put(toReuse)) {
            toReuse.recycle();
        }
        return transformed;
    }

    @Override
    public String getId() {
        return "CenterCrop.com.bumptech.glide.load.resource.bitmap";
    }
}
