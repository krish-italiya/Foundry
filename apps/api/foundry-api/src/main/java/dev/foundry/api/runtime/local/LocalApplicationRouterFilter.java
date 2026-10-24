package dev.foundry.api.runtime.local;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Local-only reverse proxy for running deployments. Requests to
 * http://{deployment-id}.localhost:{api-port}/... are sent to that deployment's
 * private loopback process. Other hostnames are left to the normal API handler.
 */
@Component
@ConditionalOnProperty(
        prefix = "foundry.runtime.local",
        name = "enabled",
        havingValue = "true")
public class LocalApplicationRouterFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LocalApplicationRouterFilter.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-connection",
            "host", "content-length", "expect");

    private final LocalProcessRuntimeAdapter runtime;
    private final HttpClient httpClient;

    public LocalApplicationRouterFilter(LocalProcessRuntimeAdapter runtime) {
        this.runtime = runtime;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void doFilter(
            ServletRequest servletRequest,
            ServletResponse servletResponse,
            FilterChain chain) throws IOException, ServletException {
        if (!(servletRequest instanceof HttpServletRequest request)
                || !(servletResponse instanceof HttpServletResponse response)) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        UUID deploymentId = LocalApplicationAddress.deploymentIdFromHost(request.getServerName());
        if (deploymentId == null) {
            chain.doFilter(servletRequest, servletResponse);
            return;
        }

        URI internalBase = runtime.internalApplicationUrl(deploymentId).orElse(null);
        if (internalBase == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No active Foundry deployment matches this host.");
            return;
        }

        URI target;
        try {
            String query = request.getQueryString();
            String pathAndQuery = request.getRequestURI()
                    + (query == null || query.isBlank() ? "" : "?" + query);
            target = URI.create(internalBase.toString() + pathAndQuery);
        } catch (IllegalArgumentException invalidRequestTarget) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid application request path.");
            return;
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT);
            Enumeration<String> names = request.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Enumeration<String> values = request.getHeaders(name);
                while (values != null && values.hasMoreElements()) {
                    builder.header(name, values.nextElement());
                }
            }
            builder.header("X-Forwarded-Host", request.getHeader("Host") == null
                    ? request.getServerName() : request.getHeader("Host"));
            builder.header("X-Forwarded-Proto", request.getScheme());

            byte[] requestBody = request.getInputStream().readAllBytes();
            HttpRequest.BodyPublisher bodyPublisher = allowsRequestBody(request.getMethod())
                    ? HttpRequest.BodyPublishers.ofByteArray(requestBody)
                    : HttpRequest.BodyPublishers.noBody();
            HttpRequest outbound = builder.method(request.getMethod(), bodyPublisher).build();
            HttpResponse<byte[]> upstream = httpClient.send(
                    outbound, HttpResponse.BodyHandlers.ofByteArray());

            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> response.addHeader(name, value));
                }
            });

            if (!"HEAD".equalsIgnoreCase(request.getMethod())
                    && upstream.statusCode() != HttpServletResponse.SC_NO_CONTENT
                    && upstream.statusCode() != HttpServletResponse.SC_NOT_MODIFIED) {
                response.getOutputStream().write(upstream.body());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Local application routing was interrupted.");
        } catch (IOException | IllegalArgumentException failure) {
            log.warn("Could not route request to local deployment {}", deploymentId, failure);
            if (!response.isCommitted()) {
                response.reset();
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "The local application could not be reached.");
            }
        }
    }

    private static boolean allowsRequestBody(String method) {
        return !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)
                && !"TRACE".equalsIgnoreCase(method);
    }
}
