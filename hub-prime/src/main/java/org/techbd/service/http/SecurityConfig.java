package org.techbd.service.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.ForwardedHeaderFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@Profile("!localopen")
public class SecurityConfig {

    @Autowired(required = false)
    private FusionAuthUserAuthorizationFilter fusionAuthAuthorizationFilter;

    @Autowired(required = false)
    private GitHubUserAuthorizationFilter gitHubUserAuthorizationFilter;

    @Value("${TECHBD_HUB_PRIME_FHIR_API_BASE_URL:#{null}}")
    private String apiUrl;

    @Value("${TECHBD_HUB_PRIME_FHIR_UI_BASE_URL:#{null}}")
    private String uiUrl;

    @Value("${ORG_TECHBD_SERVICE_HTTP_FUSIONAUTH_BASE_URL}")
    private String fusionAuthBaseUrl;

    @Value("${SPRING_SECURITY_OAUTH2_FUSIONAUTH_CLIENT_ID}")
    private String clientId;

    @Value("${SPRING_SECURITY_OAUTH2_LOGOUT_REDIRECT_URI}")
    private String logoutRedirectUrl;

    @Value("${AUTH_PROVIDER:github}")
    private String authProvider;

    @Value("${TECHBD_ALLOWED_ORIGINS:}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain statelessSecurityFilterChain(final HttpSecurity http) throws Exception {
        // Stateless configuration for bundle endpoints
        http
                .securityMatcher(Constant.STATELESS_API_URLS)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()) // Allow all requests
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // Stateless
                .csrf(AbstractHttpConfigurer::disable); // Disable CSRF for stateless APIs

        return http.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(final HttpSecurity http) throws Exception {
        // allow authentication for security
        // and turn off CSRF to allow POST methods
        http
                .authorizeHttpRequests(
                        authorize -> authorize
                                .requestMatchers(Constant.UNAUTHENTICATED_URLS)
                                .permitAll()
                                .requestMatchers("/fusionauth/webhook").permitAll()
                                .anyRequest().authenticated())
                .oauth2Login(
                        oauth2Login -> oauth2Login
                                .successHandler(oAuth2LoginSuccessHandler())
                                .defaultSuccessUrl(Constant.HOME_PAGE_URL)
                                .loginPage(Constant.LOGIN_PAGE_URL))
                .logout(logout -> logout
                .deleteCookies(Constant.SESSIONID_COOKIE) // clear JSESSIONID (or your custom session cookie)
                .invalidateHttpSession(true) // kill server-side session
                .clearAuthentication(true)
                .logoutSuccessHandler(customLogoutSuccessHandler())
                .permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessionManagement -> {
                    // Dynamically adjust session timeout behavior based on the authentication provider
                    if ("github".equalsIgnoreCase(authProvider)) {
                        sessionManagement
                                .invalidSessionUrl(Constant.SESSION_TIMEOUT_URL)
                                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);
                    } else if ("fusionauth".equalsIgnoreCase(authProvider)) {
                        sessionManagement
                                .invalidSessionUrl(fusionAuthLogoutUUrl()) // Use custom logout handler for FusionAuth
                                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED);
                    }
                });
        if (fusionAuthAuthorizationFilter != null) {
            http.addFilterAfter(fusionAuthAuthorizationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        if (gitHubUserAuthorizationFilter != null) {
            http.addFilterAfter(gitHubUserAuthorizationFilter, UsernamePasswordAuthenticationFilter.class);
        }
        // allow us to show our own content in IFRAMEs (e.g. Swagger, etc.)
        http.headers(headers -> {
            headers.frameOptions(frameOptions -> frameOptions.sameOrigin());
            headers.httpStrictTransportSecurity(
                    hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(Constant.HSTS_MAX_AGE)); // Enable HSTS
        });
        return http.build();
    }

    @Bean
    public CorsFilter corsFilter() {
        // Strict origin whitelist — never reflect arbitrary user-supplied origins.
        // Origins are derived from the configured API/UI base URLs and TECHBD_ALLOWED_ORIGINS.
        // Note: TECHBD_ALLOWED_HOSTS is a separate setting used only for Host-header
        // validation in InteractionsFilter; do not conflate the two.
        List<String> allowedOriginsList = new ArrayList<>();
        if (apiUrl != null && !apiUrl.isBlank()) {
            allowedOriginsList.add(apiUrl.stripTrailing().replaceAll("/+$", ""));
        }
        if (uiUrl != null && !uiUrl.isBlank()) {
            allowedOriginsList.add(uiUrl.stripTrailing().replaceAll("/+$", ""));
        }
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                String trimmed = origin.strip();
                if (!trimmed.isEmpty()) {
                    // Browser Origin headers always include a scheme, so entries should too.
                    // A bare hostname is assumed https:// — it will NOT match an http:// origin
                    // (e.g. local dev), so prefer full origin URLs in TECHBD_ALLOWED_ORIGINS.
                    String normalized = trimmed.startsWith("http") ? trimmed : "https://" + trimmed;
                    allowedOriginsList.add(normalized.replaceAll("/+$", ""));
                }
            }
        }

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOriginsList.isEmpty() ? null : allowedOriginsList);
        config.setAllowedMethods(Constant.CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(Constant.CORS_ALLOWED_HEADERS);
        // Expose headers for session time-out redirection at the UI side (AGGrid etc)
        config.setExposedHeaders(Constant.CORS_EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }

    @Bean
    public AuthenticationSuccessHandler oAuth2LoginSuccessHandler() {
        return new OAuth2LoginSuccessHandler();
    }

    private static class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

        private final RequestCache requestCache = new HttpSessionRequestCache();

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request,
                HttpServletResponse response, Authentication authentication)
                throws IOException, jakarta.servlet.ServletException {
            final var savedRequest = requestCache.getRequest(request, response);

            if (savedRequest == null) {
                response.sendRedirect(Constant.HOME_PAGE_URL);
                return;
            }

            final var targetUrl = savedRequest.getRedirectUrl();
            response.sendRedirect(targetUrl);
        }
    }

    @Bean
    public LogoutSuccessHandler customLogoutSuccessHandler() {
        return (request, response, authentication) -> {
            if (authentication != null) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
            }
            response.sendRedirect(fusionAuthLogoutUUrl());
        };
    }

    private String fusionAuthLogoutUUrl() {
        return fusionAuthBaseUrl + "/oauth2/logout"
                + "?client_id=" + clientId
                + "&post_logout_redirect_uri=" + logoutRedirectUrl;
    }

    /**
     * Register RolePermissionInterceptor for all MVC requests.
     */
    // @Bean
    // public WebMvcConfigurer mvcConfigurer() {
    //     return new WebMvcConfigurer() {
    //         @Override
    //         public void addInterceptors(InterceptorRegistry registry) {
    //             registry.addInterceptor(rolePermissionInterceptor)
    //                     .addPathPatterns("/**")
    //                     .excludePathPatterns(Constant.INTERCEPTOR_EXCLUDED_URLS);
    //         }
    //     };
    // }
}
