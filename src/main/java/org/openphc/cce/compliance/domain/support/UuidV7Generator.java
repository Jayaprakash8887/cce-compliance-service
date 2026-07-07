package org.openphc.cce.compliance.domain.support;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidValueGenerator;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 version 7 (time-ordered) UUIDs for use as entity identifiers.
 *
 * <p>Layout (128 bits):
 * <pre>
 *   48 bits  unix_ts_ms   big-endian Unix timestamp in milliseconds
 *    4 bits  version      0b0111 (7)
 *   12 bits  counter      monotonic sequence within a millisecond (rand_a)
 *    2 bits  variant      0b10
 *   62 bits  random       (rand_b)
 * </pre>
 *
 * <p>Because the timestamp occupies the most significant bytes and PostgreSQL compares
 * {@code uuid} values byte-wise, ordering by the id column yields approximate creation
 * order. Within a single millisecond the 12-bit counter preserves generation order for
 * ids minted by this process; if the counter overflows (&gt;4096 ids in one millisecond)
 * the timestamp is borrowed forward to keep ids strictly increasing. Ordering guarantees
 * are per-process only and hold at millisecond granularity — authoritative ordering must
 * still rely on an explicit timestamp column.
 */
public final class UuidV7Generator implements UuidValueGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_COUNTER = 0x0FFFL; // 12 bits

    private final Object lock = new Object();
    private long lastMillis = -1L;
    private long counter = 0L;

    @Override
    public UUID generateUuid(SharedSessionContractImplementor session) {
        final long timestamp;
        final long sequence;

        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = 0L;
            } else {
                // Same millisecond, or the wall clock moved backwards: keep advancing
                // the sequence so ids stay monotonic. Borrow into the timestamp on overflow.
                counter++;
                if (counter > MAX_COUNTER) {
                    lastMillis++;
                    counter = 0L;
                }
            }
            timestamp = lastMillis;
            sequence = counter;
        }

        long msb = (timestamp << 16)      // 48-bit timestamp in the high bytes
                | (0x7L << 12)            // version 7
                | (sequence & MAX_COUNTER); // 12-bit monotonic counter

        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) // clear the two variant bits
                | 0x8000000000000000L;                       // variant 0b10

        return new UUID(msb, lsb);
    }
}
