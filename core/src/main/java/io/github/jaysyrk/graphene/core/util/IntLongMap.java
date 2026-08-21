package io.github.jaysyrk.graphene.core.util;

/**
 * An open-addressed {@code int -> long} map with no boxing and no per-operation allocation.
 *
 * <p>The visibility cache touches one entry per entity per frame. With {@code HashMap<Integer, Long>}
 * that is two boxes and a node dereference per entity per frame, which on a busy server is thousands
 * of short-lived objects every frame -- exactly the allocation rate that turns into the GC pauses
 * this mod exists to remove. A flat pair of primitive arrays makes the same lookup a cache-friendly
 * probe.
 *
 * <p>Deletion uses backward-shift rather than tombstones, so a long-running session that cycles
 * through entity ids never degrades into scanning a table full of gravestones.
 *
 * <p>{@link Integer#MIN_VALUE} is reserved as the empty marker and may not be used as a key. Not
 * thread safe.
 */
public final class IntLongMap {

    /** Reserved key marking an unoccupied slot. */
    public static final int EMPTY = Integer.MIN_VALUE;

    private static final int MIN_CAPACITY = 16;

    private int[] keys;
    private long[] values;
    private int mask;
    private int size;
    private int resizeThreshold;
    private final float loadFactor;

    public IntLongMap() {
        this(64, 0.6f);
    }

    public IntLongMap(int expectedSize, float loadFactor) {
        if (loadFactor <= 0.1f || loadFactor >= 0.95f) {
            throw new IllegalArgumentException("loadFactor out of range: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        int capacity = tableSizeFor(Math.max(MIN_CAPACITY, (int) (expectedSize / loadFactor) + 1));
        allocate(capacity);
    }

    private static int tableSizeFor(int n) {
        int cap = MIN_CAPACITY;
        while (cap < n) {
            cap <<= 1;
        }
        return cap;
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        values = new long[capacity];
        java.util.Arrays.fill(keys, EMPTY);
        mask = capacity - 1;
        resizeThreshold = (int) (capacity * loadFactor);
    }

    /**
     * Fibonacci hashing. Entity ids are small sequential integers, and the identity hash of a
     * sequence collides catastrophically under linear probing once the table has any holes in it.
     */
    private int slotOf(int key) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }

    private void checkKey(int key) {
        if (key == EMPTY) {
            throw new IllegalArgumentException("Integer.MIN_VALUE is reserved as the empty marker");
        }
    }

    /** Returns the stored value, or {@code defaultValue} if the key is absent. */
    public long get(int key, long defaultValue) {
        checkKey(key);
        int i = slotOf(key);
        while (true) {
            int k = keys[i];
            if (k == EMPTY) {
                return defaultValue;
            }
            if (k == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
    }

    public boolean containsKey(int key) {
        checkKey(key);
        int i = slotOf(key);
        while (true) {
            int k = keys[i];
            if (k == EMPTY) {
                return false;
            }
            if (k == key) {
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    /** Inserts or overwrites. Returns true if the key was newly added. */
    public boolean put(int key, long value) {
        checkKey(key);
        int i = slotOf(key);
        while (true) {
            int k = keys[i];
            if (k == EMPTY) {
                keys[i] = key;
                values[i] = value;
                if (++size > resizeThreshold) {
                    grow();
                }
                return true;
            }
            if (k == key) {
                values[i] = value;
                return false;
            }
            i = (i + 1) & mask;
        }
    }

    /** Removes a key. Returns true if it was present. */
    public boolean remove(int key) {
        checkKey(key);
        int i = slotOf(key);
        while (true) {
            int k = keys[i];
            if (k == EMPTY) {
                return false;
            }
            if (k == key) {
                removeAt(i);
                size--;
                return true;
            }
            i = (i + 1) & mask;
        }
    }

    /**
     * Backward-shift deletion: walk forward from the hole, and move back any entry that probing
     * would no longer be able to reach past it. This keeps every remaining key on an unbroken probe
     * chain from its ideal slot, which is what lets lookups stop at the first empty slot.
     */
    private void removeAt(int index) {
        int i = index;
        int j = index;
        while (true) {
            keys[i] = EMPTY;
            while (true) {
                j = (j + 1) & mask;
                if (keys[j] == EMPTY) {
                    return;
                }
                int k = slotOf(keys[j]);
                // Is k cyclically within (i, j]? If so this entry is fine where it is.
                boolean settled = (i <= j) ? (i < k && k <= j) : (i < k || k <= j);
                if (!settled) {
                    break;
                }
            }
            keys[i] = keys[j];
            values[i] = values[j];
            i = j;
        }
    }

    private void grow() {
        int[] oldKeys = keys;
        long[] oldValues = values;
        allocate(keys.length << 1);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    /** Visits every entry. The callback must not modify the map. */
    public void forEach(EntryVisitor visitor) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != EMPTY) {
                visitor.accept(keys[i], values[i]);
            }
        }
    }

    /**
     * Removes every entry the predicate rejects, in one pass with no allocation. Used to evict
     * entities that have gone out of scope without the renderer telling us.
     *
     * @return how many entries were removed
     */
    public int removeIf(EntryPredicate predicate) {
        int removed = 0;
        int i = 0;
        while (i < keys.length) {
            int k = keys[i];
            if (k != EMPTY && !predicate.test(k, values[i])) {
                removeAt(i);
                size--;
                removed++;
                // removeAt may have shifted an entry into slot i, so re-examine it.
                continue;
            }
            i++;
        }
        return removed;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int capacity() {
        return keys.length;
    }

    public void clear() {
        java.util.Arrays.fill(keys, EMPTY);
        size = 0;
    }

    @FunctionalInterface
    public interface EntryVisitor {
        void accept(int key, long value);
    }

    @FunctionalInterface
    public interface EntryPredicate {
        /** Return true to keep the entry. */
        boolean test(int key, long value);
    }
}
