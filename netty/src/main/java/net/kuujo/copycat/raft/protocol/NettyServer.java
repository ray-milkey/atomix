/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat.raft.protocol;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import net.kuujo.copycat.Listener;
import net.kuujo.copycat.raft.Member;
import net.kuujo.copycat.util.concurrent.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty server.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class NettyServer implements Server {
  private static final Logger LOGGER = LoggerFactory.getLogger(NettyServer.class);
  private static final ByteBufAllocator ALLOCATOR = new PooledByteBufAllocator(true);
  private static final ChannelHandler FIELD_PREPENDER = new LengthFieldPrepender(2);

  private final int id;
  private final EventLoopGroup eventLoopGroup;
  private final Map<Channel, NettyConnection> connections = new ConcurrentHashMap<>();
  private ServerHandler handler;
  private ChannelGroup channelGroup;
  private volatile boolean listening;
  private CompletableFuture<Void> listenFuture;

  public NettyServer(int id, EventLoopGroup eventLoopGroup) {
    this.id = id;
    this.eventLoopGroup = eventLoopGroup;
  }

  @Override
  public int id() {
    return id;
  }

  /**
   * Returns the current execution context.
   */
  private Context getContext() {
    Context context = Context.currentContext();
    if (context == null) {
      throw new IllegalStateException("not on a Copycat thread");
    }
    return context;
  }

  @Override
  public CompletableFuture<Void> listen(Member member, Listener<Connection> listener) {
    if (listening)
      return CompletableFuture.completedFuture(null);

    Context context = getContext();
    synchronized (this) {
      if (listenFuture == null) {
        listenFuture = new CompletableFuture<>();
        listen(member, listener, context);
      }
    }
    return listenFuture;
  }

  /**
   * Starts listening for the given member.
   */
  private void listen(Member member, Listener<Connection> listener, Context context) {
    channelGroup = new DefaultChannelGroup("copycat-acceptor-channels", GlobalEventExecutor.INSTANCE);

    handler = new ServerHandler(connections, listener, context);

    final ServerBootstrap bootstrap = new ServerBootstrap();
    bootstrap.group(eventLoopGroup)
      .channel(eventLoopGroup instanceof EpollEventLoopGroup ? EpollServerSocketChannel.class : NioServerSocketChannel.class)
      .handler(new LoggingHandler(LogLevel.DEBUG))
      .childHandler(new ChannelInitializer<SocketChannel>() {
        @Override
        public void initChannel(SocketChannel channel) throws Exception {
          ChannelPipeline pipeline = channel.pipeline();
          pipeline.addLast(FIELD_PREPENDER);
          pipeline.addLast(new LengthFieldBasedFrameDecoder(8192, 0, 2, 0, 2));
          pipeline.addLast(handler);
        }
      })
      .option(ChannelOption.SO_BACKLOG, 128)
      .option(ChannelOption.TCP_NODELAY, true)
      .option(ChannelOption.SO_REUSEADDR, true)
      .childOption(ChannelOption.ALLOCATOR, ALLOCATOR)
      .childOption(ChannelOption.SO_KEEPALIVE, true);

    try {
      // Bind and start to accept incoming connections.
      InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(member.host()), member.port());

      LOGGER.info("Binding to {}", address);

      ChannelFuture bindFuture = bootstrap.bind(member.host(), member.port());
      bindFuture.addListener((ChannelFutureListener) channelFuture -> {
        if (channelFuture.isSuccess()) {
          listening = true;
          member.configure(member.host(), ((InetSocketAddress) bindFuture.channel().localAddress()).getPort());
          context.execute(() -> {
            LOGGER.info("Listening at {}:{}", member.host(), member.port());
            listenFuture.complete(null);
          });
        } else {
          context.execute(() -> listenFuture.completeExceptionally(channelFuture.cause()));
        }
      });
      channelGroup.add(bindFuture.channel());
    } catch (Exception e) {
      listenFuture.completeExceptionally(e);
    }
  }

  @Override
  public CompletableFuture<Void> close() {
    int i = 0;
    CompletableFuture[] futures = new CompletableFuture[connections.size()];
    for (Connection connection : connections.values()) {
      futures[i++] = connection.close();
    }
    return CompletableFuture.allOf(futures);
  }

  /**
   * Server handler.
   */
  @ChannelHandler.Sharable
  private static class ServerHandler extends NettyHandler {

    private ServerHandler(Map<Channel, NettyConnection> connections, Listener<Connection> listener, Context context) {
      super(connections, listener, context);
    }

  }

}
