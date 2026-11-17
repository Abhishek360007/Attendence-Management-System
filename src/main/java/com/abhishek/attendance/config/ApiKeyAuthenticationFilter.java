package com.abhishek.attendance.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Machine access: a request carrying {@code X-API-Key} is authenticated as role API for that request only
 * (no session is created). A wrong key is rejected outright.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final AttendanceProperties properties;

    public ApiKeyAuthenticationFilter(AttendanceProperties properties) {
        this.properties = properties;
    }

    public static boolean hasApiKey(HttpServletRequest request) {
        return request.getHeader(HEADER) != null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !hasApiKey(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        if (properties.apiKeyEnabled() && MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), properties.apiKey().getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken("api-key", null,
                    AuthorityUtils.createAuthorityList("ROLE_API"));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"status":401,"title":"Unauthorized","detail":"Missing or invalid X-API-Key header"}""");
    }
}
