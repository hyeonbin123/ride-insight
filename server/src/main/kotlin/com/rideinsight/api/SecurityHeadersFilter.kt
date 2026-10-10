package com.rideinsight.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Response headers for an API that only ever returns JSON (OWASP ZAP scan 1, 2026-10-10:
 * "X-Content-Type-Options Header Missing"). The page itself is served by nginx or Vite, not here.
 */
@Component
class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        response.setHeader("Cache-Control", "no-store")
        chain.doFilter(request, response)
    }
}
