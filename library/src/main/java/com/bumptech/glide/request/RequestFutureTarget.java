package com.bumptech.glide.request;

import android.graphics.drawable.Drawable;
import android.os.Handler;

import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.util.Util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link java.util.concurrent.Future} implementation for Glide that can be used to load resources in a blocking
 * manner on background threads.
 *
 * <p>
 *     Note - Unlike most targets, RequestFutureTargets can be used once and only once. Attempting to reuse a
 *     RequestFutureTarget will probably result in undesirable behavior or exceptions. Instead of reusing
 *     objects of this class, the pattern should be:
 *
 *     <pre>
 *     {@code
 *      RequestFutureTarget target = Glide.load("")...
 *     Object resource = target.get();
 *     // Do something with resource, and when finished:
 *     Glide.clear(target);
 *     }
 *     </pre>
 *     The {@link com.bumptech.glide.Glide#clear(FutureTarget)} call will make sure any resources used are recycled.
 * </p>
 *
 * @param <T> The type of the data to load.
 * @param <R> The type of the resource that will be loaded.
 */
public class RequestFutureTarget<T, R> implements FutureTarget<R>, Runnable {
    private static final Waiter DEFAULT_WAITER = new Waiter();

    private final Handler mainHandler;
    private final int width;
    private final int height;
    // Exists for testing only.
    private final boolean assertBackgroundThread;
    private final Waiter waiter;

    private R resource;
    private Request request;
    private boolean isCancelled;
    private Exception exception;
    private boolean resultReceived;
    private boolean exceptionReceived;

    /**
     * Constructor for a RequestFutureTarget. Should not be used directly.
     */
    public RequestFutureTarget(Handler mainHandler, int width, int height) {
        this(mainHandler, width, height, true, DEFAULT_WAITER);
    }

    RequestFutureTarget(Handler mainHandler, int width, int height, boolean assertBackgroundThread, Waiter waiter) {
        this.mainHandler = mainHandler;
        this.width = width;
        this.height = height;
        this.assertBackgroundThread = assertBackgroundThread;
        this.waiter = waiter;
    }

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
        if (isCancelled) {
            return true;
        }

        final boolean result = !isDone();
        if (result) {
            isCancelled = true;
            if (mayInterruptIfRunning) {
                clear();
            }
            waiter.notifyAll(this);
        }
        return result;
    }

    @Override
    public synchronized boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public synchronized boolean isDone() {
        return isCancelled || resultReceived;
    }

    /**
     * 交给外部手动调用获取结果
     *
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Override
    public R get() throws InterruptedException, ExecutionException {
        try {
            //实际上还是通过doGet()方法来完成后续逻辑
            return doGet(null);
        } catch (TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public R get(long time, TimeUnit timeUnit) throws InterruptedException, ExecutionException, TimeoutException {
        return doGet(timeUnit.toMillis(time));
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public void getSize(SizeReadyCallback cb) {
        cb.onSizeReady(width, height);
    }

    @Override
    public void setRequest(Request request) {
        this.request = request;
    }

    @Override
    public Request getRequest() {
        return request;
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public void onLoadCleared(Drawable placeholder) {
        // Do nothing.
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public void onLoadStarted(Drawable placeholder) {
        // Do nothing.
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public synchronized void onLoadFailed(Exception e, Drawable errorDrawable) {
         // We might get a null exception.
        exceptionReceived = true;
        this.exception = e;
        waiter.notifyAll(this);
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public synchronized void onResourceReady(R resource, GlideAnimation<? super R> glideAnimation) {
        // We might get a null result.
        resultReceived = true;
        this.resource = resource;
        waiter.notifyAll(this);
    }

    private synchronized R doGet(Long timeoutMillis) throws ExecutionException, InterruptedException, TimeoutException {
        //判断是否在子线程，如果不是抛出异常
        if (assertBackgroundThread) {
            Util.assertBackgroundThread();
        }

        //判断下载是否已取消、或者已失败，如果是已取消或者已失败的话都会直接抛出一个异常

        if (isCancelled) {
            throw new CancellationException();
        } else if (exceptionReceived) {
            throw new ExecutionException(exception);
        } else if (resultReceived) {
            //根据resultReceived这个变量来判断下载是否完成，如果为true就直接返回结果
            return resource;
        }

        //这里是下载未完成的情况

        if (timeoutMillis == null) {
            //waitForTimeout()内会调用wait()阻塞线程，阻止代码继续执行。接下来等待onResourceReady()被回调，下载完成，然后notify恢复执行
            waiter.waitForTimeout(this, 0);
        } else if (timeoutMillis > 0) {
            waiter.waitForTimeout(this, timeoutMillis);
        }

        if (Thread.interrupted()) {
            throw new InterruptedException();
        } else if (exceptionReceived) {
            throw new ExecutionException(exception);
        } else if (isCancelled) {
            throw new CancellationException();
        } else if (!resultReceived) {
            throw new TimeoutException();
        }

        return resource;
    }

    /**
     * A callback that should never be invoked directly.
     */
    @Override
    public void run() {
        if (request != null) {
            request.clear();
            cancel(false /*mayInterruptIfRunning*/);
        }
    }

    /**
     * Can be safely called from either the main thread or a background thread to cleanup the resources used by this
     * target.
     */
    @Override
    public void clear() {
        mainHandler.post(this);
    }

    @Override
    public void onStart() {
        // Do nothing.
    }

    @Override
    public void onStop() {
        // Do nothing.
    }

    @Override
    public void onDestroy() {
        // Do nothing.
    }

    // Visible for testing.
    static class Waiter {

        public void waitForTimeout(Object toWaitOn, long timeoutMillis) throws InterruptedException {
            toWaitOn.wait(timeoutMillis);
        }

        public void notifyAll(Object toNotify) {
            toNotify.notifyAll();
        }
    }
}
