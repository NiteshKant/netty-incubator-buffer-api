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
package io.netty.buffer.api.bytebuffer;

import io.netty.buffer.api.AllocatorControl;
import io.netty.buffer.api.Buffer;
import io.netty.buffer.api.BufferAllocator;
import io.netty.buffer.api.ByteCursor;
import io.netty.buffer.api.Drop;
import io.netty.buffer.api.Owned;
import io.netty.buffer.api.RcSupport;
import io.netty.buffer.api.ReadableComponent;
import io.netty.buffer.api.ReadableComponentProcessor;
import io.netty.buffer.api.WritableComponent;
import io.netty.buffer.api.WritableComponentProcessor;
import io.netty.buffer.api.internal.ArcDrop;
import io.netty.buffer.api.internal.Statics;
import io.netty.util.internal.PlatformDependent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;

import static io.netty.buffer.api.internal.Statics.bufferIsClosed;
import static io.netty.buffer.api.internal.Statics.bufferIsReadOnly;

class NioBuffer extends RcSupport<Buffer, NioBuffer> implements Buffer, ReadableComponent, WritableComponent {
    private static final ByteBuffer CLOSED_BUFFER = ByteBuffer.allocate(0);

    private final AllocatorControl control;
    private ByteBuffer base;
    private ByteBuffer rmem; // For reading.
    private ByteBuffer wmem; // For writing.

    private int roff;
    private int woff;
    private boolean constBuffer;

    NioBuffer(ByteBuffer base, ByteBuffer memory, AllocatorControl control, Drop<NioBuffer> drop) {
        super(new MakeInaccisbleOnDrop(ArcDrop.wrap(drop)));
        this.base = base;
        rmem = memory;
        wmem = memory;
        this.control = control;
    }

    /**
     * Constructor for {@linkplain BufferAllocator#constBufferSupplier(byte[]) const buffers}.
     */
    NioBuffer(NioBuffer parent) {
        super(new MakeInaccisbleOnDrop(new ArcDrop<>(ArcDrop.acquire(parent.unsafeGetDrop()))));
        control = parent.control;
        base = parent.base;
        rmem = parent.rmem.slice(0, parent.rmem.capacity()); // Need to slice to get independent byte orders.
        assert parent.wmem == CLOSED_BUFFER;
        wmem = CLOSED_BUFFER;
        roff = parent.roff;
        woff = parent.woff;
        order(parent.order());
        constBuffer = true;
    }

    private static final class MakeInaccisbleOnDrop implements Drop<NioBuffer> {
        final Drop<NioBuffer> delegate;

        private MakeInaccisbleOnDrop(Drop<NioBuffer> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void drop(NioBuffer buf) {
            try {
                delegate.drop(buf);
            } finally {
                buf.makeInaccessible();
            }
        }

        @Override
        public void attach(NioBuffer buf) {
            delegate.attach(buf);
        }

        @Override
        public String toString() {
            return "MemSegDrop(" + delegate + ')';
        }
    }

    @Override
    protected Drop<NioBuffer> unsafeGetDrop() {
        MakeInaccisbleOnDrop drop = (MakeInaccisbleOnDrop) super.unsafeGetDrop();
        return drop.delegate;
    }

    @Override
    protected void unsafeSetDrop(Drop<NioBuffer> replacement) {
        super.unsafeSetDrop(new MakeInaccisbleOnDrop(replacement));
    }

    @Override
    public String toString() {
        return "Buffer[roff:" + roff + ", woff:" + woff + ", cap:" + rmem.capacity() + ']';
    }

    @Override
    public Buffer order(ByteOrder order) {
        rmem.order(order);
        return this;
    }

    @Override
    public ByteOrder order() {
        return rmem.order();
    }

    @Override
    public int capacity() {
        return rmem.capacity();
    }

    @Override
    public int readerOffset() {
        return roff;
    }

    @Override
    public Buffer readerOffset(int offset) {
        checkRead(offset, 0);
        roff = offset;
        return this;
    }

    @Override
    public int writerOffset() {
        return woff;
    }

    @Override
    public Buffer writerOffset(int offset) {
        checkWrite(offset, 0);
        woff = offset;
        return this;
    }

    @Override
    public Buffer fill(byte value) {
        int capacity = capacity();
        checkSet(0, capacity);
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        for (int i = 0; i < capacity; i++) {
            wmem.put(i, value);
        }
        return this;
    }

    @Override
    public long nativeAddress() {
        return rmem.isDirect() && PlatformDependent.hasUnsafe()? PlatformDependent.directBufferAddress(rmem) : 0;
    }

    @Override
    public Buffer makeReadOnly() {
        wmem = CLOSED_BUFFER;
        return this;
    }

    @Override
    public boolean readOnly() {
        return wmem == CLOSED_BUFFER && rmem != CLOSED_BUFFER;
    }

    @Override
    public Buffer slice(int offset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative: " + length + '.');
        }
        if (!isAccessible()) {
            throw new IllegalStateException("This buffer is closed: " + this + '.');
        }
        ByteBuffer slice = rmem.slice(offset, length);
        ArcDrop<NioBuffer> drop = (ArcDrop<NioBuffer>) unsafeGetDrop();
        drop.increment();
        Buffer sliceBuffer = new NioBuffer(base, slice, control, drop)
                .writerOffset(length)
                .order(order());
        if (readOnly()) {
            sliceBuffer = sliceBuffer.makeReadOnly();
        }
        return sliceBuffer;
    }

    @Override
    public void copyInto(int srcPos, byte[] dest, int destPos, int length) {
        copyInto(srcPos, ByteBuffer.wrap(dest), destPos, length);
    }

    @Override
    public void copyInto(int srcPos, ByteBuffer dest, int destPos, int length) {
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        if (srcPos < 0) {
            throw new IllegalArgumentException("The srcPos cannot be negative: " + srcPos + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (capacity() < srcPos + length) {
            throw new IllegalArgumentException("The srcPos + length is beyond the end of the buffer: " +
                    "srcPos = " + srcPos + ", length = " + length + '.');
        }
        dest = dest.duplicate().clear();
        dest.put(destPos, rmem, srcPos, length);
    }

    @Override
    public void copyInto(int srcPos, Buffer dest, int destPos, int length) {
        if (dest instanceof NioBuffer) {
            var nb = (NioBuffer) dest;
            nb.checkSet(destPos, length);
            copyInto(srcPos, nb.wmem, destPos, length);
            return;
        }

        Statics.copyToViaReverseCursor(this, srcPos, dest, destPos, length);
    }

    @Override
    public ByteCursor openCursor() {
        return openCursor(readerOffset(), readableBytes());
    }

    @Override
    public ByteCursor openCursor(int fromOffset, int length) {
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        if (fromOffset < 0) {
            throw new IllegalArgumentException("The fromOffset cannot be negative: " + fromOffset + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (capacity() < fromOffset + length) {
            throw new IllegalArgumentException("The fromOffset + length is beyond the end of the buffer: " +
                    "fromOffset = " + fromOffset + ", length = " + length + '.');
        }
        return new ByteCursor() {
            // Duplicate source buffer to keep our own byte order state.
            final ByteBuffer buffer = rmem.duplicate().order(ByteOrder.BIG_ENDIAN);
            int index = fromOffset;
            final int end = index + length;
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (index + Long.BYTES <= end) {
                    longValue = buffer.getLong(index);
                    index += Long.BYTES;
                    return true;
                }
                return false;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (index < end) {
                    byteValue = buffer.get(index);
                    index++;
                    return true;
                }
                return false;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                return index;
            }

            @Override
            public int bytesLeft() {
                return end - index;
            }
        };
    }

    @Override
    public ByteCursor openReverseCursor(int fromOffset, int length) {
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        if (fromOffset < 0) {
            throw new IllegalArgumentException("The fromOffset cannot be negative: " + fromOffset + '.');
        }
        if (length < 0) {
            throw new IllegalArgumentException("The length cannot be negative: " + length + '.');
        }
        if (capacity() <= fromOffset) {
            throw new IllegalArgumentException("The fromOffset is beyond the end of the buffer: " + fromOffset + '.');
        }
        if (fromOffset - length < -1) {
            throw new IllegalArgumentException("The fromOffset - length would underflow the buffer: " +
                    "fromOffset = " + fromOffset + ", length = " + length + '.');
        }
        return new ByteCursor() {
            final ByteBuffer buffer = rmem.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int index = fromOffset;
            final int end = index - length;
            long longValue = -1;
            byte byteValue = -1;

            @Override
            public boolean readLong() {
                if (index - Long.BYTES >= end) {
                    index -= 7;
                    longValue = buffer.getLong(index);
                    index--;
                    return true;
                }
                return false;
            }

            @Override
            public long getLong() {
                return longValue;
            }

            @Override
            public boolean readByte() {
                if (index > end) {
                    byteValue = buffer.get(index);
                    index--;
                    return true;
                }
                return false;
            }

            @Override
            public byte getByte() {
                return byteValue;
            }

            @Override
            public int currentOffset() {
                return index;
            }

            @Override
            public int bytesLeft() {
                return index - end;
            }
        };
    }

    @Override
    public void ensureWritable(int size, int minimumGrowth, boolean allowCompaction) {
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException(
                    "Buffer is not owned. Only owned buffers can call ensureWritable."));
        }
        if (size < 0) {
            throw new IllegalArgumentException("Cannot ensure writable for a negative size: " + size + '.');
        }
        if (minimumGrowth < 0) {
            throw new IllegalArgumentException("The minimum growth cannot be negative: " + minimumGrowth + '.');
        }
        if (rmem != wmem) {
            throw bufferIsReadOnly();
        }
        if (writableBytes() >= size) {
            // We already have enough space.
            return;
        }

        if (allowCompaction && writableBytes() + readerOffset() >= size) {
            // We can solve this with compaction.
            compact();
            return;
        }

        // Allocate a bigger buffer.
        long newSize = capacity() + (long) Math.max(size - writableBytes(), minimumGrowth);
        BufferAllocator.checkSize(newSize);
        ByteBuffer buffer = (ByteBuffer) control.allocateUntethered(this, (int) newSize);
        buffer.order(order());

        // Copy contents.
        copyInto(0, buffer, 0, capacity());

        // Release the old memory and install the new:
        Drop<NioBuffer> drop = disconnectDrop();
        attachNewBuffer(buffer, drop);
    }

    private Drop<NioBuffer> disconnectDrop() {
        var drop = (Drop<NioBuffer>) unsafeGetDrop();
        int roff = this.roff;
        int woff = this.woff;
        drop.drop(this);
        drop = ArcDrop.unwrapAllArcs(drop);
        unsafeSetDrop(new ArcDrop<>(drop));
        this.roff = roff;
        this.woff = woff;
        return drop;
    }

    private void attachNewBuffer(ByteBuffer buffer, Drop<NioBuffer> drop) {
        base = buffer;
        rmem = buffer;
        wmem = buffer;
        constBuffer = false;
        drop.attach(this);
    }

    @Override
    public Buffer split(int splitOffset) {
        if (splitOffset < 0) {
            throw new IllegalArgumentException("The split offset cannot be negative: " + splitOffset + '.');
        }
        if (capacity() < splitOffset) {
            throw new IllegalArgumentException("The split offset cannot be greater than the buffer capacity, " +
                    "but the split offset was " + splitOffset + ", and capacity is " + capacity() + '.');
        }
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException("Cannot split a buffer that is not owned."));
        }
        var drop = (ArcDrop<NioBuffer>) unsafeGetDrop();
        unsafeSetDrop(new ArcDrop<>(drop));
        var splitByteBuffer = rmem.slice(0, splitOffset);
        // TODO maybe incrementing the existing ArcDrop is enough; maybe we don't need to wrap it in another ArcDrop.
        var splitBuffer = new NioBuffer(base, splitByteBuffer, control, new ArcDrop<>(drop.increment()));
        splitBuffer.woff = Math.min(woff, splitOffset);
        splitBuffer.roff = Math.min(roff, splitOffset);
        splitBuffer.order(order());
        boolean readOnly = readOnly();
        if (readOnly) {
            splitBuffer.makeReadOnly();
        }
        // Note that split, unlike slice, does not deconstify, because data changes in either buffer are not visible
        // in the other. The split buffers can later deconstify independently if needed.
        splitBuffer.constBuffer = constBuffer;
        rmem = rmem.slice(splitOffset, rmem.capacity() - splitOffset);
        if (!readOnly) {
            wmem = rmem;
        }
        woff = Math.max(woff, splitOffset) - splitOffset;
        roff = Math.max(roff, splitOffset) - splitOffset;
        return splitBuffer;
    }

    @Override
    public void compact() {
        if (!isOwned()) {
            throw attachTrace(new IllegalStateException("Buffer must be owned in order to compact."));
        }
        if (readOnly()) {
            throw new IllegalStateException("Buffer must be writable in order to compact, but was read-only.");
        }
        if (roff == 0) {
            return;
        }
        rmem.limit(woff).position(roff).compact().clear();
        woff -= roff;
        roff = 0;
    }

    @Override
    public int countComponents() {
        return 1;
    }

    @Override
    public int countReadableComponents() {
        return readableBytes() > 0? 1 : 0;
    }

    @Override
    public int countWritableComponents() {
        return writableBytes() > 0? 1 : 0;
    }

    // <editor-fold defaultstate="collapsed" desc="Readable/WritableComponent implementation.">
    @Override
    public boolean hasReadableArray() {
        return rmem.hasArray();
    }

    @Override
    public byte[] readableArray() {
        return rmem.array();
    }

    @Override
    public int readableArrayOffset() {
        return rmem.arrayOffset() + roff;
    }

    @Override
    public int readableArrayLength() {
        return woff - roff;
    }

    @Override
    public long readableNativeAddress() {
        return nativeAddress();
    }

    @Override
    public ByteBuffer readableBuffer() {
        return rmem.asReadOnlyBuffer().slice(readerOffset(), readableBytes()).order(order());
    }

    @Override
    public boolean hasWritableArray() {
        return wmem.hasArray();
    }

    @Override
    public byte[] writableArray() {
        return wmem.array();
    }

    @Override
    public int writableArrayOffset() {
        return wmem.arrayOffset() + woff;
    }

    @Override
    public int writableArrayLength() {
        return capacity() - woff;
    }

    @Override
    public long writableNativeAddress() {
        return nativeAddress();
    }

    @Override
    public ByteBuffer writableBuffer() {
        return wmem.slice(writerOffset(), writableBytes()).order(order());
    }
    // </editor-fold>

    @Override
    public <E extends Exception> int forEachReadable(int initialIndex, ReadableComponentProcessor<E> processor)
            throws E {
        checkRead(readerOffset(), Math.max(1, readableBytes()));
        return processor.process(initialIndex, this)? 1 : -1;
    }

    @Override
    public <E extends Exception> int forEachWritable(int initialIndex, WritableComponentProcessor<E> processor)
            throws E {
        checkWrite(writerOffset(), Math.max(1, writableBytes()));
        return processor.process(initialIndex, this)? 1 : -1;
    }

    // <editor-fold defaultstate="collapsed" desc="Primitive accessors implementation.">
    @Override
    public byte readByte() {
        checkRead(roff, Byte.BYTES);
        var value = rmem.get(roff);
        roff += Byte.BYTES;
        return value;
    }

    @Override
    public byte getByte(int roff) {
        checkGet(roff, Byte.BYTES);
        return rmem.get(roff);
    }

    @Override
    public int readUnsignedByte() {
        return readByte() & 0xFF;
    }

    @Override
    public int getUnsignedByte(int roff) {
        return getByte(roff) & 0xFF;
    }

    @Override
    public Buffer writeByte(byte value) {
        try {
            wmem.put(woff, value);
            woff += Byte.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setByte(int woff, byte value) {
        try {
            wmem.put(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer writeUnsignedByte(int value) {
        try {
            wmem.put(woff, (byte) (value & 0xFF));
            woff += Byte.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setUnsignedByte(int woff, int value) {
        try {
            wmem.put(woff, (byte) (value & 0xFF));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public char readChar() {
        checkRead(roff, 2);
        var value = rmem.getChar(roff);
        roff += 2;
        return value;
    }

    @Override
    public char getChar(int roff) {
        checkGet(roff, 2);
        return rmem.getChar(roff);
    }

    @Override
    public Buffer writeChar(char value) {
        try {
            wmem.putChar(woff, value);
            woff += 2;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setChar(int woff, char value) {
        try {
            wmem.putChar(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public short readShort() {
        checkRead(roff, Short.BYTES);
        var value = rmem.getShort(roff);
        roff += 2;
        return value;
    }

    @Override
    public short getShort(int roff) {
        checkGet(roff, Short.BYTES);
        return rmem.getShort(roff);
    }

    @Override
    public int readUnsignedShort() {
        checkRead(roff, Short.BYTES);
        var value = rmem.getShort(roff) & 0xFFFF;
        roff += 2;
        return value;
    }

    @Override
    public int getUnsignedShort(int roff) {
        checkGet(roff, Short.BYTES);
        return rmem.getShort(roff) & 0xFFFF;
    }

    @Override
    public Buffer writeShort(short value) {
        try {
            wmem.putShort(woff, value);
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setShort(int woff, short value) {
        try {
            wmem.putShort(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer writeUnsignedShort(int value) {
        try {
            wmem.putShort(woff, (short) (value & 0xFFFF));
            woff += Short.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setUnsignedShort(int woff, int value) {
        try {
            wmem.putShort(woff, (short) (value & 0xFFFF));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public int readMedium() {
        checkRead(roff, 3);
        int value = order() == ByteOrder.BIG_ENDIAN?
                rmem.get(roff) << 16 |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) & 0xFF :
                rmem.get(roff) & 0xFF |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) << 16;
        roff += 3;
        return value;
    }

    @Override
    public int getMedium(int roff) {
        checkGet(roff, 3);
        return order() == ByteOrder.BIG_ENDIAN?
                rmem.get(roff) << 16 |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) & 0xFF :
                rmem.get(roff) & 0xFF |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) << 16;
    }

    @Override
    public int readUnsignedMedium() {
        checkRead(roff, 3);
        int value = order() == ByteOrder.BIG_ENDIAN?
                (rmem.get(roff) << 16 |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) & 0xFF) & 0xFFFFFF :
                (rmem.get(roff) & 0xFF |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) << 16) & 0xFFFFFF;
        roff += 3;
        return value;
    }

    @Override
    public int getUnsignedMedium(int roff) {
        checkGet(roff, 3);
        return order() == ByteOrder.BIG_ENDIAN?
                (rmem.get(roff) << 16 |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) & 0xFF) & 0xFFFFFF :
                (rmem.get(roff) & 0xFF |
                (rmem.get(roff + 1) & 0xFF) << 8 |
                rmem.get(roff + 2) << 16) & 0xFFFFFF;
    }

    @Override
    public Buffer writeMedium(int value) {
        checkWrite(woff, 3);
        if (order() == ByteOrder.BIG_ENDIAN) {
            wmem.put(woff, (byte) (value >> 16));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value & 0xFF));
        } else {
            wmem.put(woff, (byte) (value & 0xFF));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buffer setMedium(int woff, int value) {
        checkSet(woff, 3);
        if (order() == ByteOrder.BIG_ENDIAN) {
            wmem.put(woff, (byte) (value >> 16));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value & 0xFF));
        } else {
            wmem.put(woff, (byte) (value & 0xFF));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public Buffer writeUnsignedMedium(int value) {
        checkWrite(woff, 3);
        if (order() == ByteOrder.BIG_ENDIAN) {
            wmem.put(woff, (byte) (value >> 16));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value & 0xFF));
        } else {
            wmem.put(woff, (byte) (value & 0xFF));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value >> 16 & 0xFF));
        }
        woff += 3;
        return this;
    }

    @Override
    public Buffer setUnsignedMedium(int woff, int value) {
        checkSet(woff, 3);
        if (order() == ByteOrder.BIG_ENDIAN) {
            wmem.put(woff, (byte) (value >> 16));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value & 0xFF));
        } else {
            wmem.put(woff, (byte) (value & 0xFF));
            wmem.put(woff + 1, (byte) (value >> 8 & 0xFF));
            wmem.put(woff + 2, (byte) (value >> 16 & 0xFF));
        }
        return this;
    }

    @Override
    public int readInt() {
        checkRead(roff, Integer.BYTES);
        var value = rmem.getInt(roff);
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public int getInt(int roff) {
        checkGet(roff, Integer.BYTES);
        return rmem.getInt(roff);
    }

    @Override
    public long readUnsignedInt() {
        checkRead(roff, Integer.BYTES);
        var value = rmem.getInt(roff) & 0xFFFFFFFFL;
        roff += Integer.BYTES;
        return value;
    }

    @Override
    public long getUnsignedInt(int roff) {
        checkGet(roff, Integer.BYTES);
        return rmem.getInt(roff) & 0xFFFFFFFFL;
    }

    @Override
    public Buffer writeInt(int value) {
        try {
            wmem.putInt(woff, value);
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setInt(int woff, int value) {
        try {
            wmem.putInt(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, this.woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer writeUnsignedInt(long value) {
        try {
            wmem.putInt(woff, (int) (value & 0xFFFFFFFFL));
            woff += Integer.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setUnsignedInt(int woff, long value) {
        try {
            wmem.putInt(woff, (int) (value & 0xFFFFFFFFL));
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, this.woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public float readFloat() {
        checkRead(roff, Float.BYTES);
        var value = rmem.getFloat(roff);
        roff += Float.BYTES;
        return value;
    }

    @Override
    public float getFloat(int roff) {
        checkGet(roff, Float.BYTES);
        return rmem.getFloat(roff);
    }

    @Override
    public Buffer writeFloat(float value) {
        try {
            wmem.putFloat(woff, value);
            woff += Float.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setFloat(int woff, float value) {
        try {
            wmem.putFloat(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public long readLong() {
        checkRead(roff, Long.BYTES);
        var value = rmem.getLong(roff);
        roff += Long.BYTES;
        return value;
    }

    @Override
    public long getLong(int roff) {
        checkGet(roff, Long.BYTES);
        return rmem.getLong(roff);
    }

    @Override
    public Buffer writeLong(long value) {
        try {
            wmem.putLong(woff, value);
            woff += Long.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setLong(int woff, long value) {
        try {
            wmem.putLong(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public double readDouble() {
        checkRead(roff, Double.BYTES);
        var value = rmem.getDouble(roff);
        roff += Double.BYTES;
        return value;
    }

    @Override
    public double getDouble(int roff) {
        checkGet(roff, Double.BYTES);
        return rmem.getDouble(roff);
    }

    @Override
    public Buffer writeDouble(double value) {
        try {
            wmem.putDouble(woff, value);
            woff += Double.BYTES;
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }

    @Override
    public Buffer setDouble(int woff, double value) {
        try {
            wmem.putDouble(woff, value);
            return this;
        } catch (IndexOutOfBoundsException e) {
            throw checkWriteState(e, woff);
        } catch (ReadOnlyBufferException e) {
            throw bufferIsReadOnly();
        }
    }
    // </editor-fold>

    @Override
    protected Owned<NioBuffer> prepareSend() {
        var order = order();
        var roff = this.roff;
        var woff = this.woff;
        var readOnly = readOnly();
        var isConst = constBuffer;
        ByteBuffer base = this.base;
        ByteBuffer rmem = this.rmem;
        makeInaccessible();
        return new Owned<NioBuffer>() {
            @Override
            public NioBuffer transferOwnership(Drop<NioBuffer> drop) {
                NioBuffer copy = new NioBuffer(base, rmem, control, drop);
                copy.order(order);
                copy.roff = roff;
                copy.woff = woff;
                if (readOnly) {
                    copy.makeReadOnly();
                }
                copy.constBuffer = isConst;
                return copy;
            }
        };
    }

    void makeInaccessible() {
        base = CLOSED_BUFFER;
        rmem = CLOSED_BUFFER;
        wmem = CLOSED_BUFFER;
        roff = 0;
        woff = 0;
    }

    @Override
    public boolean isOwned() {
        return super.isOwned() && ((ArcDrop<NioBuffer>) unsafeGetDrop()).isOwned();
    }

    @Override
    public int countBorrows() {
        return super.countBorrows() + ((ArcDrop<NioBuffer>) unsafeGetDrop()).countBorrows();
    }

    private void checkRead(int index, int size) {
        if (index < 0 || woff < index + size) {
            throw readAccessCheckException(index);
        }
    }

    private void checkGet(int index, int size) {
        if (index < 0 || capacity() < index + size) {
            throw readAccessCheckException(index);
        }
    }

    private void checkWrite(int index, int size) {
        if (index < roff || wmem.capacity() < index + size) {
            throw writeAccessCheckException(index);
        }
    }

    private void checkSet(int index, int size) {
        if (index < 0 || wmem.capacity() < index + size) {
            throw writeAccessCheckException(index);
        }
    }

    private RuntimeException checkWriteState(IndexOutOfBoundsException ioobe, int offset) {
        if (rmem == CLOSED_BUFFER) {
            return bufferIsClosed();
        }
        if (wmem != rmem) {
            return bufferIsReadOnly();
        }

        IndexOutOfBoundsException exception = outOfBounds(offset);
        exception.addSuppressed(ioobe);
        return exception;
    }

    private RuntimeException readAccessCheckException(int index) {
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        return outOfBounds(index);
    }

    private RuntimeException writeAccessCheckException(int index) {
        if (rmem == CLOSED_BUFFER) {
            throw bufferIsClosed();
        }
        if (wmem != rmem) {
            return bufferIsReadOnly();
        }
        return outOfBounds(index);
    }

    private IndexOutOfBoundsException outOfBounds(int index) {
        return new IndexOutOfBoundsException(
                "Index " + index + " is out of bounds: [read 0 to " + woff + ", write 0 to " +
                        rmem.capacity() + "].");
    }

    ByteBuffer recoverable() {
        return base;
    }
}
