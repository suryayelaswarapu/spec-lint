package io.github.suryayelaswarapu.speclint.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security filter: strict response headers on everything, an optional bearer token for the
 * API, and a request body limit that also covers chunked uploads (no Content-Length).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BodyLimitFilter extends OncePerRequestFilter {
    static final String CSP = "default-src 'self'; script-src 'self' https://unpkg.com/swagger-ui-dist@5.17.14/; "
            + "style-src 'self' 'unsafe-inline' https://unpkg.com/swagger-ui-dist@5.17.14/; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final long maxBytes;
    private final String apiToken;

    public BodyLimitFilter(@Value("${speclint.max-bytes:2097152}") long maxBytes,
            @Value("${speclint.api-token:}") String apiToken) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException(
                    "SPECLINT_MAX_BYTES must be a positive whole number of bytes, got " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.apiToken = apiToken == null ? "" : apiToken;
    }

    /** Thrown while reading a body that exceeds the limit. */
    public static final class PayloadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        PayloadTooLargeException(long max) {
            super("Request body exceeds " + max + " bytes");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");

        boolean api = "POST".equals(request.getMethod())
                && request.getRequestURI().startsWith("/api/v1/");
        if (api && !apiToken.isEmpty()) {
            String supplied = request.getHeader("Authorization");
            byte[] expected = ("Bearer " + apiToken).getBytes(StandardCharsets.UTF_8);
            byte[] actual = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(expected, actual)) {
                response.setHeader("WWW-Authenticate", "Bearer");
                write(response, 401, "Unauthorized", "A valid bearer token is required");
                return;
            }
        }
        if (request.getContentLengthLong() > maxBytes) {
            write(response, 413, "Payload too large", "Request body exceeds " + maxBytes + " bytes");
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static void write(HttpServletResponse response, int status, String title,
            String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail + "\"}");
    }

    /** Wraps the request so reading more than {@code max} bytes fails. */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long count;

                private void add(long n) throws IOException {
                    count += Math.max(n, 0);
                    if (count > max) {
                        throw new PayloadTooLargeException(max);
                    }
                }

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    add(b < 0 ? 0 : 1);
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    add(n);
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
