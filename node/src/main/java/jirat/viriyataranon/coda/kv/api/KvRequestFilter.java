package jirat.viriyataranon.coda.kv.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jirat.viriyataranon.coda.kv.exception.UnexpectedException;
import jirat.viriyataranon.coda.kv.model.KvRequestContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class KvRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        var context = new KvRequestContext();
        context.setStartDateTime(Instant.now());
        context.setCorrelationId(request.getHeader("correlation-id"));
        context.setRequestId(request.getHeader("request-id"));

        try {
            request.setAttribute(KvRequestContext.KEY, context);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            context.setException(new UnexpectedException(e));

            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            logEvent(request, response, context);
        }
    }

    private void logEvent(HttpServletRequest request, HttpServletResponse response, KvRequestContext context) {
        var startDateTime = context.getStartDateTime();
        var endDateTime = Instant.now();

        log.atInfo()
                .addKeyValue("remoteAddr", request.getRemoteAddr())
                .addKeyValue("remoteHost", request.getRemoteHost())
                .addKeyValue("remotePort", request.getRemotePort())
                .addKeyValue("scheme", request.getScheme())
                .addKeyValue("method", request.getMethod())
                .addKeyValue("servletPath", request.getServletPath())

                .addKeyValue("httpStatus", response.getStatus())

                .addKeyValue("correlationId", context.getCorrelationId())
                .addKeyValue("requestId", context.getRequestId())
                .addKeyValue("operation", context.getOperation())
                .addKeyValue("key", context.getKey())
                .addKeyValue("value", context.getValue())
                .addKeyValue("ifVersion", context.getIfVersion())
                .addKeyValue("returnValue", context.getReturnValue())
                .addKeyValue("returnVersion", context.getReturnVersion())
                .addKeyValue("startDateTime", startDateTime)
                .addKeyValue("endDateTime", endDateTime)

                .addKeyValue("executionTime", Duration.between(startDateTime, endDateTime).toMillis())

                .setCause(context.getException())

                .log();
    }
}
