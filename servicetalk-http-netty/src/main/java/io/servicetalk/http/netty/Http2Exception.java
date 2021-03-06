/*
 * Copyright © 2021 Apple Inc. and the ServiceTalk project authors
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

import io.servicetalk.transport.api.RetryableException;

import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2NoMoreStreamIdsException;
import io.netty.handler.codec.http2.Http2ResetFrame;

import java.io.IOException;

import static io.netty.handler.codec.http2.Http2Error.REFUSED_STREAM;

/**
 * An exception that indicates <a href="https://tools.ietf.org/html/rfc7540#section-5.4">HTTP/2 error</a>.
 */
class Http2Exception extends IOException {
    private static final long serialVersionUID = 745695232431963628L;

    Http2Exception(final String message) {
        super(message);
    }

    Http2Exception(final Throwable cause) {
        super(cause);
    }

    static Throwable wrapIfNecessary(final Throwable cause) {
        if (cause instanceof io.netty.handler.codec.http2.Http2Exception) {
            final io.netty.handler.codec.http2.Http2Exception h2Cause =
                    (io.netty.handler.codec.http2.Http2Exception) cause;
            return isRetryable(h2Cause) ? new RetryableStacklessHttp2Exception(h2Cause) :
                    new StacklessHttp2Exception(h2Cause);
        }
        if (cause instanceof io.netty.handler.codec.http2.Http2FrameStreamException) {
            return new StacklessHttp2Exception((io.netty.handler.codec.http2.Http2FrameStreamException) cause);
        }
        return cause;
    }

    /**
     * Checks if an {@link io.netty.handler.codec.http2.Http2Exception} is retryable on a different h2 parent
     * connection.
     *
     * @param cause {@link io.netty.handler.codec.http2.Http2Exception} for inspection
     * @return {@code true} if {@link io.netty.handler.codec.http2.Http2Exception} is retryable on a different h2
     * parent connection.
     */
    private static boolean isRetryable(final io.netty.handler.codec.http2.Http2Exception cause) {
        // The first check captures cases like:
        //  - Cannot create stream %d greater than Last-Stream-ID %d from GOAWAY.
        //  - Stream IDs are exhausted for this endpoint.
        //  - Maximum active streams violated for this endpoint.
        //  - Http2ChannelClosedException
        return cause.error() == REFUSED_STREAM
                // The  second check captures "No more streams can be created on this connection":
                || cause instanceof Http2NoMoreStreamIdsException;
    }

    private static final class StacklessHttp2Exception extends Http2Exception {
        private static final long serialVersionUID = 7794465950455688900L;

        StacklessHttp2Exception(io.netty.handler.codec.http2.Http2Exception cause) {
            super(cause);
        }

        StacklessHttp2Exception(io.netty.handler.codec.http2.Http2FrameStreamException cause) {
            super(cause);
        }

        @Override
        public Throwable fillInStackTrace() {
            // This is a wrapping exception class that always has an original cause and does not require stack trace.
            return this;
        }
    }

    private static final class RetryableStacklessHttp2Exception extends Http2Exception implements RetryableException {
        private static final long serialVersionUID = -5874594718640129904L;

        RetryableStacklessHttp2Exception(io.netty.handler.codec.http2.Http2Exception cause) {
            super(cause);
        }

        @Override
        public Throwable fillInStackTrace() {
            // This is a wrapping exception class that always has an original cause and does not require stack trace.
            return this;
        }
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1.4">REFUSED_STREAM</a> is always retryable.
     */
    private static final class H2StreamRefusedException extends H2StreamResetException implements RetryableException {
        private static final long serialVersionUID = 615309480184187428L;

        H2StreamRefusedException(String message) {
            super(message);
        }
    }

    static class H2StreamResetException extends Http2Exception {
        private static final long serialVersionUID = -2000223857660046560L;

        H2StreamResetException(String message) {
            super(message);
        }
    }

    static H2StreamResetException newStreamResetException(final Http2ResetFrame resetFrame) {
        final Http2FrameStream stream = resetFrame.stream();
        assert stream != null;
        if (resetFrame.errorCode() == REFUSED_STREAM.code()) {
            return new H2StreamRefusedException("RST_STREAM received for streamId=" + stream.id() + ", stream refused");
        } else {
            return new H2StreamResetException("RST_STREAM received for streamId=" + stream.id() + " with error code: " +
                    resetFrame.errorCode());
        }
    }
}
