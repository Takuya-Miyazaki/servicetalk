/*
 * Copyright © 2018-2019, 2021 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.buffer.api.BufferAllocator;
import io.servicetalk.concurrent.api.AsyncContext;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpResponseFactory;
import io.servicetalk.http.api.HttpServerBuilder;
import io.servicetalk.http.api.HttpService;
import io.servicetalk.http.api.HttpServiceContext;
import io.servicetalk.transport.api.ServerContext;

import static io.servicetalk.concurrent.api.Single.defer;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.http.api.HttpExecutionStrategies.offloadNone;
import static java.lang.Thread.currentThread;

class HttpServiceAsyncContextTest extends AbstractAsyncHttpServiceAsyncContextTest {

    @Override
    protected ServerContext serverWithEmptyAsyncContextService(HttpServerBuilder serverBuilder,
                                                               boolean useImmediate) throws Exception {
        if (useImmediate) {
            serverBuilder.executionStrategy(offloadNone());
        }
        return serverBuilder.listenAndAwait(newEmptyAsyncContextService());
    }

    private HttpService newEmptyAsyncContextService() {
        return (ctx, request, factory) -> {
            if (!AsyncContext.isEmpty()) {
                BufferAllocator alloc = ctx.executionContext().bufferAllocator();
                return succeeded(factory.internalServerError()
                        .payloadBody(alloc.fromAscii(AsyncContext.context().toString())));
            }
            CharSequence requestId = request.headers().getAndRemove(REQUEST_ID_HEADER);
            if (requestId != null) {
                AsyncContext.put(K1, requestId);
                return succeeded(factory.ok().setHeader(REQUEST_ID_HEADER, requestId));
            } else {
                return succeeded(factory.badRequest());
            }
        };
    }

    @Override
    protected ServerContext serverWithService(HttpServerBuilder serverBuilder, boolean useImmediate,
                                              boolean asyncService) throws Exception {
        if (useImmediate) {
            serverBuilder.executionStrategy(offloadNone());
        }
        return serverBuilder.listenAndAwait(service(useImmediate, asyncService));
    }

    private HttpService service(boolean useImmediate, boolean asyncService) {
        return new HttpService() {
            @Override
            public Single<HttpResponse> handle(HttpServiceContext ctx,
                                               HttpRequest request,
                                               HttpResponseFactory responseFactory) {
                return asyncService ? defer(() -> doHandle(responseFactory).shareContextOnSubscribe()) :
                        doHandle(responseFactory);
            }

            private Single<HttpResponse> doHandle(HttpResponseFactory factory) {
                CharSequence requestId = AsyncContext.get(K1);
                if (useImmediate && !currentThread().getName().startsWith(IO_THREAD_PREFIX)) {
                    // verify that if we expect to be offloaded, that we actually are
                    return succeeded(factory.badGateway());
                }
                if (requestId != null) {
                    return succeeded(factory.ok().setHeader(REQUEST_ID_HEADER, requestId));
                } else {
                    return succeeded(factory.internalServerError().setHeader(REQUEST_ID_HEADER, "null"));
                }
            }
        };
    }
}