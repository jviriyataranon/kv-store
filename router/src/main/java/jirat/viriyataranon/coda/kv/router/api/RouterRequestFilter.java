package jirat.viriyataranon.coda.kv.router.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jirat.viriyataranon.coda.kv.router.exception.UnexpectedException;
import jirat.viriyataranon.coda.kv.router.model.RouterRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class RouterRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        var context = new RouterRequestContext();
        context.setStartDateTime(Instant.now());
        context.setCorrelationId(request.getHeader("correlation-id"));
        context.setRequestId(request.getHeader("request-id"));

        try {
            request.setAttribute(RouterRequestContext.KEY, context);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            context.setException(new UnexpectedException(e));

            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            logEvent(request, response, context);
        }
    }

    private void logEvent(HttpServletRequest request, HttpServletResponse response, RouterRequestContext context) {
        var startDateTime = context.getStartDateTime();
        var endDateTime = Instant.now();

        log.atInfo()
                .addKeyValue("remoteAddr", request.getRemoteAddr())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("servletPath", request.getServletPath())

                .addKeyValue("httpStatus", response.getStatus())

                .addKeyValue("correlationId", context.getCorrelationId())
                .addKeyValue("requestId", context.getRequestId())
                .addKeyValue("operation", context.getOperation())
                .addKeyValue("key", context.getKey())
                .addKeyValue("registryStatus", context.getRegistryStatus())
                .addKeyValue("targetNodes", context.getTargetNodes())

                .addKeyValue("startDateTime", startDateTime)
                .addKeyValue("endDateTime", endDateTime)
                .addKeyValue("executionTime", Duration.between(startDateTime, endDateTime).toMillis())

                .setCause(context.getException())

                .log();
    }
}
