package br.com.thalleseduardo.springemail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Order(1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.security.rate-limit.max-requests:20}") int maxRequests,
                            @Value("${app.security.rate-limit.window-seconds:60}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowMillis = windowSeconds * 1000L;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = request.getRemoteAddr();
        if (!tryConsume(key)) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Rate limit exceeded\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean tryConsume(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(now));
        synchronized (window) {
            if (now - window.startedAt >= windowMillis) {
                window.startedAt = now;
                window.count.set(0);
            }
            return window.count.incrementAndGet() <= maxRequests;
        }
    }

    private static final class Window {
        private volatile long startedAt;
        private final AtomicInteger count = new AtomicInteger(0);

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
