/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;

abstract class AbstractStreamMessageAndWriter<T> extends AbstractStreamMessage<T>
        implements StreamMessageAndWriter<T> {

    static final CloseEvent SUCCESSFUL_CLOSE = new CloseEvent(null);

    enum State {
        /**
         * The initial state. Will enter {@link #CLOSED} or {@link #CLEANUP}.
         */
        OPEN,
        /**
         * {@link #close()} or {@link #close(Throwable)} has been called. Will enter {@link #CLEANUP} after
         * {@link org.reactivestreams.Subscriber#onComplete()} or
         * {@link org.reactivestreams.Subscriber#onError(Throwable)} is invoked.
         */
        CLOSED,
        /**
         * Anything in the queue must be cleaned up.
         * Enters this state when there's no chance of consumption by subscriber.
         * i.e. when any of the following methods are invoked:
         * <ul>
         *   <li>{@link org.reactivestreams.Subscription#cancel()}</li>
         *   <li>{@link #abort()} (via {@link AbortingSubscriber})</li>
         *   <li>{@link org.reactivestreams.Subscriber#onComplete()}</li>
         *   <li>{@link org.reactivestreams.Subscriber#onError(Throwable)}</li>
         * </ul>
         */
        CLEANUP
    }

    @Override
    public boolean write(T obj) {
        requireNonNull(obj, "obj");
        if (obj instanceof ReferenceCounted) {
            ((ReferenceCounted) obj).touch();
            if (!(obj instanceof ByteBufHolder) && !(obj instanceof ByteBuf)) {
                throw new IllegalArgumentException(
                        "can't publish a ReferenceCounted that's not a ByteBuf or a ByteBufHolder: " + obj);
            }
        }

        if (!isOpen()) {
            ReferenceCountUtil.safeRelease(obj);
            return false;
        }

        addObject(obj);
        return true;
    }

    @Override
    public CompletableFuture<Void> onDemand(Runnable task) {
        requireNonNull(task, "task");

        final AwaitDemandFuture f = new AwaitDemandFuture();
        if (!isOpen()) {
            f.completeExceptionally(ClosedPublisherException.get());
            return f;
        }

        addObjectOrEvent(f);
        return f.thenRun(task);
    }

    /**
     * Adds an object to publish to the stream.
     */
    abstract void addObject(T obj);

    /**
     * Adds an object to publish (of type {@code T} or an event (e.g., {@link CloseEvent},
     * {@link AwaitDemandFuture}) to the stream.
     */
    abstract void addObjectOrEvent(Object obj);

    static final class AwaitDemandFuture extends CompletableFuture<Void> {}
}
