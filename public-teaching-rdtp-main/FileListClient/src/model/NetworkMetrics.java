package model;

import java.util.ArrayDeque;
import java.util.Deque;

public class NetworkMetrics {
    private final Deque<Long> rttSamples;
    private final Deque<Long> jitterSamples;
    private final Deque<Boolean> packetLossSamples;
    private Long lastRtt;

    public NetworkMetrics(int windowSize) {
        this.rttSamples = new ArrayDeque<>(windowSize);
        this.jitterSamples = new ArrayDeque<>(windowSize);
        this.packetLossSamples = new ArrayDeque<>(windowSize);
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
        lastRtt = null;
    }
}