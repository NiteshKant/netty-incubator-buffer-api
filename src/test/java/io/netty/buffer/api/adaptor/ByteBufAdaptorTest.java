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
package io.netty.buffer.api.adaptor;

import io.netty.buffer.ByteBuf;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;

public class ByteBufAdaptorTest extends AbstractByteBufTest {
    static ByteBufAllocatorAdaptor alloc;

    @BeforeClass
    public static void setUpAllocator() {
        alloc = new ByteBufAllocatorAdaptor();
    }

    @AfterClass
    public static void tearDownAllocator() throws Exception {
        alloc.close();
    }

    @Override
    protected ByteBuf newBuffer(int capacity, int maxCapacity) {
        return alloc.buffer(capacity, capacity);
    }

    @Ignore("This test codifies that asking to reading 0 bytes from an empty but unclosed stream should return -1, " +
            "which is just weird.")
    @Override
    public void testStreamTransfer1() throws Exception {
    }

    @Ignore("Relies on capacity and max capacity being separate things.")
    @Override
    public void testCapacityIncrease() {
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityDecrease() {
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityNegative() {
        throw new IllegalArgumentException(); // Can't ignore tests annotated with throws expectation?
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testCapacityEnforceMaxCapacity() {
        throw new IllegalArgumentException(); // Can't ignore tests annotated with throws expectation?
    }

    @Ignore("Decreasing capacity not supported in new API.")
    @Override
    public void testMaxFastWritableBytes() {
    }

    @Ignore("Impossible to expose entire memory as a ByteBuffer using new API.")
    @Override
    public void testNioBufferExposeOnlyRegion() {
    }

    @Ignore("Impossible to expose entire memory as a ByteBuffer using new API.")
    @Override
    public void testToByteBuffer2() {
    }

    @Ignore("No longer allowed to allocate 0 sized buffers, except for composite buffers with no components.")
    @Override
    public void testLittleEndianWithExpand() {
    }
}
