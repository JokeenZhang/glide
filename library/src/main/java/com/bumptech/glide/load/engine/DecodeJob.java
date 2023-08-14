package com.bumptech.glide.load.engine;

import android.util.Log;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.Encoder;
import com.bumptech.glide.load.Key;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.engine.cache.DiskCache;
import com.bumptech.glide.load.resource.transcode.ResourceTranscoder;
import com.bumptech.glide.provider.DataLoadProvider;
import com.bumptech.glide.util.LogTime;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * A class responsible for decoding resources either from cached data or from the original source and applying
 * transformations and transcodes.
 *
 * @param <A> The type of the source data the resource can be decoded from.
 * @param <T> The type of resource that will be decoded.
 * @param <Z> The type of resource that will be transcoded from the decoded and transformed resource.
 */
class DecodeJob<A, T, Z> {
    private static final String TAG = "DecodeJob";
    private static final FileOpener DEFAULT_FILE_OPENER = new FileOpener();

    private final EngineKey resultKey;
    private final int width;
    private final int height;
    private final DataFetcher<A> fetcher;
    private final DataLoadProvider<A, T> loadProvider;
    private final Transformation<T> transformation;
    private final ResourceTranscoder<T, Z> transcoder;
    private final DiskCacheProvider diskCacheProvider;
    private final DiskCacheStrategy diskCacheStrategy;
    private final Priority priority;
    private final FileOpener fileOpener;

    private volatile boolean isCancelled;

    public DecodeJob(EngineKey resultKey, int width, int height, DataFetcher<A> fetcher,
            DataLoadProvider<A, T> loadProvider, Transformation<T> transformation, ResourceTranscoder<T, Z> transcoder,
            DiskCacheProvider diskCacheProvider, DiskCacheStrategy diskCacheStrategy, Priority priority) {
        this(resultKey, width, height, fetcher, loadProvider, transformation, transcoder, diskCacheProvider,
                diskCacheStrategy, priority, DEFAULT_FILE_OPENER);
    }

    // Visible for testing.
    DecodeJob(EngineKey resultKey, int width, int height, DataFetcher<A> fetcher,
            DataLoadProvider<A, T> loadProvider, Transformation<T> transformation, ResourceTranscoder<T, Z> transcoder,
            DiskCacheProvider diskCacheProvider, DiskCacheStrategy diskCacheStrategy, Priority priority, FileOpener
            fileOpener) {
        this.resultKey = resultKey;
        this.width = width;
        this.height = height;
        this.fetcher = fetcher;
        this.loadProvider = loadProvider;
        this.transformation = transformation;
        this.transcoder = transcoder;
        this.diskCacheProvider = diskCacheProvider;
        this.diskCacheStrategy = diskCacheStrategy;
        this.priority = priority;
        this.fileOpener = fileOpener;
    }

    /**
     * 获取硬盘缓存中DiskCacheStrategy.RESULT策略下的转换过后的图片
     *
     * Returns a transcoded resource decoded from transformed resource data in the disk cache, or null if no such
     * resource exists.
     *
     * @throws Exception
     */
    public Resource<Z> decodeResultFromCache() throws Exception {
        if (!diskCacheStrategy.cacheResult()) {
            return null;
        }

        long startTime = LogTime.getLogTime();
        Resource<T> transformed = loadFromCache(resultKey);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Decoded transformed from cache", startTime);
        }
        startTime = LogTime.getLogTime();
        Resource<Z> result = transcode(transformed);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transcoded transformed from cache", startTime);
        }
        return result;
    }

    /**
     * 获取硬盘缓存中DiskCacheStrategy.SOURCE策略下的未转换的原图
     *
     * Returns a transformed and transcoded resource decoded from source data in the disk cache, or null if no such
     * resource exists.
     *
     * @throws Exception
     */
    public Resource<Z> decodeSourceFromCache() throws Exception {
        if (!diskCacheStrategy.cacheSource()) {
            return null;
        }

        long startTime = LogTime.getLogTime();
        Resource<T> decoded = loadFromCache(resultKey.getOriginalKey());
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Decoded source from cache", startTime);
        }
        return transformEncodeAndTranscode(decoded);
    }

    /**
     * Returns a transformed and transcoded resource decoded from source data, or null if no source data could be
     * obtained or no resource could be decoded.
     *
     * 返回从源数据解码的转换和转码的资源，如果无法获取源数据或无法解码资源，则返回 null。
     * <p>
     *     Depending on the {@link com.bumptech.glide.load.engine.DiskCacheStrategy} used, source data is either decoded
     *     directly or first written to the disk cache and then decoded from the disk cache.
     *
     *     根据所使用的 DiskCacheStrategy，源数据要幺直接解码，要幺先写入磁盘缓存，然后从磁盘缓存解码。
     * </p>
     *
     * @throws Exception
     */
    public Resource<Z> decodeFromSource() throws Exception {
        //用来解析原图片，decoded是Resource<GifBitmapWrapper>对象
        Resource<T> decoded = decodeSource();
        //对图片进行转换和转码，并写入到硬盘缓存中
        //返回类型是Resource<GlideDrawable>
        return transformEncodeAndTranscode(decoded);
    }

    public void cancel() {
        isCancelled = true;
        fetcher.cancel();
    }

    /**
     * 对图片进行转换和转码，并写入到硬盘缓存中
     * @param decoded 为Resource<GifBitmapWrapper>，T类型为GifBitmapWrapper
     * @return 返回Resource<GlideDrawable>
     */
    private Resource<Z> transformEncodeAndTranscode(Resource<T> decoded) {
        long startTime = LogTime.getLogTime();
        //对图片进行转换
        // 这里返回值类型针对静态图片。在这里完成从Resource<GlideDrawable>到Resource<GlideBitmapDrawable>的转换
        //即transformed是Resource<GlideBitmapDrawable>
        Resource<T> transformed = transform(decoded);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transformed resource from source", startTime);
        }

        //将转换后的图片写入到缓存中
        writeTransformedToCache(transformed);

        startTime = LogTime.getLogTime();
        //transformed是Resource<GlideBitmapDrawable>，经过transcode()处理后返回Resource<GlideDrawable>
        Resource<Z> result = transcode(transformed);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Transcoded transformed from source", startTime);
        }
        return result;
    }

    /**
     * 将转换后的图片写入到硬盘缓存中
     * @param transformed 转换后的图片，非原图
     */
    private void writeTransformedToCache(Resource<T> transformed) {
        if (transformed == null || !diskCacheStrategy.cacheResult()) {
            return;
        }
        long startTime = LogTime.getLogTime();
        SourceWriter<Resource<T>> writer = new SourceWriter<Resource<T>>(loadProvider.getEncoder(), transformed);
        //写入到硬盘缓存，这里用的是resultKey
        diskCacheProvider.getDiskCache().put(resultKey, writer);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Wrote transformed from source to cache", startTime);
        }
    }

    /**
     * 用来解析原图片
     * @return
     * @throws Exception
     */
    private Resource<T> decodeSource() throws Exception {
        Resource<T> decoded = null;
        try {
            long startTime = LogTime.getLogTime();
            //fetcher是在onSizeReady()得到的ImageVideoFetcher对象
            //loadData()返回的是ImageVideoWrapper ，携带InputStream，此时已经通过网络请求拿到图片数据
            final A data = fetcher.loadData(priority);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Fetched data", startTime);
            }
            if (isCancelled) {
                return null;
            }
            //ImageVideoWrapper类型参数
            decoded = decodeFromSourceData(data);
        } finally {
            fetcher.cleanup();
        }
        return decoded;
    }

    /**
     * 这里已经拿到图片，A的类型是{@link com.bumptech.glide.load.model.ImageVideoWrapper}
     * @param data
     * @return
     * @throws IOException
     */
    private Resource<T> decodeFromSourceData(A data) throws IOException {
        final Resource<T> decoded;
        //是否允许缓存原始图片？
        if (diskCacheStrategy.cacheSource()) {
            //允许缓存原始图片，data是ImageVideoWrapper类型
            decoded = cacheAndDecodeSourceData(data);
        } else {
            long startTime = LogTime.getLogTime();
            //解码操作
            //这里loadProvider是FixedLoadProvider，getSourceDecoder()得到的则是一个GifBitmapWrapperResourceDecoder对象
            //最终调用GifBitmapWrapperResourceDecoder.decode(ImageVideoWrapper source, int width, int height):Resource<GifBitmapWrapper>
            //因此decoded在这里的类型是Resource<GifBitmapWrapper>
            decoded = loadProvider.getSourceDecoder().decode(data, width, height);
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                logWithTimeAndKey("Decoded from source", startTime);
            }
        }
        return decoded;
    }

    /**
     * 缓存和转码原始图片
     * @param data
     * @return
     * @throws IOException
     */
    private Resource<T> cacheAndDecodeSourceData(A data) throws IOException {
        long startTime = LogTime.getLogTime();
        SourceWriter<A> writer = new SourceWriter<A>(loadProvider.getSourceEncoder(), data);
        //获取DiskLruCache实例并调用put写入硬盘缓存，注意这里用到的key
        diskCacheProvider.getDiskCache().put(resultKey.getOriginalKey(), writer);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            logWithTimeAndKey("Wrote source to cache", startTime);
        }

        startTime = LogTime.getLogTime();
        Resource<T> result = loadFromCache(resultKey.getOriginalKey());
        if (Log.isLoggable(TAG, Log.VERBOSE) && result != null) {
            logWithTimeAndKey("Decoded source from cache", startTime);
        }
        return result;
    }

    private Resource<T> loadFromCache(Key key) throws IOException {
        File cacheFile = diskCacheProvider.getDiskCache().get(key);
        if (cacheFile == null) {
            return null;
        }

        Resource<T> result = null;
        try {
            result = loadProvider.getCacheDecoder().decode(cacheFile, width, height);
        } finally {
            if (result == null) {
                diskCacheProvider.getDiskCache().delete(key);
            }
        }
        return result;
    }

    /**
     * 转换图片
     * @param decoded
     * @return
     */
    private Resource<T> transform(Resource<T> decoded) {
        if (decoded == null) {
            return null;
        }

        Resource<T> transformed = transformation.transform(decoded, width, height);
        if (!decoded.equals(transformed)) {
            decoded.recycle();
        }
        return transformed;
    }

    private Resource<Z> transcode(Resource<T> transformed) {
        //transformed是Resource<GlideBitmapDrawable>
        if (transformed == null) {
            return null;
        }
        //transcoder实际上是 GifBitmapWrapperDrawableTranscoder 对象
        //方法返回Resource<GlideDrawable>类型，实际是 GlideBitmapDrawableResource 对象
        return transcoder.transcode(transformed);
    }

    private void logWithTimeAndKey(String message, long startTime) {
        Log.v(TAG, message + " in " + LogTime.getElapsedMillis(startTime) + ", key: " + resultKey);
    }

    class SourceWriter<DataType> implements DiskCache.Writer {

        private final Encoder<DataType> encoder;
        private final DataType data;

        public SourceWriter(Encoder<DataType> encoder, DataType data) {
            this.encoder = encoder;
            this.data = data;
        }

        @Override
        public boolean write(File file) {
            boolean success = false;
            OutputStream os = null;
            try {
                os = fileOpener.open(file);
                success = encoder.encode(data, os);
            } catch (FileNotFoundException e) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Failed to find file to write to disk cache", e);
                }
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        // Do nothing.
                    }
                }
            }
            return success;
        }
    }

    interface DiskCacheProvider {
        DiskCache getDiskCache();
    }

    static class FileOpener {
        public OutputStream open(File file) throws FileNotFoundException {
            return new BufferedOutputStream(new FileOutputStream(file));
        }
    }
}
