package com.serge.carrental.config.logging;

import io.micrometer.common.util.StringUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Logs every HTTP request/response with minimal PII,
 * including duration, status, and basic caller info.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(HttpLoggingFilter.class);
    private static final int MAX_LOG_BYTES = 4096; // trim large payloads
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        // Wrap to ensure status and body copying is safe (we don't log bodies here).
        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper resp = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(req, resp);
        } catch (Exception e){
            log.error("Error in logging processor", e);
            throw e;
        } finally {
            try {
                long durationMs = System.currentTimeMillis() - start;
                String method = req.getMethod();
                String uri = req.getRequestURI();
                String qs = req.getQueryString();
                int status = resp.getStatus();
                String user = request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "-";
                String ip = Optional.ofNullable(request.getHeader("X-Forwarded-For"))
                        .orElseGet(request::getRemoteAddr);
                String ua = Optional.ofNullable(request.getHeader("User-Agent")).orElse("-");
                String idem = Optional.ofNullable(request.getHeader("Idempotency-Key")).orElse("-");
                String baseUrl = Optional.ofNullable(request.getHeader("X-Base-Url")).orElse("-");

                // Keep one concise, structured log line per request.
                log.info("http_request method={} path={} query={} status={} duration_ms={} user={} ip={} ua=\"{}\" idem_key={} base_url={}",
                        method,
                        uri,
                        qs == null ? "-" : qs,
                        status,
                        durationMs,
                        user,
                        ip,
                        ua.replace('"', ' '),
                        idem,
                        baseUrl);

                if (log.isDebugEnabled()) {

                    String reqCt = Optional.ofNullable(req.getContentType()).orElse("");
                    String respCt = Optional.ofNullable(resp.getContentType()).orElse("");

                    if (isTextual(reqCt)) {
                        String body = toDisplayString(req.getContentAsByteArray(),
                                charsetOrUtf8(req.getCharacterEncoding()));
                        if (!body.isEmpty()) {
                            log.debug("http.request.body {}: {}", reqCt, body);
                        }
                    }

                    if (isTextual(respCt)) {
                        String body = toDisplayString(resp.getContentAsByteArray(),
                                charsetOrUtf8(resp.getCharacterEncoding()));
                        if (!body.isEmpty()) {
                            log.debug("http.response.body {}: {}", respCt, body);
                        }
                    }
                }
                // Important: write cached body back to the real response
                resp.copyBodyToResponse();
            } catch (Exception e){
                log.error("Error in logging processor", e);
                throw e;
            }
        }
    }

    private static Charset charsetOrUtf8(String enc) {
        try {
            return enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        } catch (Exception e) {
            log.warn("Exception in reading encoding", e);
            return StandardCharsets.UTF_8;
        }
    }

    private static String toDisplayString(byte[] bytes, Charset cs) {
        if (bytes == null || bytes.length == 0) return "";
        int len = Math.min(bytes.length, MAX_LOG_BYTES);
        String s = new String(bytes, 0, len, cs);
        // sanitize: single-line, collapse whitespace
        s = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (bytes.length > MAX_LOG_BYTES) s += "…(truncated)";
        return s;
    }
    private static boolean isTextual(String contentType) {
        if (StringUtils.isNotBlank(contentType)) {

            MediaType mt = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.includes(mt)
                    || MediaType.APPLICATION_XML.includes(mt)
                    || MediaType.TEXT_PLAIN.includes(mt)
                    || MediaType.TEXT_HTML.includes(mt)
                    || MediaType.TEXT_XML.includes(mt)
                    || MediaType.valueOf("application/x-www-form-urlencoded").includes(mt);
        } else {
            return false;
        }
    }
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        // Avoid noisy health or static paths if any are added later.
        // Keep logging everything by default for traceability.
        return false;
    }
}
