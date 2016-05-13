/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static io.netty.handler.codec.http.websocketx.WebSocketUtil.addIfNotNull;
import static io.netty.handler.codec.http.websocketx.WebSocketUtil.decoder;
import static io.netty.handler.codec.http.websocketx.WebSocketUtil.handler;

/**
 * Base class for server side web socket opening and closing handshakes
 */
public abstract class WebSocketServerHandshaker {
    protected static final InternalLogger logger = InternalLoggerFactory.getInstance(WebSocketServerHandshaker.class);
    private static final ClosedChannelException CLOSED_CHANNEL_EXCEPTION = new ClosedChannelException();

    static {
        CLOSED_CHANNEL_EXCEPTION.setStackTrace(EmptyArrays.EMPTY_STACK_TRACE);
    }

    private final String uri;

    private final String[] subprotocols;

    private final WebSocketVersion version;

    private final int maxFramePayloadLength;

    private String selectedSubprotocol;

    /**
     * Use this as wildcard to support all requested sub-protocols
     */
    public static final String SUB_PROTOCOL_WILDCARD = "*";

    /**
     * Constructor specifying the destination web socket location
     *
     * @param version
     *            the protocol version
     * @param uri
     *            URL for web socket communications. e.g "ws://myhost.com/mypath". Subsequent web socket frames will be
     *            sent to this URL.
     * @param subprotocols
     *            CSV of supported protocols. Null if sub protocols not supported.
     * @param maxFramePayloadLength
     *            Maximum length of a frame's payload
     */
    protected WebSocketServerHandshaker(
            WebSocketVersion version, String uri, String subprotocols,
            int maxFramePayloadLength) {
        this.version = version;
        this.uri = uri;
        if (subprotocols != null) {
            String[] subprotocolArray = StringUtil.split(subprotocols, ',');
            for (int i = 0; i < subprotocolArray.length; i++) {
                subprotocolArray[i] = subprotocolArray[i].trim();
            }
            this.subprotocols = subprotocolArray;
        } else {
            this.subprotocols = EmptyArrays.EMPTY_STRINGS;
        }
        this.maxFramePayloadLength = maxFramePayloadLength;
    }

    /**
     * Returns the URL of the web socket
     */
    public String uri() {
        return uri;
    }

    /**
     * Returns the CSV of supported sub protocols
     */
    public Set<String> subprotocols() {
        Set<String> ret = new LinkedHashSet<String>();
        Collections.addAll(ret, subprotocols);
        return ret;
    }

    /**
     * Returns the version of the specification being supported
     */
    public WebSocketVersion version() {
        return version;
    }

    /**
     * Gets the maximum length for any frame's payload.
     *
     * @return The maximum length for a frame's payload
     */
    public int maxFramePayloadLength() {
        return maxFramePayloadLength;
    }

    /**
     * Performs the opening handshake. When call this method you <strong>MUST NOT</strong> retain the
     * {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *              Channel
     * @param req
     *              HTTP Request
     * @return future
     *              The {@link ChannelFuture} which is notified once the opening handshake completes
     * @deprecated use {@link #handshake(Channel, FullHttpRequest, Set)}
     */
    @Deprecated
    public ChannelFuture handshake(Channel channel, FullHttpRequest req) {
        return handshake(channel, req, null, channel.newPromise());
    }

    /**
     * Performs the opening handshake. When call this method you <strong>MUST NOT</strong> retain the
     * {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *              Channel
     * @param req
     *              HTTP Request
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpServerCodec} or {@link HttpRequestDecoder} and {@link HttpResponseEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentCompressor} may be present as well.
     * @return future
     *              The {@link ChannelFuture} which is notified once the opening handshake completes
     */
    public ChannelFuture handshake(Channel channel, FullHttpRequest req, Set<? extends ChannelHandler> handlers) {
        return handshake(channel, req, null, handlers, channel.newPromise());
    }

    /**
     * Performs the opening handshake
     *
     * When call this method you <strong>MUST NOT</strong> retain the {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *            Channel
     * @param req
     *            HTTP Request
     * @param responseHeaders
     *            Extra headers to add to the handshake response or {@code null} if no extra headers should be added
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is done
     * @return future
     *            the {@link ChannelFuture} which is notified when the opening handshake is done
     * @deprecated use {@link #handshake(Channel, FullHttpRequest, HttpHeaders, Set, ChannelPromise)}
     */
    @Deprecated
    public final ChannelFuture handshake(Channel channel, FullHttpRequest req,
                                         HttpHeaders responseHeaders, final ChannelPromise promise) {
        return handshake(channel, req, responseHeaders, newHandlersSet(channel.pipeline()), promise);
    }

    /**
     * Performs the opening handshake
     *
     * When call this method you <strong>MUST NOT</strong> retain the {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *            Channel
     * @param req
     *            HTTP Request
     * @param responseHeaders
     *            Extra headers to add to the handshake response or {@code null} if no extra headers should be added
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpServerCodec} or {@link HttpRequestDecoder} and {@link HttpResponseEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentCompressor} may be present as well.
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is done
     * @return future
     *            the {@link ChannelFuture} which is notified when the opening handshake is done
     */
    public final ChannelFuture handshake(Channel channel, FullHttpRequest req,
                                            HttpHeaders responseHeaders, Set<? extends ChannelHandler> handlers,
                                         final ChannelPromise promise) {
        if (!isValidSet(ObjectUtil.checkNotNull(handlers, "handlers"))) {
            promise.setFailure(new IllegalStateException("ChannelPipeline does not contain " +
                    "a HttpRequestDecoder or HttpServerCodec"));
            return promise;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} WebSocket version {} server handshake", channel, version());
        }

        FullHttpResponse response = newHandshakeResponse(req, responseHeaders);
        ChannelPipeline p = channel.pipeline();
        HttpObjectAggregator aggregator = handler(handlers, HttpObjectAggregator.class);
        if (aggregator != null) {
            p.remove(aggregator);
        }
        HttpContentCompressor compressor = handler(handlers, HttpContentCompressor.class);
        if (compressor != null) {
            p.remove(compressor);
        }

        ChannelHandler decoder = decoder(handlers, HttpRequestDecoder.class, HttpServerCodec.class);
        ChannelHandlerContext ctx = p.context(decoder);
        if (ctx == null) {
            promise.setFailure(
                    new IllegalStateException("No HttpRequestDecoder and no HttpServerCodec in the pipeline"));
            return promise;
        }

        final String encoderName;
        if (decoder instanceof HttpServerCodec) {
            p.addBefore(ctx.name(), "wsdecoder", newWebsocketDecoder());
            p.addBefore(ctx.name(), "wsencoder", newWebSocketEncoder());
            encoderName = ctx.name();
        } else {
            p.replace(ctx.name(), "wsdecoder", newWebsocketDecoder());

            encoderName = p.context(HttpResponseEncoder.class).name();
            p.addBefore(encoderName, "wsencoder", newWebSocketEncoder());
        }
        channel.writeAndFlush(response).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    ChannelPipeline p = future.channel().pipeline();
                    p.remove(encoderName);
                    promise.setSuccess();
                } else {
                    promise.setFailure(future.cause());
                }
            }
        });
        return promise;
    }

    /**
     * Performs the opening handshake. When call this method you <strong>MUST NOT</strong> retain the
     * {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *              Channel
     * @param req
     *              HTTP Request
     * @return future
     *              The {@link ChannelFuture} which is notified once the opening handshake completes
     * @deprecated use {@link #handshake(Channel, HttpRequest, Set)}
     */
    @Deprecated
    public ChannelFuture handshake(Channel channel, HttpRequest req) {
        return handshake(channel, req, null, channel.newPromise());
    }

    /**
     * Performs the opening handshake. When call this method you <strong>MUST NOT</strong> retain the
     * {@link FullHttpRequest} which is passed in.
     *
     * @param channel
     *              Channel
     * @param req
     *              HTTP Request
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpServerCodec} or {@link HttpRequestDecoder} and {@link HttpResponseEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentCompressor} may be present as well.
     * @return future
     *              The {@link ChannelFuture} which is notified once the opening handshake completes
     */
    public ChannelFuture handshake(Channel channel, HttpRequest req, Set<? extends ChannelHandler> handlers) {
        return handshake(channel, req, null, handlers, channel.newPromise());
    }

    /**
     * Performs the opening handshake
     *
     * When call this method you <strong>MUST NOT</strong> retain the {@link HttpRequest} which is passed in.
     *
     * @param channel
     *            Channel
     * @param req
     *            HTTP Request
     * @param responseHeaders
     *            Extra headers to add to the handshake response or {@code null} if no extra headers should be added
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is done
     * @return future
     *            the {@link ChannelFuture} which is notified when the opening handshake is done
     * @deprecated use {@link #handshake(Channel, HttpRequest, HttpHeaders, Set, ChannelPromise)}
     */
    @Deprecated
    public final ChannelFuture handshake(final Channel channel, HttpRequest req,
                                         final HttpHeaders responseHeaders, final ChannelPromise promise) {
        return handshake(channel, req, responseHeaders, newHandlersSet(channel.pipeline()), promise);
    }

    /**
     * Performs the opening handshake
     *
     * When call this method you <strong>MUST NOT</strong> retain the {@link HttpRequest} which is passed in.
     *
     * @param channel
     *            Channel
     * @param req
     *            HTTP Request
     * @param responseHeaders
     *            Extra headers to add to the handshake response or {@code null} if no extra headers should be added
     * @param handlers
     *            the HTTP specific {@link ChannelHandler}s to remove. This must contain either at least
     *            {@link HttpServerCodec} or {@link HttpRequestDecoder} and {@link HttpResponseEncoder}. Other handlers
     *            like {@link HttpObjectAggregator} and {@link HttpContentCompressor} may be present as well.
     * @param promise
     *            the {@link ChannelPromise} to be notified when the opening handshake is done
     * @return future
     *            the {@link ChannelFuture} which is notified when the opening handshake is done
     */
    public final ChannelFuture handshake(final Channel channel, HttpRequest req, final HttpHeaders responseHeaders,
                                         final Set<? extends ChannelHandler> handlers, final ChannelPromise promise) {
        if (req instanceof FullHttpRequest) {
            return handshake(channel, (FullHttpRequest) req, responseHeaders, handlers, promise);
        }
        if (!isValidSet(ObjectUtil.checkNotNull(handlers, "handlers"))) {
            promise.setFailure(new IllegalStateException("ChannelPipeline does not contain " +
                    "a HttpRequestDecoder or HttpServerCodec"));
            return promise;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} WebSocket version {} server handshake", channel, version());
        }
        ChannelPipeline p = channel.pipeline();
        ChannelHandler handler = decoder(handlers, HttpRequestDecoder.class, HttpServerCodec.class);
        ChannelHandlerContext ctx = p.context(handler);
        if (ctx == null) {
            promise.setFailure(
                    new IllegalStateException("No HttpDecoder and no HttpServerCodec in the pipeline"));
            return promise;
        }
        // Add aggregator and ensure we feed the HttpRequest so it is aggregated. A limit o 8192 should be more then
        // enough for the websockets handshake payload.
        //
        // TODO: Make handshake work without HttpObjectAggregator at all.
        String aggregatorName = "httpAggregator";
        p.addAfter(ctx.name(), aggregatorName, new HttpObjectAggregator(8192));
        p.addAfter(aggregatorName, "handshaker", new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
                // Remove ourself and do the actual handshake
                ctx.pipeline().remove(this);
                handshake(channel, msg, responseHeaders, handlers, promise);
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                // Remove ourself and fail the handshake promise.
                ctx.pipeline().remove(this);
                promise.tryFailure(cause);
                ctx.fireExceptionCaught(cause);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                // Fail promise if Channel was closed
                promise.tryFailure(CLOSED_CHANNEL_EXCEPTION);
                ctx.fireChannelInactive();
            }
        });
        try {
            ctx.fireChannelRead(ReferenceCountUtil.retain(req));
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    /**
     * Returns a new {@link FullHttpResponse) which will be used for as response to the handshake request.
     */
    protected abstract FullHttpResponse newHandshakeResponse(FullHttpRequest req,
                                         HttpHeaders responseHeaders);
    /**
     * Performs the closing handshake
     *
     * @param channel
     *            Channel
     * @param frame
     *            Closing Frame that was received
     */
    public ChannelFuture close(Channel channel, CloseWebSocketFrame frame) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return close(channel, frame, channel.newPromise());
    }

    /**
     * Performs the closing handshake
     *
     * @param channel
     *            Channel
     * @param frame
     *            Closing Frame that was received
     * @param promise
     *            the {@link ChannelPromise} to be notified when the closing handshake is done
     */
    public ChannelFuture close(Channel channel, CloseWebSocketFrame frame, ChannelPromise promise) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return channel.writeAndFlush(frame, promise).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Selects the first matching supported sub protocol
     *
     * @param requestedSubprotocols
     *            CSV of protocols to be supported. e.g. "chat, superchat"
     * @return First matching supported sub protocol. Null if not found.
     */
    protected String selectSubprotocol(String requestedSubprotocols) {
        if (requestedSubprotocols == null || subprotocols.length == 0) {
            return null;
        }

        String[] requestedSubprotocolArray = StringUtil.split(requestedSubprotocols, ',');
        for (String p: requestedSubprotocolArray) {
            String requestedSubprotocol = p.trim();

            for (String supportedSubprotocol: subprotocols) {
                if (SUB_PROTOCOL_WILDCARD.equals(supportedSubprotocol)
                        || requestedSubprotocol.equals(supportedSubprotocol)) {
                    selectedSubprotocol = requestedSubprotocol;
                    return requestedSubprotocol;
                }
            }
        }

        // No match found
        return null;
    }

    /**
     * Returns the selected subprotocol. Null if no subprotocol has been selected.
     * <p>
     * This is only available AFTER <tt>handshake()</tt> has been called.
     * </p>
     */
    public String selectedSubprotocol() {
        return selectedSubprotocol;
    }

    /**
     * Returns the decoder to use after handshake is complete.
     */
    protected abstract WebSocketFrameDecoder newWebsocketDecoder();

    /**
     * Returns the encoder to use after the handshake is complete.
     */
    protected abstract WebSocketFrameEncoder newWebSocketEncoder();

    private static Set<? extends ChannelHandler> newHandlersSet(ChannelPipeline pipeline) {
        Set<ChannelHandler> handlers = new HashSet<ChannelHandler>();
        addIfNotNull(handlers, pipeline, HttpRequestDecoder.class);
        addIfNotNull(handlers, pipeline, HttpResponseEncoder.class);
        addIfNotNull(handlers, pipeline, HttpServerCodec.class);
        addIfNotNull(handlers, pipeline, HttpObjectAggregator.class);
        addIfNotNull(handlers, pipeline, HttpContentCompressor.class);
        return handlers;
    }

    private static boolean isValidSet(Set<? extends ChannelHandler> handlers) {
        return handler(handlers, HttpServerCodec.class) != null
                || (handler(handlers, HttpResponseEncoder.class) != null
                && handler(handlers, HttpRequestDecoder.class) != null);
    }
}
