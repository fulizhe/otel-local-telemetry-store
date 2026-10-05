package io.github.fulizhe.otelstore.demo.deps;

import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServiceDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * gRPC 那一跳：**真端口的 Netty server**（不用 protoc）。
 *
 * <p><b>为什么手搓 MethodDescriptor 而不生成代码</b>：这里要验的是仪表化，
 * 不是 protobuf。一个 {@code byte[]} 的 identity marshaller 加
 * {@code MethodDescriptor<byte[],byte[]>} 就够了，而且它换来的是<b>两侧都有 span</b> ——
 * 客户端产生 CLIENT、服务端产生 SERVER，是五跳里链最深的一条
 * （{@code tomcat → grpc CLIENT → grpc SERVER}）。
 *
 * <p><b>server 必须在真端口上监听</b>：进程内直调那个 Echo 方法不会产生 SERVER span，
 * 而那恰恰是这一跳要证明的东西之一。
 *
 * <p>端口 18900。
 */
public final class GrpcDependency implements DependencyProbe {

    public static final String KEY = DepsRegistry.GRPC;
    private static final String TITLE = "gRPC（进程内 Netty server）";

    private static final String SERVICE = "otelstore.Demo";
    private static final String METHOD_NAME = "Echo";

    /** byte[] 的 identity marshaller —— 不做任何序列化，两端都是同一串字节。 */
    private static final MethodDescriptor.Marshaller<byte[]> BYTES =
            new MethodDescriptor.Marshaller<byte[]>() {
                @Override
                public InputStream stream(final byte[] value) {
                    return new ByteArrayInputStream(value);
                }

                @Override
                public byte[] parse(final InputStream stream) {
                    try {
                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        final byte[] buf = new byte[256];
                        int n;
                        while ((n = stream.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        return out.toByteArray();
                    } catch (final IOException e) {
                        throw new IllegalStateException("读 gRPC 报文体失败", e);
                    }
                }
            };

    private static final MethodDescriptor<byte[], byte[]> METHOD =
            MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, METHOD_NAME))
                    .setRequestMarshaller(BYTES)
                    .setResponseMarshaller(BYTES)
                    .build();

    private final String host;
    private final int port;
    private final int callTimeoutMs;

    private volatile Server server;

    public GrpcDependency() {
        this("127.0.0.1", 18900, 3000);
    }

    public GrpcDependency(final String host, final int port, final int callTimeoutMs) {
        this.host = host;
        this.port = port;
        this.callTimeoutMs = callTimeoutMs;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public boolean embedded() {
        return true;
    }

    public int port() {
        return port;
    }

    /**
     * 起 server 并<b>真的调一次</b>。
     *
     * <p>探到"能往返"而不是"端口在听"：gRPC 的 server 起来之后还要注册服务、
     * 监听器才真的可用 —— 只探端口得到的 ready=true 会让第一个调用者白等。
     */
    @Override
    public DepStatus probe() {
        try {
            startServer();
            call("probe");
            return DepStatus.ready(KEY, TITLE, true,
                    "进程内 Netty server 就绪（起在 " + port + "，服务端与客户端都已收发一次）");
        } catch (final Exception e) {
            return DepStatus.notReady(KEY, TITLE, true,
                    "起不来（" + describe(e) + "）。降级：这一跳不会出现在链路图上，"
                            + "进程照常启动（ADR-7）");
        }
    }

    private synchronized void startServer() throws IOException {
        if (server != null) {
            return;
        }
        final ServerServiceDefinition service = ServerServiceDefinition
                .builder(new ServiceDescriptor(SERVICE, METHOD))
                .addMethod(METHOD, ServerCalls.asyncUnaryCall(
                        new ServerCalls.UnaryMethod<byte[], byte[]>() {
                            @Override
                            public void invoke(final byte[] request,
                                               final StreamObserver<byte[]> observer) {
                                // 原样回显：这一跳要验的是链路，不是业务逻辑
                                observer.onNext(request);
                                observer.onCompleted();
                            }
                        }))
                .build();
        final Server s = NettyServerBuilder.forPort(port)
                .addService(service)
                .build()
                .start();
        server = s;
    }

    /** 一次真实的 unary 调用。CLIENT span 由这一句产生，SERVER span 由服务端那个 Echo 产生。 */
    public String call(final String text) {
        final io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder
                .forAddress(host, port)
                // 明文：靶子里没有 TLS，而它会让"连不上"变成另一个故事
                .usePlaintext()
                .build();
        try {
            final byte[] resp = ClientCalls.blockingUnaryCall(
                    channel, METHOD, io.grpc.CallOptions.DEFAULT,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(resp, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            // shutdown 而不是 shutdownNow 之后 await：等它真的关掉，
            // 否则反复调用会攒下一堆 channel（靶子跑得久，泄漏会显现出来）
            channel.shutdown();
            try {
                channel.awaitTermination(callTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 关掉 server。进程退出时不必调 —— 内嵌进程随宿主一起走。 */
    synchronized void stop() {
        final Server s = server;
        server = null;
        if (s != null) {
            try {
                s.shutdown();
                s.awaitTermination(callTimeoutMs, TimeUnit.MILLISECONDS);
                s.shutdownNow();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final RuntimeException e) {
                // 关不掉不影响正确性：进程要退了，端口跟着释放
            }
        }
    }

    private static String describe(final Throwable t) {
        final StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append(" — ").append(t.getMessage());
        }
        final StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 3; i++) {
            sb.append(" ← ").append(st[i]);
        }
        return sb.toString();
    }
}