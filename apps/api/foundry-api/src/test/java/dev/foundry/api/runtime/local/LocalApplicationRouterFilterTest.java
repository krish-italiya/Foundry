package dev.foundry.api.runtime.local;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalApplicationRouterFilterTest {

    @Test
    void forwardsPathQueryHeadersAndBodyToActiveDeployment() throws Exception {
        UUID deploymentId = UUID.fromString("af831a2a-d706-433a-8f1d-6a523e04de6e");
        AtomicReference<String> seenRequest = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seenRequest.set(exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath() + "?"
                    + exchange.getRequestURI().getRawQuery() + " "
                    + exchange.getRequestHeaders().getFirst("X-Trace") + " "
                    + body);
            byte[] responseBody = "proxied response".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(201, responseBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(responseBody);
            }
        });
        upstream.start();

        try {
            LocalProcessRuntimeAdapter runtime = mock(LocalProcessRuntimeAdapter.class);
            when(runtime.internalApplicationUrl(deploymentId)).thenReturn(Optional.of(
                    URI.create("http://127.0.0.1:" + upstream.getAddress().getPort())));
            LocalApplicationRouterFilter filter = new LocalApplicationRouterFilter(runtime);

            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/resume/create");
            request.setServerName(deploymentId + ".localhost");
            request.setServerPort(8080);
            request.setScheme("http");
            request.setQueryString("template=classic");
            request.addHeader("X-Trace", "trace-123");
            request.setContent("resume-data".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getContentAsString()).isEqualTo("proxied response");
            assertThat(seenRequest.get()).isEqualTo(
                    "POST /resume/create?template=classic trace-123 resume-data");
            verifyNoInteractions(chain);
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void returnsNotFoundWhenLocalHostDoesNotMatchAnActiveDeployment() throws Exception {
        UUID deploymentId = UUID.fromString("af831a2a-d706-433a-8f1d-6a523e04de6e");
        LocalProcessRuntimeAdapter runtime = mock(LocalProcessRuntimeAdapter.class);
        when(runtime.internalApplicationUrl(deploymentId)).thenReturn(Optional.empty());
        LocalApplicationRouterFilter filter = new LocalApplicationRouterFilter(runtime);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setServerName(deploymentId + ".localhost");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verifyNoInteractions(chain);
    }

    @Test
    void leavesFoundryApiHostnamesAlone() throws Exception {
        LocalProcessRuntimeAdapter runtime = mock(LocalProcessRuntimeAdapter.class);
        LocalApplicationRouterFilter filter = new LocalApplicationRouterFilter(runtime);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system");
        request.setServerName("localhost");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        org.mockito.Mockito.verify(chain).doFilter(request, response);
        verifyNoInteractions(runtime);
    }
}
