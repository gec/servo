/*
 * #%L
 * servo
 * %%
 * Copyright (C) 2011 - 2012 Netflix
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.netflix.servo.publish;

import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.netflix.servo.Metric;
import com.netflix.servo.monitor.Monitor;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.tag.Tag;
import com.netflix.servo.tag.TagList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Converts counter metrics into a rate per second. The rate is calculated by
 * comparing two samples of given metric and looking at the delta. Since two
 * samples are needed to calculate the rate, no value will be sent to the
 * wrapped observer until a second sample arrives. If a given metric is not
 * updated within a given heartbeat interval, the previous cached value for the
 * counter will be dropped such that if a new sample comes in it will be
 * treated as the first sample for that metric.
 *
 * <p>Counters should be monotonically increasing values. If a counter value
 * decreases from one sample to the next, then we will assume the counter value
 * was reset and send a rate of 0. This is similar to the RRD concept of
 * type DERIVE with a min of 0.
 */
public final class CounterToRateMetricTransform implements MetricObserver {

    private static final Logger LOGGER =
        LoggerFactory.getLogger(CounterToRateMetricTransform.class);

    private final MetricObserver observer;
    private final Cache<MonitorConfig, CounterValue> cache;

    private final Monitor<?> cacheMonitor;

    /**
     * Creates a new instance with the specified heartbeat interval. The
     * heartbeat should be some multiple of the sampling interval used when
     * collecting the metrics.
     */
    public CounterToRateMetricTransform(MetricObserver observer, long heartbeat, TimeUnit unit) {
        this.observer = observer;
        this.cache = CacheBuilder.newBuilder()
            .expireAfterWrite(heartbeat, unit)
            .build();
        cacheMonitor = Monitors.newCacheMonitor(observer.getName(), cache);
    }

    /** {@inheritDoc} */
    public String getName() {
        return observer.getName();
    }

    /** {@inheritDoc} */
    public void update(List<Metric> metrics) {
        Preconditions.checkNotNull(metrics);
        List<Metric> newMetrics = Lists.newArrayList();
        for (Metric m : metrics) {
            if (isCounter(m)) {
                CounterValue current = new CounterValue(m);
                CounterValue prev = cache.getIfPresent(m.getConfig());
                if (prev != null) {
                    Metric rate = new Metric(
                        m.getConfig(),
                        m.getTimestamp(),
                        current.computeRate(prev));
                    newMetrics.add(rate);
                }
                cache.put(m.getConfig(), current);
            } else {
                newMetrics.add(m);
            }
        }
        observer.update(newMetrics);
    }

    /**
     * Clear all cached state of previous counter values.
     */
    public void reset() {
        cache.invalidateAll();
    }

    private boolean isCounter(Metric m) {
        TagList tags = m.getConfig().getTags();
        Tag type = tags.getTag(DataSourceType.KEY);
        String counter = DataSourceType.COUNTER.name();
        return (type != null && counter.equals(type.getValue()));
    }

    private static class CounterValue {
        private final long timestamp;
        private final double value;

        public CounterValue(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }

        public CounterValue(Metric m) {
            this(m.getTimestamp(), m.getNumberValue().doubleValue());
        }

        public double computeRate(CounterValue prev) {
            final double millisPerSecond = 1000.0;
            double duration = (timestamp - prev.timestamp) / millisPerSecond;
            double delta = value - prev.value;
            return (duration <= 0.0 || delta <= 0.0) ? 0.0 : delta / duration;
        }
    }
}
