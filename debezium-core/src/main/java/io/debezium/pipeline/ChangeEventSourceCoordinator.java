/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.pipeline;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.annotation.ThreadSafe;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.pipeline.metrics.SnapshotChangeEventSourceMetrics;
import io.debezium.pipeline.metrics.StreamingChangeEventSourceMetrics;
import io.debezium.pipeline.source.spi.ChangeEventSource;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.pipeline.source.spi.ChangeEventSourceFactory;
import io.debezium.pipeline.source.spi.SnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.pipeline.spi.SnapshotResult.SnapshotResultStatus;
import io.debezium.util.Threads;

/**
 * Coordinates one or more {@link ChangeEventSource}s and executes them in order.
 *
 * @author Gunnar Morling
 */
@ThreadSafe
public class ChangeEventSourceCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeEventSourceCoordinator.class);

    private static final Duration SHUTDOWN_WAIT_TIMEOUT = Duration.ofSeconds(90);

    private final OffsetContext previousOffset;
    private final ErrorHandler errorHandler;
    private final ChangeEventSourceFactory changeEventSourceFactory;
    private final ExecutorService executor;
    private final EventDispatcher<?> eventDispatcher;

    private volatile boolean running;
    private volatile StreamingChangeEventSource streamingSource;

    private SnapshotChangeEventSourceMetrics snapshotMetrics;
    private StreamingChangeEventSourceMetrics streamingMetrics;

    public ChangeEventSourceCoordinator(OffsetContext previousOffset, ErrorHandler errorHandler, Class<? extends SourceConnector> connectorType, String logicalName, ChangeEventSourceFactory changeEventSourceFactory, EventDispatcher<?> eventDispatcher) {
        this.previousOffset = previousOffset;
        this.errorHandler = errorHandler;
        this.changeEventSourceFactory = changeEventSourceFactory;
        this.executor = Threads.newSingleThreadExecutor(connectorType, logicalName, "change-event-source-coordinator");
        this.eventDispatcher = eventDispatcher;
    }

    public synchronized <T extends CdcSourceTaskContext> void start(T taskContext) {
        this.snapshotMetrics = new SnapshotChangeEventSourceMetrics(taskContext);
        this.streamingMetrics = new StreamingChangeEventSourceMetrics(taskContext);
        running = true;

        // run the snapshot source on a separate thread so start() won't block
        executor.submit(() -> {
            try {
                snapshotMetrics.register(LOGGER);
                streamingMetrics.register(LOGGER);

                ChangeEventSourceContext context = new ChangeEventSourceContextImpl();

                SnapshotChangeEventSource snapshotSource = changeEventSourceFactory.getSnapshotChangeEventSource(previousOffset, snapshotMetrics);
                eventDispatcher.setEventListener(snapshotMetrics);
                SnapshotResult snapshotResult = snapshotSource.execute(context);

                if (running && snapshotResult.getStatus() == SnapshotResultStatus.COMPLETED) {
                    streamingSource = changeEventSourceFactory.getStreamingChangeEventSource(snapshotResult.getOffset());
                    eventDispatcher.setEventListener(streamingMetrics);
                    streamingSource.execute(context);
                }
            }
            catch (InterruptedException e) {
                Thread.interrupted();
                LOGGER.warn("Change event source executor was interrupted", e);
            }
            catch (Exception e) {
                errorHandler.setProducerThrowable(e);
            }
        });
    }

    public void commitOffset(Map<String, ?> offset) {
        if (streamingSource != null) {
            streamingSource.commitOffset(offset);
        }
    }

    /**
     * Stops this coordinator.
     */
    public synchronized void stop() throws InterruptedException {
        running = false;

        executor.shutdown();
        Thread.interrupted();
        boolean isShutdown = executor.awaitTermination(SHUTDOWN_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        if (!isShutdown) {
            LOGGER.warn("Coordinator didn't stop in the expected time, shutting down executor now");

            executor.shutdownNow();
            executor.awaitTermination(SHUTDOWN_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        snapshotMetrics.unregister(LOGGER);
        streamingMetrics.unregister(LOGGER);
    }

    private class ChangeEventSourceContextImpl implements ChangeEventSourceContext {

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
