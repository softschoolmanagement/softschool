package com.softschool.backend.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Wraps a request so its JSON body can be read more than once.
 *
 * A servlet request body is normally a one-shot stream: whoever reads it
 * first leaves nothing for whoever reads it second. RateLimitFilter needs
 * to peek a field (e.g. "username") to apply per-account throttling on
 * login/register endpoints, but the controller still needs to read the
 * *full* body afterwards to bind @RequestBody. This wrapper reads the body
 * into memory exactly once, then hands out a brand-new stream/reader over
 * that copy every time something asks for it - so both the filter and the
 * controller see the complete, unmodified body.
 *
 * Only applied to the small set of authentication endpoints that need
 * per-account rate limiting (see RateLimitFilter) - never applied globally,
 * so large payloads elsewhere in the app (bulk student/finance saves,
 * base64 logo uploads) are never buffered here and existing functionality
 * is unaffected.
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        try (var is = request.getInputStream()) {
            this.cachedBody = is.readAllBytes();
        }
    }

    /** The full raw body, safe to parse without consuming anything downstream needs. */
    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Not needed: this app never reads the body asynchronously.
            }

            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
