/*
 * Copyright 2019 The Netty Project
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
package io.netty.buffer.api.benchmarks;

import io.netty.buffer.api.Allocator;
import io.netty.buffer.api.Buf;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 5, jvmArgsAppend = { "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints" })
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class ByteIterationBenchmark {
    private static final int SIZE = 4096;
    @Param({"heap", "direct", "composite-heap", "composite-direct"})
    public String type;

    Allocator allocator;
    private Buf buf;

    @Setup
    public void setUp() {
        switch (type) {
        case "heap":
            allocator = Allocator.heap();
            buf = allocator.allocate(SIZE);
            break;
        case "direct":
            allocator = Allocator.direct();
            buf = allocator.allocate(SIZE);
            break;
        case "composite-heap":
            allocator = Allocator.heap();
            try (var a = allocator.allocate(SIZE / 2);
                 var b = allocator.allocate(SIZE / 2)) {
                buf = allocator.compose(a, b);
            }
            break;
        case "composite-direct":
            allocator = Allocator.direct();
            try (var a = allocator.allocate(SIZE / 2);
                 var b = allocator.allocate(SIZE / 2)) {
                buf = allocator.compose(a, b);
            }
            break;
        default:
            throw new IllegalArgumentException("Unknown buffer type: " + type + '.');
        }
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        while (buf.writableBytes() > 7) {
            buf.writeLong(tlr.nextLong());
        }
        while (buf.writableBytes() > 0) {
            buf.writeByte((byte) tlr.nextInt());
        }
    }

    @TearDown
    public void tearDown() {
        buf.close();
        allocator.close();
    }

    @Benchmark
    public long sum() {
        var itr = buf.iterate();
        long sum = 0;
        while (itr.hasNextLong()) {
            sum += itr.nextLong();
        }
        while (itr.hasNextByte()) {
            sum += itr.nextByte();
        }
        return sum;
    }

    @Benchmark
    public long sumReverse() {
        var itr = buf.iterateReverse();
        long sum = 0;
        while (itr.hasNextLong()) {
            sum += itr.nextLong();
        }
        while (itr.hasNextByte()) {
            sum += itr.nextByte();
        }
        return sum;
    }
}