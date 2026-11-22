package com.eventflow.config;

import com.eventflow.service.SlidingWindowRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Throttles brute-force and abuse-prone endpoints. It sits inside the Spring Security chain (before the login
 * filter), so failed logins are counted too. Limits are per client IP, plus per account for logins and per user for
 * the check-in and booking endpoints.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final long MINUTE = 60_000L;
    private static final Pattern CHECKIN = Pattern.compile("^/organizer/events/[^/]+/check-in/.*");
    private static final Pattern BOOKING = Pattern.compile("^/participant/events/[^/]+/(register|pay|waitlist)$");
    private static final Pattern COUPON = Pattern.compile("^/participant/events/[^/]+/coupon-preview$");

    private final SlidingWindowRateLimiter limiter;
    private final boolean enabled;

    public RateLimitFilter(SlidingWindowRateLimiter limiter, boolean enabled) {
        this.limiter = limiter;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (enabled) {
            long retry = check(request);
            if (retry > 0) {
                response.setHeader("Retry-After", String.valueOf(retry));
                response.sendError(429, "Too many requests. Please wait " + retry + " seconds and try again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** @return 0 when the request may proceed, otherwise seconds until the next allowed attempt */
    long check(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        String ip = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        boolean post = "POST".equals(method);

        if (post && path.equals("/login")) {
            long ipRetry = limiter.tryAcquire("login:ip:" + ip, 20, MINUTE, now);
            String email = request.getParameter("email");
            long accountRetry = email == null ? 0 : limiter.tryAcquire("login:acct:" + email.trim().toLowerCase(Locale.ROOT), 8, 15 * MINUTE, now);
            return Math.max(ipRetry, accountRetry);
        }
        if (post && path.equals("/register")) return limiter.tryAcquire("register:" + ip, 6, 10 * MINUTE, now);
        if (post && (path.startsWith("/forgot-password") || path.equals("/reset-password"))) {
            long ipRetry = limiter.tryAcquire("reset:ip:" + ip, 8, 15 * MINUTE, now);
            String email = request.getParameter("email");
            long accountRetry = email == null ? 0 : limiter.tryAcquire("reset:acct:" + email.trim().toLowerCase(Locale.ROOT), 3, 60 * MINUTE, now);
            return Math.max(ipRetry, accountRetry);
        }
        if (post && path.equals("/contact")) return limiter.tryAcquire("contact:" + ip, 5, 10 * MINUTE, now);
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
                ? auth.getName() : ip;
        if (CHECKIN.matcher(path).matches()) return limiter.tryAcquire("checkin:" + user, 240, MINUTE, now);
        if (COUPON.matcher(path).matches()) return limiter.tryAcquire("coupon:" + user, 30, MINUTE, now);
        if (post && BOOKING.matcher(path).matches()) return limiter.tryAcquire("booking:" + user, 20, MINUTE, now);
        return 0;
    }
}
