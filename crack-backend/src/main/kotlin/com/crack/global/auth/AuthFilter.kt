package com.crack.global.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class AuthFilter(
    private val authConfig: AuthConfig,
    private val authTokens: AuthTokens,
) : OncePerRequestFilter() {

    private val publicPaths = setOf("/api/auth/login", "/api/auth/verify")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val path = request.requestURI

        // Skip auth for non-API paths and public endpoints
        if (!path.startsWith("/api") || publicPaths.contains(path)) {
            filterChain.doFilter(request, response)
            return
        }

        // If no password configured, allow all
        if (authConfig.password.isBlank()) {
            filterChain.doFilter(request, response)
            return
        }

        val auth = request.getHeader("Authorization")
        val token = auth?.removePrefix("Bearer ")?.trim()

        if (!authTokens.isValid(token)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"Unauthorized"}""")
            return
        }

        filterChain.doFilter(request, response)
    }
}
