/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.utils.queue;

import edu.umd.cs.findbugs.annotations.*;
import org.jitsi.utils.logging.*;
import org.json.simple.*;
import org.jetbrains.annotations.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.util.concurrent.*;

/**
 * An abstract queue of packets.
 *
 * @author Boris Grozev
 * @author Yura Yaroshevich
 */
public class PacketQueue<T>
{
    /**
     * The {@link Logger} used by the {@link PacketQueue} class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(PacketQueue.class.getName());

    /**
     * The default value for the {@code enableStatistics} constructor argument.
     */
    private static boolean enableStatisticsDefault = false;

    /**
     * Sets the default value for the {@code enableStatistics} constructor
     * parameter.
     *
     * @param enable the value to set.
     */
    public static void setEnableStatisticsDefault(boolean enable)
    {
        enableStatisticsDefault = enable;
    }

    public static boolean getEnableStatisticsDefault()
    {
        return enableStatisticsDefault;
    }

    /**
     * The underlying {@link BlockingQueue} which holds packets.
     * Used as synchronization object between {@link #close()}
     * and {@link #add(Object)}.
     */
    @SuppressFBWarnings(
        value = "JLM_JSR166_UTILCONCURRENT_MONITORENTER",
        justification = "We synchronize on the queue intentionally.")
    @NotNull private final BlockingQueue<T> queue;

    /**
     * The {@link QueueStatistics} instance optionally used to collect and print
     * detailed statistics about this queue.
     */
    private final QueueStatistics queueStatistics;

    /**
     * The {@link AsyncQueueHandler} to perpetually read packets
     * from {@link #queue} on separate thread and handle them with provided
     * packet handler.
     */
    @NotNull private final AsyncQueueHandler<T> asyncQueueHandler;

    /**
     * A string used to identify this {@link PacketQueue} for logging purposes.
     */
    @NotNull private final String id;

    /**
     * Whether this queue has been closed. Field is denoted as volatile,
     * because it is set in one thread and could be read in while loop in other.
     */
    private volatile boolean closed = false;

    /**
     * The maximum number of items the queue can contain before it starts
     * dropping items.
     */
    private final int capacity;

    /**
     * Handles dropped packets and exceptions thrown while processing.
     */
    @NotNull
    private ErrorHandler errorHandler = new ErrorHandler(){};

    /**
     * Initializes a new {@link PacketQueue} instance.
     * @param capacity the capacity of the queue.
     * @param enableStatistics whether detailed statistics should be gathered.
     * This might affect performance. A value of {@code null} indicates that
     * the default {@link #enableStatisticsDefault} value will be used.
     * @param id the ID of the packet queue, to be used for logging.
     * @param packetHandler An handler to be used by the queue for
     * packets read from it.  The queue will start its own tasks on
     * {@param executor}, which will read packets from the queue and execute
     * {@code handler.handlePacket} on them.
     * @param executor An executor service to use to execute
     * packetHandler for items added to queue.
     */
    public PacketQueue(
        int capacity,
        Boolean enableStatistics,
        @NotNull String id,
        @NotNull PacketHandler<T> packetHandler,
        ExecutorService executor)
    {
        this.id = id;
        this.capacity = capacity;
        queue = new ArrayBlockingQueue<>(capacity);

        if (enableStatistics == null)
        {
            enableStatistics = enableStatisticsDefault;
        }
        queueStatistics
            = enableStatistics ? new QueueStatistics() : null;

        asyncQueueHandler = new AsyncQueueHandler<>(
            queue,
            new HandlerAdapter(packetHandler),
            id,
            executor,
            packetHandler.maxSequentiallyProcessedPackets());

        logger.debug("Initialized a PacketQueue instance with ID " + id);
    }

    /**
     * Adds a specific packet ({@code T}) instance to the queue.
     * @param pkt the packet to add.
     */
    public void add(T pkt)
    {
        if (closed)
            return;

        while (!queue.offer(pkt))
        {
            // Drop from the head of the queue.
            T p = queue.poll();
            if (p != null)
            {
                if (queueStatistics != null)
                {
                    queueStatistics.drop(System.currentTimeMillis());
                }
                errorHandler.packetDropped();

                // Call release on dropped packet to allow proper implementation
                // of object pooling by PacketQueue users
                releasePacket(p);
            }
        }

        if (queueStatistics != null)
        {
            queueStatistics.add(System.currentTimeMillis());
        }

        synchronized (queue)
        {
            // notify single thread because only 1 item was added into queue
            queue.notify();
        }

        asyncQueueHandler.handleQueueItemsUntilEmpty();
    }




    /**
     * Closes current <tt>PacketQueue</tt> instance. No items will be added
     * to queue when it's closed.  Asynchronous queue processing by
     * {@link #asyncQueueHandler} is stopped.
     */
    public void close()
    {
        if (!closed)
        {
            closed = true;

            asyncQueueHandler.cancel();

            synchronized (queue)
            {
                // notify all threads because PacketQueue is closed and all
                // threads waiting on queue must stop reading it.
                queue.notifyAll();
            }
            T item;
            while ((item = queue.poll()) != null) {
                releasePacket(item);
            }
        }
    }

    /**
     * Releases packet when it is handled by provided packet handler.
     * This method is not called when <tt>PacketQueue</tt> was created without
     * handler and hence no automatic queue processing is done.
     * Default implementation is empty, but it might be used to implement
     * packet pooling to re-use them.
     * @param pkt packet to release
     */
    protected void releasePacket(T pkt)
    {
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("id", id);
        debugState.put("capacity", capacity);
        debugState.put("closed", closed);
        debugState.put(
                "statistics",
                queueStatistics == null
                        ? null : queueStatistics.getStats());

        return debugState;
    }

    /**
     * Sets the handler of errors (packets dropped or exceptions caught while
     * processing).
     * @param errorHandler the handler to set.
     */
    public void setErrorHandler(@NotNull ErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
    }

    /**
     * A simple interface to handle packets.
     * @param <T> the type of the packets.
     */
    public interface PacketHandler<T>
    {
        /**
         * Does something with a packet.
         * @param pkt the packet to do something with.
         * @return {@code true} if the operation was successful, and
         * {@code false} otherwise.
         */
        boolean handlePacket(T pkt);

        /**
         * Specifies the number of packets allowed to be processed sequentially
         * without yielding control to executor's thread. Specifying positive
         * number will allow other possible queues sharing same
         * {@link ExecutorService} to process their packets.
         * @return positive value to specify max number of packets which allows
         * implementation of cooperative multi-tasking between different
         * {@link PacketQueue} sharing same {@link ExecutorService}.
         */
        default long maxSequentiallyProcessedPackets()
        {
            return -1;
        }
    }

    /**
     * An adapter class implementing {@link AsyncQueueHandler.Handler}
     * to wrap {@link PacketHandler}.
     */
    private final class HandlerAdapter implements AsyncQueueHandler.Handler<T>
    {
        /**
         * An actual handler of packets.
         */
        private final PacketHandler<T> handler;

        /**
         * Constructs adapter of {@link PacketHandler} to
         * {@link AsyncQueueHandler.Handler} interface.
         * @param handler an handler instance to adapt
         */
        HandlerAdapter(PacketHandler<T> handler)
        {
            this.handler = handler;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void handleItem(T item)
        {
            if (queueStatistics != null)
            {
                queueStatistics.remove(System.currentTimeMillis());
            }

            try
            {
                handler.handlePacket(item);
            }
            catch (Throwable t)
            {
                errorHandler.packetHandlingFailed(t);
            }
        }
    }
}
