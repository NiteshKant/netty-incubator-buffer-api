/*
 * Copyright 2021 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.buffer.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.assertj.core.api.Assertions.assertThat;

public class BufferWriteBytesCombinationsTest extends BufferTestSupport {
    private static final Memoize<Fixture[]> OTHER_FIXTURES = new Memoize<Fixture[]>(
            () -> Arrays.stream(allocators()).filter(filterOfTheDay(10)).toArray(Fixture[]::new));

    @ParameterizedTest
    @MethodSource("allocators")
    public void writeBytesMustTransferDataAndUpdateOffsets(Fixture fixture) {
        try (BufferAllocator alloc1 = fixture.createAllocator()) {
            // Only test 10% of available combinations. Otherwise, this takes too long.
            Fixture[] allocators = OTHER_FIXTURES.get();
            Arrays.stream(allocators).parallel().forEach(otherFixture -> {
                try (BufferAllocator alloc2 = otherFixture.createAllocator();
                     Buffer target = alloc1.allocate(37);
                     Buffer source = alloc2.allocate(35)) {
                    // BE to BE
                    target.order(BIG_ENDIAN);
                    source.order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);

                    // LE to BE
                    target.fill((byte) 0).reset().order(BIG_ENDIAN);
                    source.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    verifyWriteBytes(target, source);

                    // BE to LE
                    target.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    source.fill((byte) 0).reset().order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);

                    // LE to LE
                    target.fill((byte) 0).reset().order(LITTLE_ENDIAN);
                    source.fill((byte) 0).reset().order(BIG_ENDIAN);
                    verifyWriteBytes(target, source);
                } catch (Exception e) {
                    e.addSuppressed(new RuntimeException("other fixture was: " + otherFixture));
                    throw e;
                }
            });
        }
    }

    private static void verifyWriteBytes(Buffer target, Buffer source) {
        for (int i = 0; i < 35; i++) {
            source.writeByte((byte) (i + 1));
        }
        target.writeBytes(source);
        assertThat(target.readerOffset()).isZero();
        assertThat(target.writerOffset()).isEqualTo(35);
        assertThat(source.readerOffset()).isEqualTo(35);
        assertThat(source.writerOffset()).isEqualTo(35);
        try (Buffer readableSlice = target.slice()) {
            assertEquals(source, readableSlice);
        }
    }
}
