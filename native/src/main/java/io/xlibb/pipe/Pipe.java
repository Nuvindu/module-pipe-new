// Copyright (c) 2022, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package io.xlibb.pipe;

import io.ballerina.runtime.api.Environment;
import io.ballerina.runtime.api.Future;
import io.ballerina.runtime.api.PredefinedTypes;
import io.ballerina.runtime.api.creators.TypeCreator;
import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.types.Type;
import io.ballerina.runtime.api.types.UnionType;
import io.ballerina.runtime.api.utils.ValueUtils;
import io.ballerina.runtime.api.values.BDecimal;
import io.ballerina.runtime.api.values.BError;
import io.ballerina.runtime.api.values.BHandle;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BTypedesc;
import io.xlibb.pipe.observer.Callback;
import io.xlibb.pipe.observer.Notifier;
import io.xlibb.pipe.observer.Observable;

import java.math.BigDecimal;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.xlibb.pipe.utils.ModuleUtils.getModule;
import static io.xlibb.pipe.utils.Utils.NATIVE_PIPE;
import static io.xlibb.pipe.utils.Utils.NATIVE_PIPE_OBJECT;
import static io.xlibb.pipe.utils.Utils.NATIVE_TIMER_OBJECT;
import static io.xlibb.pipe.utils.Utils.RESULT_ITERATOR;
import static io.xlibb.pipe.utils.Utils.STREAM_GENERATOR;
import static io.xlibb.pipe.utils.Utils.TIMER;
import static io.xlibb.pipe.utils.Utils.TIME_OUT;
import static io.xlibb.pipe.utils.Utils.createError;

/**
 * Provide APIs to exchange events concurrently.
 */
public class Pipe implements IPipe {

    private static final long INFINITE_WAIT = -1;
    private static final BDecimal MILLISECONDS_FACTOR = ValueCreator.createDecimalValue(new BigDecimal(1000));
    private ConcurrentLinkedQueue<Object> queue;
    private final AtomicInteger queueSize;
    private final Long limit;
    private final Timer timer;
    private final AtomicBoolean isClosed;
    private final Observable producer;
    private final Observable consumer;
    private final Observable emptyQueue;
    private final Observable timeKeeper;
    private final Object consumeLock = new Object();
    private final Object produceLock = new Object();

    public Pipe(Long limit) {
        this(limit, ValueCreator.createObjectValue(getModule(), TIMER));
    }

    public Pipe(Long limit, BObject timer) {
        this.limit = limit;
        this.timer = (Timer) ((BHandle) timer.get(NATIVE_TIMER_OBJECT)).getValue();
        this.queue = new ConcurrentLinkedQueue<>();
        this.queueSize = new AtomicInteger(0);
        this.isClosed = new AtomicBoolean(false);
        this.consumer = new Observable(this.queue, this.queueSize);
        this.producer = new Observable(this.queue, this.queueSize);
        this.emptyQueue = new Observable(null, null);
        this.timeKeeper = new Observable(null, null);
    }

    protected void asyncProduce(Callback callback, Object event, long timeout) {
        if (event == null) {
            callback.onError(createError("Nil values cannot be produced to a pipe"));
            return;
        }
        if (this.isClosed.get()) {
            callback.onError(createError("Events cannot be produced to a closed pipe"));
            return;
        }
        synchronized (this.produceLock) {
            if (this.queueSize.get() == this.limit || queue.size() == this.limit) {
                callback.setEvent(event);
                this.consumer.registerObserver(callback);
                this.scheduleAction(callback, timeout);
            } else {
                queue.add(event);
                queueSize.incrementAndGet();
                this.producer.notifyObservers(event);
                callback.onSuccess(null);
            }
        }
    }

    protected void asyncConsume(Callback callback, long timeout, Type type) {
        if (this.queue == null) {
            callback.onSuccess(null);
            return;
        }
        synchronized (this.consumeLock) {
            if (this.queueSize.get() == 0 || queue.isEmpty()) {
                this.emptyQueue.notifyObservers(true);
                this.producer.registerObserver(callback);
                this.scheduleAction(callback, timeout);
            } else {
                try {
                    queueSize.decrementAndGet();
                    this.consumer.notifyObservers();
                    Object value = ValueUtils.convert(queue.remove(), type);
                    callback.onSuccess(value);
                } catch (Exception e) {
                    callback.onError(createError(e.getMessage()));
                }
            }
        }
    }

    @Override
    public boolean isClosed() {
        return this.isClosed.get();
    }

    @Override
    public BError immediateClose() {
        if (this.isClosed.get()) {
            return createError("Closing of a closed pipe is not allowed");
        }
        this.isClosed.compareAndSet(false, true);
        this.queue = null;
        return null;
    }

    protected void asyncClose(Callback callback, long timeout) {
        if (this.isClosed.get()) {
            callback.onError(createError("Closing of a closed pipe is not allowed"));
        } else if (timeout == -1) {
            callback.onError(createError("Graceful close must provide 0 or greater timeout"));
        } else {
            this.isClosed.compareAndSet(false, true);
            if (this.queueSize.get() != 0) {
                emptyQueue.registerObserver(callback);
                this.timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        queue = null;
                        emptyQueue.unregisterObserver(callback);
                        callback.onSuccess(null);
                    }
                }, timeout);
            } else {
                this.queue = null;
                callback.onSuccess(null);
            }
        }
    }

    public static Object consumeStream(BObject pipe, BDecimal timeout, BTypedesc typeParam) {
        long timeoutInMillis;
        try {
            timeoutInMillis = getTimeoutInMillis(timeout);
        } catch (BError bError) {
            return createError("Invalid consume timeout value provided", bError);
        }
        UnionType typeUnion = TypeCreator.createUnionType(PredefinedTypes.TYPE_NULL, PredefinedTypes.TYPE_ERROR);
        BObject resultIterator = ValueCreator.createObjectValue(getModule(), RESULT_ITERATOR, typeParam);
        BObject streamGenerator = ValueCreator.createObjectValue(getModule(), STREAM_GENERATOR, resultIterator);
        BHandle handle = (BHandle) pipe.get(NATIVE_PIPE_OBJECT);
        streamGenerator.addNativeData(NATIVE_PIPE, handle.getValue());
        streamGenerator.addNativeData(TIME_OUT, timeoutInMillis);
        return ValueCreator.createStreamValue(TypeCreator.createStreamType(typeParam.getDescribingType(), typeUnion),
                                              streamGenerator);
    }

    public static BError produce(Environment env, BObject pipe, Object event, BDecimal timeout) {
        try {
            long timeoutInMillis;
            try {
                timeoutInMillis = getTimeoutInMillis(timeout);
            } catch (BError bError) {
                return createError("Invalid produce timeout value provided", bError);
            }
            BHandle handle = (BHandle) pipe.get(NATIVE_PIPE_OBJECT);
            Pipe nativePipe = (Pipe) handle.getValue();
            Future future = env.markAsync();
            Callback observer = new Callback(future, nativePipe.getConsumer(), nativePipe.getTimeKeeper(),
                    nativePipe.getProducer());
            nativePipe.asyncProduce(observer, event, timeoutInMillis);
            return null;
        } catch (Exception e) {
            return createError(e.getMessage());
        }
    }

    public static Object consume(Environment env, BObject pipe, BDecimal timeout, BTypedesc typeParam) {
        try {
            long timeoutInMillis;
            try {
                timeoutInMillis = getTimeoutInMillis(timeout);
            } catch (BError bError) {
                return createError("Invalid consume timeout value provided", bError);
            }
            BHandle handle = (BHandle) pipe.get(NATIVE_PIPE_OBJECT);
            Pipe nativePipe = (Pipe) handle.getValue();
            Future future = env.markAsync();
            Callback observer = new Callback(future, nativePipe.getProducer(), nativePipe.getTimeKeeper(),
                    nativePipe.getConsumer());
            nativePipe.asyncConsume(observer, timeoutInMillis, typeParam.getDescribingType());
            return null;
        } catch (Exception e) {
            return createError(e.getMessage());
        }
    }

    public static BError gracefulClose(Environment env, BObject pipe, BDecimal timeout) {
        long timeoutInMillis;
        try {
            timeoutInMillis = getTimeoutInMillis(timeout);
        } catch (BError bError) {
            return createError("Invalid graceful timeout value provided", bError);
        }
        BHandle handle = (BHandle) pipe.get(NATIVE_PIPE_OBJECT);
        Pipe nativePipe = (Pipe) handle.getValue();
        Future future = env.markAsync();
        Callback observer = new Callback(future, null, null, null);
        nativePipe.asyncClose(observer, timeoutInMillis);
        return null;
    }

    private static long getTimeoutInMillis(BDecimal timeout) {
        if (timeout.floatValue() == -1) {
            return INFINITE_WAIT;
        } else if (timeout.floatValue() < 0) {
            throw createError("Timeout cannot be less than -1. Provided: " + timeout);
        }
        BDecimal timeoutInMillis = timeout.multiply(MILLISECONDS_FACTOR);
        return Double.valueOf(timeoutInMillis.floatValue()).longValue();
    }

    private void scheduleAction(Callback callback, long timeout) {
        if (timeout == INFINITE_WAIT) {
            return;
        }
        this.timeKeeper.registerObserver(callback);
        Notifier notifier = new Notifier(this.timeKeeper, callback);
        this.timer.schedule(notifier, timeout);
    }

    protected Observable getProducer() {
        return producer;
    }

    protected Observable getConsumer() {
        return consumer;
    }

    protected Observable getTimeKeeper() {
        return timeKeeper;
    }
}
