package com.bumptech.glide.load.resource.transcode;

import android.graphics.Bitmap;

import com.bumptech.glide.load.engine.Resource;
import com.bumptech.glide.load.resource.bitmap.GlideBitmapDrawable;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.load.resource.gifbitmap.GifBitmapWrapper;

/**
 * An {@link com.bumptech.glide.load.resource.transcode.ResourceTranscoder} that can transcode either an
 * {@link Bitmap} or an {@link com.bumptech.glide.load.resource.gif.GifDrawable} into an
 * {@link android.graphics.drawable.Drawable}.
 */
public class GifBitmapWrapperDrawableTranscoder implements ResourceTranscoder<GifBitmapWrapper, GlideDrawable> {
    private final ResourceTranscoder<Bitmap, GlideBitmapDrawable> bitmapDrawableResourceTranscoder;

    public GifBitmapWrapperDrawableTranscoder(
            ResourceTranscoder<Bitmap, GlideBitmapDrawable> bitmapDrawableResourceTranscoder) {
        this.bitmapDrawableResourceTranscoder = bitmapDrawableResourceTranscoder;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Resource<GlideDrawable> transcode(Resource<GifBitmapWrapper> toTranscode) {
        //先从Resource<GifBitmapWrapper>中取出GifBitmapWrapper对象
        GifBitmapWrapper gifBitmap = toTranscode.get();
        //然后再从GifBitmapWrapper中取出Resource<Bitmap>对象
        Resource<Bitmap> bitmapResource = gifBitmap.getBitmapResource();

        final Resource<? extends GlideDrawable> result;
        if (bitmapResource != null) {
            //如果Resource<Bitmap>不为空，那么说明此时加载的是静态图，需要再次转码，调用 GlideBitmapDrawableTranscoder 的transcode方法
            //这里完成从Resource<Bitmap>到Resource<GlideBitmapDrawable>的转换，实际返回类型是 GlideBitmapDrawableResource
            //此时result的类型是Resource<GlideBitmapDrawable>
            result = bitmapDrawableResourceTranscoder.transcode(bitmapResource);
        } else {
            //如果Resource<Bitmap>为空，那么说明此时加载的是GIF图
            result = gifBitmap.getGifResource();
        }
        // This is unchecked but always safe, anything that extends a Drawable can be safely cast to a Drawable.
        // 翻译：这是未检查的，但始终是安全的，任何扩展Drawable的内容都可以安全地转换为Drawable。
        //完成转换后，返回数据类型Resource<GlideBitmapDrawable>，对象的实际类型是GlideBitmapDrawableResource
        return (Resource<GlideDrawable>) result;
    }

    @Override
    public String getId() {
        return "GifBitmapWrapperDrawableTranscoder.com.bumptech.glide.load.resource.transcode";
    }
}
