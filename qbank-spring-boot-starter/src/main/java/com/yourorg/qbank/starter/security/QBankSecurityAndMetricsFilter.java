package com.yourorg.qbank.starter.security;

import com.yourorg.qbank.core.model.UserRole;
import com.yourorg.qbank.core.port.AuthPort;
import com.yourorg.qbank.core.service.CoreStandaloneAuthService;
import com.yourorg.qbank.starter.config.QBankProperties;
import com.yourorg.qbank.starter.service.QBankHealthMonitor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Enforces strict LAN security headers (CSP, nosniff, X-Frame-Options, no-store on APIs),
 * extracts student signed session JWTs and standalone staff bearer tokens cleanly within the
 * configured QBank base path, and records 5xx counts for ADMIN health monitoring.
 */
public class QBankSecurityAndMetricsFilter extends OncePerRequestFilter {

    private final QBankProperties props;
    private final StudentTokenCodec studentTokenCodec;
    private final CoreStandaloneAuthService standaloneAuthService;
    private final QBankHealthMonitor healthMonitor;

    public QBankSecurityAndMetricsFilter(
            QBankProperties props,
            StudentTokenCodec studentTokenCodec,
            CoreStandaloneAuthService standaloneAuthService,
            QBankHealthMonitor healthMonitor
    ) {
        this.props = props;
        this.studentTokenCodec = studentTokenCodec;
        this.standaloneAuthService = standaloneAuthService;
        this.healthMonitor = healthMonitor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String base = props.normalizedBasePath();
        if (base.isEmpty()) {
            return false;
        }
        String uri = request.getRequestURI();
        return !(uri.equals(base) || uri.startsWith(base + "/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        applySecurityHeaders(request, response);
        populateAuthContext(request);
        try {
            chain.doFilter(request, response);
        } finally {
            RequestAuthContext.clear();
            if (response.getStatus() >= 500) {
                healthMonitor.recordServerError();
            }
        }
    }

    private void applySecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", props.getSecurity().getCsp());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        String apiPrefix = props.normalizedBasePath() + "/api/";
        if (request.getRequestURI().startsWith(apiPrefix)) {
            response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
            response.setHeader("Pragma", "no-cache");
        }
    }

    private void populateAuthContext(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return;
        }
        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            return;
        }
        String studentApiPrefix = props.normalizedBasePath() + "/api/v1/student/";
        if (token.startsWith("qbs1.") && request.getRequestURI().startsWith(studentApiPrefix)) {
            try {
                studentTokenCodec.parseAndVerify(token).ifPresent(claims -> RequestAuthContext.set(
                        new AuthPort.AuthActor(
                                claims.rollNumber(),
                                claims.rollNumber(),
                                Set.of(UserRole.STUDENT),
                                claims.sessionId(),
                                claims.assignedVersion(),
                                null
                        )));
            } catch (RuntimeException ignored) {
                // Invalid token simply leaves RequestAuthContext empty -> endpoint rejects with 401
            }
            return;
        }

        if (standaloneAuthService != null) {
            standaloneAuthService.authenticateSessionToken(token).ifPresent(u -> RequestAuthContext.set(
                    new AuthPort.AuthActor(
                            u.username(),
                            u.displayName(),
                            Set.of(u.role())
                    )));
        }
    }
}
