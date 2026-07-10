package com.ericko.redmiscreenbrightness;

/**
 * In-memory timestamped lux history inspired by AOSP's ambient-light buffer.
 * The buffer keeps one boundary sample when pruning so time-weighted averages
 * remain continuous across the requested horizon.
 */
public final class AmbientLightRingBuffer {
    private static final int INITIAL_CAPACITY = 32;
    private static final int MAX_CAPACITY = 256;

    private long[] times = new long[INITIAL_CAPACITY];
    private float[] luxValues = new float[INITIAL_CAPACITY];
    private int count;

    public void clear() {
        count = 0;
    }

    public int size() {
        return count;
    }

    public void push(long timeMs, float lux) {
        float safeLux = sanitizeLux(lux);
        if (count > 0) {
            long lastTime = times[count - 1];
            if (timeMs == lastTime) {
                luxValues[count - 1] = safeLux;
                return;
            }
            if (timeMs < lastTime) {
                timeMs = lastTime + 1L;
            }
        }

        ensureCapacity(count + 1);
        if (count >= times.length) {
            dropOldest();
        }
        times[count] = timeMs;
        luxValues[count] = safeLux;
        count++;
    }

    public void prune(long cutoffTimeMs) {
        if (count <= 1) {
            return;
        }

        int firstAtOrAfter = 0;
        while (firstAtOrAfter < count && times[firstAtOrAfter] < cutoffTimeMs) {
            firstAtOrAfter++;
        }

        int keepFrom;
        if (firstAtOrAfter <= 0) {
            keepFrom = 0;
        } else if (firstAtOrAfter >= count) {
            keepFrom = count - 1;
        } else {
            keepFrom = firstAtOrAfter - 1;
        }

        if (keepFrom > 0) {
            int newCount = count - keepFrom;
            System.arraycopy(times, keepFrom, times, 0, newCount);
            System.arraycopy(luxValues, keepFrom, luxValues, 0, newCount);
            count = newCount;
        }

        if (count > 0 && times[0] < cutoffTimeMs) {
            times[0] = cutoffTimeMs;
        }
    }

    /**
     * Returns a duration-weighted average with a small recency preference.
     * Recent samples receive up to three times the weight of the horizon edge.
     */
    public float calculateWeightedLux(long nowMs, long horizonMs) {
        if (count <= 0) {
            return -1f;
        }
        if (count == 1 || horizonMs <= 0L) {
            return luxValues[count - 1];
        }

        long cutoff = nowMs - horizonMs;
        double weightedLux = 0d;
        double totalWeight = 0d;

        for (int i = 0; i < count; i++) {
            long segmentStart = Math.max(cutoff, times[i]);
            long segmentEnd = i + 1 < count ? Math.min(nowMs, times[i + 1]) : nowMs;
            if (segmentEnd <= segmentStart) {
                continue;
            }

            double midpoint = segmentStart + (segmentEnd - segmentStart) * 0.5d;
            double normalizedRecency = (midpoint - cutoff) / Math.max(1d, horizonMs);
            normalizedRecency = Math.max(0d, Math.min(1d, normalizedRecency));
            double recencyWeight = 1d + 2d * normalizedRecency;
            double segmentWeight = (segmentEnd - segmentStart) * recencyWeight;

            weightedLux += luxValues[i] * segmentWeight;
            totalWeight += segmentWeight;
        }

        if (totalWeight <= 0d) {
            return luxValues[count - 1];
        }
        return (float) (weightedLux / totalWeight);
    }

    public float getLatestLux() {
        return count <= 0 ? -1f : luxValues[count - 1];
    }

    public long getOldestTimeMs() {
        return count <= 0 ? 0L : times[0];
    }

    public int countRecentAtOrAbove(long nowMs, long windowMs, float thresholdLux) {
        int matches = 0;
        long cutoff = nowMs - Math.max(0L, windowMs);
        for (int i = count - 1; i >= 0; i--) {
            if (times[i] < cutoff) {
                break;
            }
            if (luxValues[i] >= thresholdLux) {
                matches++;
            }
        }
        return matches;
    }

    public int countRecentAtOrBelow(long nowMs, long windowMs, float thresholdLux) {
        int matches = 0;
        long cutoff = nowMs - Math.max(0L, windowMs);
        for (int i = count - 1; i >= 0; i--) {
            if (times[i] < cutoff) {
                break;
            }
            if (luxValues[i] <= thresholdLux) {
                matches++;
            }
        }
        return matches;
    }

    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= times.length || times.length >= MAX_CAPACITY) {
            return;
        }
        int newCapacity = Math.min(MAX_CAPACITY, Math.max(requiredCapacity, times.length * 2));
        long[] newTimes = new long[newCapacity];
        float[] newLuxValues = new float[newCapacity];
        System.arraycopy(times, 0, newTimes, 0, count);
        System.arraycopy(luxValues, 0, newLuxValues, 0, count);
        times = newTimes;
        luxValues = newLuxValues;
    }

    private void dropOldest() {
        if (count <= 0) {
            return;
        }
        System.arraycopy(times, 1, times, 0, count - 1);
        System.arraycopy(luxValues, 1, luxValues, 0, count - 1);
        count--;
    }

    private float sanitizeLux(float lux) {
        if (Float.isNaN(lux) || Float.isInfinite(lux) || lux < 0f) {
            return 0f;
        }
        return lux;
    }
}
