package io.github.suryayelaswarapu.speclint.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Bearer token and body limit, configured the same way as in production (properties/env). */
@SpringBootTest(properties = {"speclint.api-token=s3cret", "speclint.max-bytes=300"})
@AutoConfigureMockMvc
class SecuredApiTest {
    @Autowired
    MockMvc mvc;

    MockHttpServletResponse lint(String auth, String body) throws Exception {
        var req = post("/api/v1/lint").contentType(MediaType.APPLICATION_JSON).content(body);
        if (auth != null) {
            req.header("Authorization", auth);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    @Test
    void bearerToken() throws Exception {
        String body = "{\"spec\": \"openapi: 3.1.0\\ninfo: {title: x, version: 1.0.0}\\npaths: {}\"}";
        MockHttpServletResponse denied = lint(null, body);
        assertThat(denied.getStatus()).isEqualTo(401);
        assertThat(denied.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(lint("Bearer nope", body).getStatus()).isEqualTo(401);
        assertThat(lint("Bearer s3cret", body).getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/rules")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get("/health")).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void bodyLimit() throws Exception {
        MockHttpServletResponse r = lint("Bearer s3cret",
                "{\"spec\": \"" + "x".repeat(400) + "\"}");
        assertThat(r.getStatus()).isEqualTo(413);
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void streamingLimitWithoutContentLength() throws IOException {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/api/v1/lint") {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(new byte[10]);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return in.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return in.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(jakarta.servlet.ReadListener l) {}
                };
            }
        };
        ServletInputStream limited = new BodyLimitFilter.LimitedRequest(raw, 5).getInputStream();
        assertThat(limited.isReady()).isTrue();
        assertThat(limited.isFinished()).isFalse();
        limited.setReadListener(null);
        byte[] buf = new byte[4];
        assertThat(limited.read(buf, 0, 4)).isEqualTo(4);
        assertThat(limited.read()).isZero();
        try {
            limited.read();
            throw new AssertionError("expected PayloadTooLargeException");
        } catch (BodyLimitFilter.PayloadTooLargeException expected) {
            assertThat(expected.getMessage()).contains("5 bytes");
        }
    }

    @Test
    void serveCommandStartsSpringBoot() {
        assertThat(io.github.suryayelaswarapu.speclint.CliAccess.serveArgs("serve", "--port",
                "9999", "--host", "0.0.0.0"))
                .containsExactly("--server.address=0.0.0.0", "--server.port=9999");
    }

    @Test
    void rejectsNonPositiveBodyLimit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BodyLimitFilter(0, ""))
                .hasMessageContaining("SPECLINT_MAX_BYTES");
    }
}
