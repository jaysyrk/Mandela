package io.github.jaysyrk.graphene.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class IntLongMapTest {

    private static final long MISSING = -999L;

    @Test
    void storesAndRetrieves() {
        IntLongMap map = new IntLongMap();
        assertTrue(map.put(7, 42L));
        assertFalse(map.put(7, 43L), "second put of the same key is an overwrite, not an insert");
        assertEquals(43L, map.get(7, MISSING));
        assertEquals(1, map.size());
        assertEquals(MISSING, map.get(8, MISSING));
    }

    @Test
    void handlesNegativeKeys() {
        IntLongMap map = new IntLongMap();
        map.put(-5, 1L);
        map.put(Integer.MAX_VALUE, 2L);
        map.put(Integer.MIN_VALUE + 1, 3L);
        assertEquals(1L, map.get(-5, MISSING));
        assertEquals(2L, map.get(Integer.MAX_VALUE, MISSING));
        assertEquals(3L, map.get(Integer.MIN_VALUE + 1, MISSING));
    }

    @Test
    void rejectsTheReservedKey() {
        IntLongMap map = new IntLongMap();
        assertThrows(IllegalArgumentException.class, () -> map.put(IntLongMap.EMPTY, 1L));
        assertThrows(IllegalArgumentException.class, () -> map.get(IntLongMap.EMPTY, 0L));
    }

    @Test
    void growsWithoutLosingEntries() {
        IntLongMap map = new IntLongMap(16, 0.6f);
        for (int i = 0; i < 5_000; i++) {
            map.put(i, (long) i * 3);
        }
        assertEquals(5_000, map.size());
        for (int i = 0; i < 5_000; i++) {
            assertEquals((long) i * 3, map.get(i, MISSING), "key " + i);
        }
    }

    /**
     * Backward-shift deletion is the part most likely to be subtly wrong, and a subtle bug shows up
     * as an entry that is present but unreachable. Randomised operations against a HashMap oracle
     * catch that in a way hand-written cases do not.
     */
    @Test
    void matchesAHashMapUnderRandomOperations() {
        Random random = new Random(20260821L);
        IntLongMap map = new IntLongMap(16, 0.7f);
        Map<Integer, Long> oracle = new HashMap<>();

        for (int step = 0; step < 200_000; step++) {
            // A small key space forces collisions and long probe chains, which is where the
            // interesting failures live.
            int key = random.nextInt(2_000) - 1_000;
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    long value = random.nextLong();
                    boolean added = map.put(key, value);
                    assertEquals(oracle.put(key, value) == null, added, "insert flag at step " + step);
                }
                case 2 -> {
                    boolean removed = map.remove(key);
                    assertEquals(oracle.remove(key) != null, removed, "remove flag at step " + step);
                }
                default -> assertEquals(
                        oracle.getOrDefault(key, MISSING), map.get(key, MISSING),
                        "lookup at step " + step);
            }
            assertEquals(oracle.size(), map.size(), "size at step " + step);
        }

        for (Map.Entry<Integer, Long> e : oracle.entrySet()) {
            assertEquals(e.getValue().longValue(), map.get(e.getKey(), MISSING),
                    "surviving key " + e.getKey());
        }
    }

    @Test
    void removeIfDropsExactlyTheRejectedEntries() {
        IntLongMap map = new IntLongMap();
        Map<Integer, Long> oracle = new HashMap<>();
        for (int i = 0; i < 3_000; i++) {
            map.put(i, i);
            oracle.put(i, (long) i);
        }

        int removed = map.removeIf((key, value) -> value % 3 == 0);
        oracle.entrySet().removeIf(e -> e.getValue() % 3 != 0);

        assertEquals(3_000 - oracle.size(), removed);
        assertEquals(oracle.size(), map.size());
        for (int i = 0; i < 3_000; i++) {
            assertEquals(oracle.getOrDefault(i, MISSING), map.get(i, MISSING), "key " + i);
        }
    }

    @Test
    void removeIfSurvivesRemovingEverything() {
        IntLongMap map = new IntLongMap();
        for (int i = 0; i < 500; i++) {
            map.put(i, i);
        }
        assertEquals(500, map.removeIf((key, value) -> false));
        assertTrue(map.isEmpty());
        for (int i = 0; i < 500; i++) {
            assertEquals(MISSING, map.get(i, MISSING));
        }
    }

    @Test
    void forEachVisitsEveryEntryOnce() {
        IntLongMap map = new IntLongMap();
        Map<Integer, Long> seen = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            map.put(i * 7, i);
        }
        map.forEach((key, value) -> assertTrue(seen.put(key, value) == null, "visited " + key + " twice"));
        assertEquals(200, seen.size());
    }

    @Test
    void reusesSlotsAfterHeavyChurn() {
        IntLongMap map = new IntLongMap(64, 0.6f);
        for (int round = 0; round < 100; round++) {
            for (int i = 0; i < 500; i++) {
                map.put(round * 1000 + i, i);
            }
            for (int i = 0; i < 500; i++) {
                map.remove(round * 1000 + i);
            }
        }
        assertTrue(map.isEmpty());
        // Without backward-shift deletion this table would have grown every round on tombstones.
        assertTrue(map.capacity() <= 2048, "table grew to " + map.capacity() + " despite staying empty");
    }

    @Test
    void clearEmptiesTheTable() {
        IntLongMap map = new IntLongMap();
        for (int i = 1; i <= 100; i++) {
            map.put(i, i);
        }
        map.clear();
        assertEquals(0, map.size());
        assertEquals(MISSING, map.get(50, MISSING));
    }
}
