package org.openphc.cce.compliance.domain.support;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7GeneratorTest {

    private final UuidV7Generator generator = new UuidV7Generator();

    /** Unsigned 128-bit comparison, matching how PostgreSQL orders the {@code uuid} type. */
    private static int compareUnsigned(UUID a, UUID b) {
        int hi = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return hi != 0 ? hi : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    private static long embeddedTimestampMs(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    @Test
    void setsVersion7AndRfcVariant() {
        UUID uuid = generator.generateUuid(null);
        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2); // RFC 4122 / 9562 variant (0b10)
    }

    @Test
    void embedsCurrentTimestamp() {
        long before = System.currentTimeMillis();
        UUID uuid = generator.generateUuid(null);
        long after = System.currentTimeMillis();

        assertThat(embeddedTimestampMs(uuid)).isBetween(before, after);
    }

    @Test
    void isStrictlyMonotonicWithinABurst() {
        int count = 100_000; // far exceeds 4096/ms, exercising the counter-overflow carry
        UUID previous = generator.generateUuid(null);
        for (int i = 1; i < count; i++) {
            UUID current = generator.generateUuid(null);
            assertThat(compareUnsigned(previous, current))
                    .as("uuid %d must sort strictly before uuid %d", i - 1, i)
                    .isNegative();
            previous = current;
        }
    }

    @Test
    void producesUniqueValues() {
        int count = 100_000;
        Set<UUID> seen = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            assertThat(seen.add(generator.generateUuid(null))).isTrue();
        }
    }

    @Test
    void isThreadSafe() throws InterruptedException {
        int threads = 8;
        int perThread = 20_000;
        Set<UUID> all = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        all.add(generator.generateUuid(null));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(all).hasSize(threads * perThread); // no collisions across threads
    }
}
