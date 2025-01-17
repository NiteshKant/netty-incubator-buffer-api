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

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.api.adaptor.ByteBufAllocatorAdaptor;
import io.netty.buffer.api.examples.echo.EchoServerHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.nio.NioHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class EchoIT {
    // In this test we have a server and a client, where the server echos back anything it receives,
    // and our client sends a single message to the server, and then verifies that it receives it back.

    @Test
    void echoServerMustReplyWithSameData() throws Exception {
        ByteBufAllocatorAdaptor allocator = new ByteBufAllocatorAdaptor();
        EventLoopGroup bossGroup = new MultithreadEventLoopGroup(1, NioHandler.newFactory());
        EventLoopGroup workerGroup = new MultithreadEventLoopGroup(NioHandler.newFactory());
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap server = new ServerBootstrap();
            server.group(bossGroup, workerGroup)
                  .channel(NioServerSocketChannel.class)
                  .childOption(ChannelOption.ALLOCATOR, allocator)
                  .option(ChannelOption.SO_BACKLOG, 100)
                  .handler(new LoggingHandler(LogLevel.INFO))
                  .childHandler(new ChannelInitializer<SocketChannel>() {
                      @Override
                      public void initChannel(SocketChannel ch) throws Exception {
                          ChannelPipeline p = ch.pipeline();
                          p.addLast(new LoggingHandler(LogLevel.INFO));
                          p.addLast(serverHandler);
                      }
                  });

            // Start the server.
            ChannelFuture bind = server.bind("localhost", 0).sync();
            InetSocketAddress serverAddress = (InetSocketAddress) bind.channel().localAddress();

            // Configure the client.
            EventLoopGroup group = new MultithreadEventLoopGroup(NioHandler.newFactory());
            try {
                Bootstrap b = new Bootstrap();
                b.group(group)
                 .channel(NioSocketChannel.class)
                 .option(ChannelOption.TCP_NODELAY, true)
                 .handler(new ChannelInitializer<SocketChannel>() {
                     @Override
                     public void initChannel(SocketChannel ch) throws Exception {
                         ChannelPipeline p = ch.pipeline();
                         p.addLast(new LoggingHandler(LogLevel.INFO));
                         p.addLast(new EchoClientHandler());
                     }
                 });

                // Start the client.
                ChannelFuture f = b.connect(serverAddress).sync();

                // Wait until the connection is closed.
                f.channel().closeFuture().sync();
            } finally {
                // Shut down the event loop to terminate all threads.
                group.shutdownGracefully();
            }

            // Shut down the server.
            bind.channel().close().sync();
        } finally {
            // Shut down all event loops to terminate all threads.
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            allocator.close();
        }
    }

    static class EchoClientHandler implements ChannelHandler {
        private static final int SIZE = 256;
        private final Buffer firstMessage;

        /**
         * Creates a client-side handler.
         */
        EchoClientHandler() {
            firstMessage = BufferAllocator.heap().allocate(SIZE);
            for (int i = 0; i < SIZE; i++) {
                firstMessage.writeByte((byte) i);
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            ctx.writeAndFlush(firstMessage);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            assertEquals(SIZE, buf.capacity());
            assertEquals(SIZE, buf.readableBytes());
            for (int i = 0; i < SIZE; i++) {
                assertEquals((byte) i, buf.readByte());
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Close the connection when an exception is raised.
            ctx.close();
            throw new RuntimeException(cause);
        }
    }
}
