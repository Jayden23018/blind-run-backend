package com.example.demo.filter;

import com.example.demo.service.TokenBlacklistService;
import com.example.demo.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 过滤器 —— 每个请求进来时，先到这里检查有没有有效的 JWT token
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    public JwtFilter(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);

            Claims claims = jwtUtil.parseToken(token);
            if (claims != null) {
                Long userId = Long.parseLong(claims.getSubject());

                // 检查 token 黑名单：单 token 撤销（登出）或按用户整体撤销（注销/封禁）
                if (tokenBlacklistService.isTokenBlacklisted(claims.getId())
                        || tokenBlacklistService.isBlacklisted(userId, claims.getIssuedAt())) {
                    filterChain.doFilter(request, response);
                    return;
                }

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                String userRole = claims.get("role", String.class);
                if (userRole != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + userRole));
                }
                String csRole = claims.get("csRole", String.class);
                if (csRole != null) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_CS_" + csRole));
                }

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);

                if (csRole != null) {
                    authentication.setDetails(csRole);
                }

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }
}
