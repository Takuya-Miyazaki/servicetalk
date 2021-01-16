/*
 * Copyright © 2019 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.test.resources.DefaultTestCerts;
import io.servicetalk.transport.api.HostAndPort;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.IoThreadFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.HttpHeaderNames.HOST;
import static io.servicetalk.http.api.HttpResponseStatus.OK;
import static io.servicetalk.http.api.HttpSerializationProviders.textSerializer;
import static io.servicetalk.transport.netty.NettyIoExecutors.createIoExecutor;
import static io.servicetalk.transport.netty.internal.AddressUtils.localAddress;
import static io.servicetalk.transport.netty.internal.AddressUtils.serverHostAndPort;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

public class HttpsProxyTest {

    @Rule
    public final ServiceTalkTestTimeout timeout = new ServiceTalkTestTimeout();

    private final ProxyTunnel proxyTunnel = new ProxyTunnel();

    @Nullable
    private HostAndPort proxyAddress;
    @Nullable
    private IoExecutor serverIoExecutor;
    @Nullable
    private ServerContext serverContext;
    @Nullable
    private HostAndPort serverAddress;
    @Nullable
    private BlockingHttpClient client;

    @Before
    public void setUp() throws Exception {
        proxyAddress = proxyTunnel.startProxy();
        startServer();
        createClient();
    }

    @After
    public void tearDown() throws Exception {
        try {
            safeClose(client);
            safeClose(serverContext);
            safeClose(proxyTunnel);
        } finally {
            if (serverIoExecutor != null) {
                serverIoExecutor.closeAsync().toFuture().get();
            }
        }
    }

    static void safeClose(@Nullable AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void startServer() throws Exception {
        serverContext = HttpServers.forAddress(localAddress(0))
                .ioExecutor(serverIoExecutor = createIoExecutor(new IoThreadFactory("server-io-executor")))
                .secure().commit(DefaultTestCerts::loadServerPem, DefaultTestCerts::loadServerKey)
                .listenAndAwait((ctx, request, responseFactory) -> succeeded(responseFactory.ok()
                        .payloadBody("host: " + request.headers().get(HOST), textSerializer())));
        serverAddress = serverHostAndPort(serverContext);
    }

    public void createClient() {
        assert serverAddress != null && proxyAddress != null;
        client = HttpClients
                .forSingleAddressViaProxy(serverAddress, proxyAddress)
                .secure().disableHostnameVerification().trustManager(DefaultTestCerts::loadServerCAPem).commit()
                .buildBlocking();
    }

    @Test
    public void testRequest() throws Exception {
        assert client != null;
        final HttpResponse httpResponse = client.request(client.get("/path"));
        assertThat(httpResponse.status(), is(OK));
        assertThat(proxyTunnel.connectCount(), is(1));
        assertThat(httpResponse.payloadBody().toString(US_ASCII), is("host: " + serverAddress));
    }

    @Test
    public void testBadProxyResponse() {
        proxyTunnel.badResponseProxy();
        assert client != null;
        assertThrows(ProxyResponseException.class, () -> client.request(client.get("/path")));
    }
}