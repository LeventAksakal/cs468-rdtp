package model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;

public class NetworkMetrics {
    private final Deque<Long> rttSamples;
    private final Deque<Long> jitterSamples;
    private final Deque<Boolean> packetLossSamples;
    private final Deque<Pair<Long, Long>> throughputSamples;
    private final long timeWindow;
    private Long lastRtt;

    // Custom Pair implementation
    public static class Pair<K, V> {
        private final K key;
        private final V value;

        public Pair(K key, V value) {
            this.key = key;
            this.value = value;
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "Pair{" +
                    "key=" + key +
                    ", value=" + value +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            Pair<?, ?> pair = (Pair<?, ?>) o;

            if (key != null ? !key.equals(pair.key) : pair.key != null)
                return false;
            return value != null ? value.equals(pair.value) : pair.value == null;
        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (value != null ? value.hashCode() : 0);
            return result;
        }
    }

    public NetworkMetrics(int windowSize, long timeWindow) {
        this.rttSamples = new ArrayDeque<>(windowSize);
        this.timeWindow = timeWindow;
        this.jitterSamples = new ArrayDeque<>(windowSize);
        this.packetLossSamples = new ArrayDeque<>(windowSize);
        throughputSamples = new LinkedList<>();
        this.lastRtt = null;
    }

    public void updateRtt(long rtt) {
        if (!rttSamples.offer(rtt)) {
            rttSamples.poll();
            rttSamples.add(rtt);
        }
        if (lastRtt != null) {
            long jitter = Math.abs(rtt - lastRtt);
            if (!jitterSamples.offer(jitter)) {
                jitterSamples.poll();
                jitterSamples.add(jitter);
            }
        }
        lastRtt = rtt;
    }

    public void updatePacketLoss(boolean lost) {
        if (!packetLossSamples.offer(lost)) {
            packetLossSamples.poll();
            packetLossSamples.add(lost);
        }
    }

    public void updateThroughput(long bytes, long timestamp) {
        throughputSamples.add(new Pair<>(timestamp, bytes));
        long currentTime = System.currentTimeMillis();
        while (!throughputSamples.isEmpty() && (currentTime - throughputSamples.peekFirst().getKey() > timeWindow)) {
            throughputSamples.pollFirst();
        }
    }

    public double getAverageThroughput() {
        long currentTime = System.currentTimeMillis();
        long totalBytes = 0;
        long earliestTimestamp = currentTime;

        for (Pair<Long, Long> sample : throughputSamples) {
            if (currentTime - sample.getKey() <= timeWindow) {
                totalBytes += sample.getValue();
                earliestTimestamp = Math.min(earliestTimestamp, sample.getKey());
            }
        }

        long duration = currentTime - earliestTimestamp;
        return duration > 0 ? (double) totalBytes / (duration / 1000.0) : 0;
    }

    public double getAverageRtt() {
        long sum = 0;
        for (long rtt : rttSamples) {
            sum += rtt;
        }
        return rttSamples.size() > 0 ? (double) sum / rttSamples.size() : 0;
    }

    public double getPacketLossRate() {
        int lossCount = 0;
        for (boolean lost : packetLossSamples) {
            if (lost) {
                lossCount++;
            }
        }
        return packetLossSamples.size() > 0 ? (double) lossCount / packetLossSamples.size() : 0;
    }

    public double getAverageJitter() {
        long sum = 0;
        for (long jitter : jitterSamples) {
            sum += jitter;
        }
        return jitterSamples.size() > 0 ? (double) sum / jitterSamples.size() : 0;
    }

    public void reset() {
        rttSamples.clear();
        jitterSamples.clear();
        packetLossSamples.clear();
        throughputSamples.clear();
        lastRtt = null;
    }
}