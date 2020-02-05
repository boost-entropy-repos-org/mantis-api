package io.mantisrx.api.push;

import io.mantisrx.api.util.RetryUtils;
import io.mantisrx.api.util.Util;
import io.mantisrx.server.core.master.MasterDescription;
import io.mantisrx.server.master.client.MasterClientWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import lombok.extern.slf4j.Slf4j;
import mantis.io.reactivex.netty.RxNetty;
import mantis.io.reactivex.netty.channel.StringTransformer;
import mantis.io.reactivex.netty.pipeline.PipelineConfigurator;
import mantis.io.reactivex.netty.pipeline.PipelineConfigurators;
import mantis.io.reactivex.netty.protocol.http.client.HttpClient;
import mantis.io.reactivex.netty.protocol.http.client.HttpClientRequest;
import mantis.io.reactivex.netty.protocol.http.client.HttpClientResponse;
import mantis.io.reactivex.netty.protocol.http.client.HttpResponseHeaders;
import rx.Observable;
import rx.Subscription;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Http handler for the WebSocket/SSE paths.
 */
@Slf4j
public class MantisSSEHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ConnectionBroker connectionBroker;
    private final MasterClientWrapper masterClientWrapper;
    private final List<String> pushPrefixes;
    private Subscription subscription;

    private static final String SSE_SUFFIX = "\r\n\r\n";
    private static final String SSE_PREFIX = "data: ";

    public MantisSSEHandler(ConnectionBroker connectionBroker, MasterClientWrapper masterClientWrapper, List<String> pushPrefixes) {
        this.connectionBroker = connectionBroker;
        this.masterClientWrapper = masterClientWrapper;
        this.pushPrefixes = pushPrefixes;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (Util.startsWithAnyOf(request.uri(), pushPrefixes)
                && !isWebsocketUpgrade(request)) {

            if (HttpUtil.is100ContinueExpected(request)) {
                send100Contine(ctx);
            }

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK);
            HttpHeaders headers = response.headers();
            headers.add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            headers.add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Accept, Content-Type, Cache-Control");
            headers.set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            headers.set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate");
            headers.set(HttpHeaderNames.PRAGMA, HttpHeaderValues.NO_CACHE);
            headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(response);

            final String uri = request.uri();
            final PushConnectionDetails pcd =
                    isSubmitAndConnect(request)
                            ? new PushConnectionDetails(jobSubmit(request), PushConnectionDetails.TARGET_TYPE.CONNECT_BY_ID)
                            : new PushConnectionDetails(PushConnectionDetails.determineTarget(uri),
                            PushConnectionDetails.determineTargetType(uri));
        log.info("SSE Connecting for: {}", pcd);

        this.subscription = this.connectionBroker.connect(pcd)
                .doOnNext(event -> {
                    if (ctx.channel().isWritable()) {
                        ctx.writeAndFlush(Unpooled.copiedBuffer(SSE_PREFIX
                                        + event
                                        + SSE_SUFFIX,
                                StandardCharsets.UTF_8));
                    }
                })
                .subscribe();

    } else {
        ctx.fireChannelRead(request.retain());
    }
}

    private static void send100Contine(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.CONTINUE);
        ctx.writeAndFlush(response);
    }

    /**
     * TODO: This could be made a lot more robust with null checks and checking the "upgrade" header for "websocket" value.
     *       This check failing has caused websocket to break because it receives a 200 response code entering the SSE path.
     * @param request A HttpRequest representing the request
     * @return A boolean indicating wether or not this request is a websocket upgrade.
     */
    private boolean isWebsocketUpgrade(HttpRequest request) {
        return (request.headers().get("Connection") != null &&
                request.headers().get("Connection").toLowerCase().equals("upgrade"))
                || (request.headers().get(HttpHeaderNames.CONNECTION) != null &&
                request.headers().get(HttpHeaderNames.CONNECTION).toLowerCase().equals("upgrade"));
    }

    private boolean isSubmitAndConnect(HttpRequest request) {
        return request.method().equals(HttpMethod.POST) && request.uri().contains("jobsubmitandconnect");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (this.subscription != null && !this.subscription.isUnsubscribed()) {
            this.subscription.unsubscribe();
        }
        super.channelUnregistered(ctx);
    }

    public String jobSubmit(FullHttpRequest request) {
        final String API_JOB_SUBMIT_PATH = "/api/submit";

        String content = request.content().toString(Charset.forName("UTF-8"));
        return callPostOnMaster(masterClientWrapper.getMasterMonitor().getMasterObservable(), API_JOB_SUBMIT_PATH, content)
                .retryWhen(RetryUtils.getRetryFunc(log))
                .flatMap(masterResponse -> masterResponse.getByteBuf()
                        .take(1)
                        .map(byteBuf -> {
                            final String s = byteBuf.toString(Charset.forName("UTF-8"));
                            log.info("response: " + s);
                            return s;
                        }))
                .take(1)
                .toBlocking()
                .first();
    }

public static class MasterResponse {

    private final HttpResponseStatus status;
    private final Observable<ByteBuf> byteBuf;
    private final HttpResponseHeaders responseHeaders;

    public MasterResponse(HttpResponseStatus status, Observable<ByteBuf> byteBuf, HttpResponseHeaders responseHeaders) {
        this.status = status;
        this.byteBuf = byteBuf;
        this.responseHeaders = responseHeaders;
    }

    public HttpResponseStatus getStatus() {
        return status;
    }

    public Observable<ByteBuf> getByteBuf() {
        return byteBuf;
    }

    public HttpResponseHeaders getResponseHeaders() { return responseHeaders; }
}

    public static Observable<MasterResponse> callPostOnMaster(Observable<MasterDescription> masterObservable, String uri, String content) {
        PipelineConfigurator<HttpClientResponse<ByteBuf>, HttpClientRequest<String>> pipelineConfigurator
                = PipelineConfigurators.httpClientConfigurator();

        return masterObservable
                .filter(Objects::nonNull)
                .flatMap(masterDesc -> {
                    HttpClient<String, ByteBuf> client =
                            RxNetty.<String, ByteBuf>newHttpClientBuilder(masterDesc.getHostname(), masterDesc.getApiPort())
                                    .pipelineConfigurator(pipelineConfigurator)
                                    .build();
                    HttpClientRequest<String> request = HttpClientRequest.create(HttpMethod.POST, uri);
                    request = request.withHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON.toString());
                    request.withRawContent(content, StringTransformer.DEFAULT_INSTANCE);
                    return client.submit(request)
                            .map(response -> new MasterResponse(response.getStatus(), response.getContent(), response.getHeaders()));
                })
                .take(1);
    }
}
