package com.pol.rag.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility to access the current authenticated user from the security context.
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * Get the current authenticated userId, or null if unauthenticated.
     */
    public static Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return token.getUserId();
        }
        return null;
    }

    /**
     * Get the current authenticated username, or null if unauthenticated.
     */
    public static String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return (String) token.getPrincipal();
        }
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return null;
    }
}
