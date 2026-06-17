package com.example.demo.config;

import com.example.demo.filter.JwtFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * Spring Security 安全配置 —— 定义 "哪些接口需要登录才能访问"
 *
 * 【安全规则说明】
 * - /api/auth/**    → 所有人都可以访问（不需要登录），包括发送验证码和验证登录
 * - 其他所有接口    → 必须携带有效的 JWT token 才能访问
 *
 * 【为什么不需要 PasswordEncoder 了？】
 * 原来的配置有 BCryptPasswordEncoder（密码加密器），
 * 但现在用短信验证码登录，不再有密码字段，所以删掉了。
 *
 * 【什么是 CSRF？为什么禁用？】
 * CSRF（跨站请求伪造）是一种攻击方式。
 * 因为我们用 JWT token 认证（不是 Cookie），天然免疫 CSRF 攻击，所以可以禁用。
 *
 * 【SessionCreationPolicy.STATELESS 是什么意思？】
 * "无状态" —— 服务器不保存用户的登录状态（不使用 Session）。
 * 每次请求都通过 JWT token 来验证身份。
 * 这适合前后端分离的架构和 RESTful API。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ObjectMapper objectMapper;

    /** 允许的 CORS 来源域名列表，通过配置文件注入，开发环境默认允许 localhost */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    public SecurityConfig(JwtFilter jwtFilter, ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * 安全过滤器链 —— 配置安全规则
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 启用 CORS（允许前端跨域访问）
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration config = new CorsConfiguration();
                    config.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
                    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(List.of("*"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                // 禁用 CSRF（因为使用 JWT，不需要）
                .csrf(csrf -> csrf.disable())
                // 设置为无状态会话（不使用 Session）
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 配置URL权限规则
                .authorizeHttpRequests(auth -> auth
                        // 登出端点需要认证（必须在 permitAll 之前）
                        .requestMatchers("/api/auth/logout").authenticated()
                        .requestMatchers("/api/cs/auth/logout").authenticated()
                        // 认证相关接口（发送验证码、验证登录）允许匿名访问
                        .requestMatchers("/api/auth/**").permitAll()
                        // 客服登录接口允许匿名访问
                        .requestMatchers("/api/cs/auth/**").permitAll()
                        // Swagger / OpenAPI 文档允许匿名访问
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // WebSocket 握手端点允许匿名访问（认证在 HandshakeInterceptor 中处理）
                        .requestMatchers("/ws/volunteer/**").permitAll()
                        .requestMatchers("/ws/blind/**").permitAll()
                        // 角色限制：盲人端点需要 BLIND 角色
                        .requestMatchers("/api/blind/**").hasRole("BLIND")
                        // 角色限制：志愿者端点需要 VOLUNTEER 角色
                        .requestMatchers("/api/volunteer/**").hasRole("VOLUNTEER")
                        // 管理后台需要 CS_ADMIN 角色
                        .requestMatchers("/api/admin/**").hasRole("CS_ADMIN")
                        // 客服端点需要 CS 或 CS_ADMIN 角色
                        .requestMatchers("/api/cs/**").hasAnyRole("CS_CS", "CS_ADMIN")
                        // 紧急联系人需要 BLIND 角色
                        .requestMatchers("/api/users/*/emergency-contacts/**").hasRole("BLIND")
                        // 订单：创建/取消/继续等待 → BLIND
                        // ⚠️ 必须用 requestMatchers(HttpMethod, pattern) 重载：Spring Security 6 的
                        // MvcRequestMatcher 不解析字符串内的方法前缀，"POST /api/orders" 会被当成字面
                        // 路径而永不匹配（S3 评审缺陷的真正根因，原裸字符串规则全部失效）。
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("BLIND")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/cancel").hasAnyRole("BLIND", "VOLUNTEER")  // 取消：盲人取消订单 / 志愿者取消转 REMATCHING（cancelOrder 双角色分支）
                        .requestMatchers(HttpMethod.PUT, "/api/orders/*/keep-waiting").hasRole("BLIND")
                        // 订单：接单/拒绝/响应/完成/出发/到达/可接单列表 → VOLUNTEER
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/accept").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/reject").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/respond").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/finish").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/en-route").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/arrived").hasRole("VOLUNTEER")
                        .requestMatchers(HttpMethod.GET, "/api/orders/available").hasRole("VOLUNTEER")
                        // 订单：评价 → BLIND
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/review").hasRole("BLIND")
                        // 订单：查询类（GET）→ BLIND 或 VOLUNTEER；写操作已在上方逐条显式授权
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyRole("BLIND", "VOLUNTEER")
                        // 其他所有接口需要认证（必须携带有效 JWT token）
                        .anyRequest().authenticated()
                )
                // 未认证请求返回 401 JSON，无权限返回 403 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(Map.of("success", false, "code", 401, "message", "未认证")));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write(
                                    objectMapper.writeValueAsString(Map.of("success", false, "code", 403, "message", "无权访问")));
                        })
                )
                // 在 Spring Security 的过滤器链中插入我们的 JWT 过滤器
                // 在 UsernamePasswordAuthenticationFilter 之前执行
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
