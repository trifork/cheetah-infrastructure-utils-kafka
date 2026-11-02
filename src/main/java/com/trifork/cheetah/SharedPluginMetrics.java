package com.trifork.cheetah;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.MetricValueProvider;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.PluginMetrics;
import org.apache.kafka.common.metrics.Sensor;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lets the delegate and the private authorizers report into the same authorizer metrics.
 *
 * <p>Kafka's {@link PluginMetrics} rejects a second sensor or metric with the same name, but every
 * {@code StandardAuthorizer} registers the same ones. Sensors are therefore shared, and repeated metric
 * registrations are ignored: the first one wins, which is the delegate's (including its ACL count gauge).
 */
final class SharedPluginMetrics implements PluginMetrics {
    private final PluginMetrics target;
    private final ConcurrentMap<String, Sensor> sensors = new ConcurrentHashMap<>();
    private final Set<MetricName> metricNames = ConcurrentHashMap.newKeySet();

    SharedPluginMetrics(PluginMetrics target) {
        this.target = target;
    }

    /**
     * For when Kafka does not inject metrics, for example in tests. Without any metrics object,
     * {@code StandardAuthorizer.authorize} throws {@code NullPointerException}.
     */
    static SharedPluginMetrics standalone() {
        return new SharedPluginMetrics(new LocalPluginMetrics(new Metrics()));
    }

    @Override
    public MetricName metricName(String name, String description, LinkedHashMap<String, String> tags) {
        return target.metricName(name, description, tags);
    }

    @Override
    public void addMetric(MetricName metricName, MetricValueProvider<?> metricValueProvider) {
        if (metricNames.add(metricName)) {
            target.addMetric(metricName, metricValueProvider);
        }
    }

    @Override
    public void removeMetric(MetricName metricName) {
        if (metricNames.remove(metricName)) {
            target.removeMetric(metricName);
        }
    }

    @Override
    public Sensor addSensor(String name) {
        return sensors.computeIfAbsent(name, target::addSensor);
    }

    @Override
    public void removeSensor(String name) {
        if (sensors.remove(name) != null) {
            target.removeSensor(name);
        }
    }

    private static final class LocalPluginMetrics implements PluginMetrics {
        private final Metrics metrics;

        private LocalPluginMetrics(Metrics metrics) {
            this.metrics = metrics;
        }

        @Override
        public MetricName metricName(String name, String description, LinkedHashMap<String, String> tags) {
            return metrics.metricName(name, "cheetah-authorizer", description, tags);
        }

        @Override
        public void addMetric(MetricName metricName, MetricValueProvider<?> metricValueProvider) {
            metrics.addMetric(metricName, metricValueProvider);
        }

        @Override
        public void removeMetric(MetricName metricName) {
            metrics.removeMetric(metricName);
        }

        @Override
        public Sensor addSensor(String name) {
            return metrics.sensor(name);
        }

        @Override
        public void removeSensor(String name) {
            metrics.removeSensor(name);
        }
    }
}
