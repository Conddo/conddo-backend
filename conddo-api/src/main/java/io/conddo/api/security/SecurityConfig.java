package io.conddo.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.conddo.core.auth.AuthProperties;
import io.conddo.core.auth.JwtService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

/**
 * The application's security policy (PRD §6.2).
 *
 * <ul>
 *   <li>Stateless, JWT-bearer resource server — no server-side sessions.</li>
 *   <li>CSRF disabled: there is no session cookie to protect; the refresh cookie
 *       is {@code SameSite=Strict}.</li>
 *   <li>Public: tenant signup, the {@code /auth/*} endpoints, health.
 *       Everything else requires a valid access token; finer rules are enforced
 *       per-method with {@code @PreAuthorize} ({@link EnableMethodSecurity}).</li>
 *   <li>The {@code role} claim becomes a {@code ROLE_*} authority; the
 *       {@link JwtTenantContextFilter} then binds the token's tenant for RLS.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
                                                   SecurityErrorResponder errorResponder) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/tenants").permitAll()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(errorResponder)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder))
                .addFilterAfter(new JwtTenantContextFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Reuse the issuer's decoder so signing and verification share one key source. */
    @Bean
    public JwtDecoder jwtDecoder(JwtService jwtService) {
        return jwtService.decoder();
    }

    @Bean
    public SecurityErrorResponder securityErrorResponder(ObjectMapper objectMapper) {
        return new SecurityErrorResponder(objectMapper);
    }

    /** A single clock for the auth services; swapped for a fixed one in tests. */
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }

    /** Maps the single-valued {@code role} claim to a {@code ROLE_<role>} authority. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName(JwtService.CLAIM_ROLE);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
