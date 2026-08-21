package io.github.jaysyrk.graphene.core.stats;

/**
 * A fixed-bucket histogram of frame times, tuned for the range a real frame can occupy.
 *
 * <p>Percentiles are the only frame-time statistic worth acting on: a mean hides the stutter that
 * players actually feel. Computing them by sorting a window would allocate and sort every frame, so
 * this keeps counts in a flat array instead. Recording is a branch, an index and an increment, with
 * no allocation, which means it is safe to call from the render loop.
 *
 * <p>Resolution is deliberately non-uniform. Below {@link #FINE_LIMIT_US} each bucket spans
 * {@link #FINE_BUCKET_US}, because that is the region where a 1&nbsp;ms shift changes the decision
 * the governor makes. Above it, buckets grow geometrically: once a frame takes 40&nbsp;ms nobody
 * needs to know whether it was 41 or 42.
 *
 * <p>The window slides by {@linkplain #decay() halving} all counts periodically, which weights
 * recent frames more heavily than old ones without ever needing to remember individual samples.
 * Not thread safe; one instance belongs to one thread.
 */
public final class FrameTimeHistogram {

    /** Frame times below this are bucketed at full resolution. */
    public static final int FINE_LIMIT_US = 40_000;
    /** Width of a bucket in the fine region. 250us is ~1/66th of a 16.7ms frame. */
    public static final int FINE_BUCKET_US = 250;
    /** Growth factor applied per bucket above the fine region. */
    private static final double COARSE_GROWTH = 1.08;
    /** Anything at or beyond this lands in the final bucket. */
    public static final long MAX_TRACKED_US = 4_000_000L;

    private static final int FINE_BUCKETS = FINE_LIMIT_US / FINE_BUCKET_US;

    /** Upper bound (exclusive) of each bucket, in microseconds. Shared by every instance. */
    private static final long[] BUCKET_BOUNDS = buildBounds();
    /** Representative value reported for each bucket, in microseconds. */
    private static final long[] BUCKET_VALUES = buildValues(BUCKET_BOUNDS);

    private final long[] counts = new long[BUCKET_BOUNDS.length];
    private long totalCount;
    private long sumMicros;
    private long maxMicros;

    private static long[] buildBounds() {
        long[] tmp = new long[512];
        int n = 0;
        for (int i = 1; i <= FINE_BUCKETS; i++) {
            tmp[n++] = (long) i * FINE_BUCKET_US;
        }
        double bound = FINE_LIMIT_US;
        while (bound < MAX_TRACKED_US) {
            bound *= COARSE_GROWTH;
            long rounded = (long) Math.ceil(bound);
            if (rounded <= tmp[n - 1]) {
                rounded = tmp[n - 1] + 1;
            }
            if (n == tmp.length) {
                long[] grown = new long[n * 2];
                System.arraycopy(tmp, 0, grown, 0, n);
                tmp = grown;
            }
            tmp[n++] = rounded;
            bound = rounded;
        }
        tmp[n - 1] = Long.MAX_VALUE;
        long[] bounds = new long[n];
        System.arraycopy(tmp, 0, bounds, 0, n);
        return bounds;
    }

    private static long[] buildValues(long[] bounds) {
        long[] values = new long[bounds.length];
        long lower = 0;
        for (int i = 0; i < bounds.length; i++) {
            long upper = bounds[i];
            // The last bucket is unbounded; report its lower edge rather than overflowing.
            values[i] = upper == Long.MAX_VALUE ? lower : (lower + upper) / 2;
            lower = upper;
        }
        return values;
    }

    /** Number of buckets. Exposed for tests and for sizing external copies. */
    public static int bucketCount() {
        return BUCKET_BOUNDS.length;
    }

    /**
     * Records one frame. Negative durations are ignored rather than clamped: they only arise from a
     * clock going backwards, and folding them into the zero bucket would drag percentiles down.
     */
    public void record(long nanos) {
        if (nanos < 0) {
            return;
        }
        long micros = nanos / 1_000L;
        counts[bucketOf(micros)]++;
        totalCount++;
        sumMicros += micros;
        if (micros > maxMicros) {
            maxMicros = micros;
        }
    }

    static int bucketOf(long micros) {
        if (micros < FINE_LIMIT_US) {
            return (int) (micros / FINE_BUCKET_US);
        }
        int lo = FINE_BUCKETS;
        int hi = BUCKET_BOUNDS.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (micros < BUCKET_BOUNDS[mid]) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * Returns the frame time in microseconds at the given quantile, or 0 with no samples.
     *
     * @param quantile in [0, 1]; 0.99 asks "how slow are the worst 1% of frames"
     */
    public long percentileMicros(double quantile) {
        if (totalCount == 0) {
            return 0L;
        }
        double q = Math.min(1.0, Math.max(0.0, quantile));
        // Rank is 1-based so that q=1 selects the highest occupied bucket.
        long rank = (long) Math.ceil(q * totalCount);
        if (rank < 1) {
            rank = 1;
        }
        long seen = 0;
        for (int i = 0; i < counts.length; i++) {
            seen += counts[i];
            if (seen >= rank) {
                return BUCKET_VALUES[i];
            }
        }
        return BUCKET_VALUES[counts.length - 1];
    }

    /** Mean frame time in microseconds, or 0 with no samples. */
    public long meanMicros() {
        return totalCount == 0 ? 0L : sumMicros / totalCount;
    }

    /** Longest frame seen in the current window, in microseconds. */
    public long maxMicros() {
        return maxMicros;
    }

    public long sampleCount() {
        return totalCount;
    }

    /**
     * Ages the window by halving every count, so recent frames outweigh old ones. Odd counts round
     * down, which lets a bucket that stops being hit drain to zero instead of lingering at one.
     */
    public void decay() {
        long remaining = 0;
        long remainingSum = 0;
        for (int i = 0; i < counts.length; i++) {
            long halved = counts[i] >> 1;
            counts[i] = halved;
            remaining += halved;
            remainingSum += halved * BUCKET_VALUES[i];
        }
        totalCount = remaining;
        sumMicros = remaining == 0 ? 0 : remainingSum;
        if (remaining == 0) {
            maxMicros = 0;
        }
    }

    public void reset() {
        java.util.Arrays.fill(counts, 0L);
        totalCount = 0;
        sumMicros = 0;
        maxMicros = 0;
    }
}
