package com.serge.carrental.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.Customizer;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain appSecurity(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/**", "/", "/authorized")
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/cars/**", "/api/auth/**").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_admin:write")
                .requestMatchers("/api/bookings/**").hasAuthority("SCOPE_bookings:write")
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))
            .csrf(csrf -> csrf.disable())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    private Converter<org.springframework.security.oauth2.jwt.Jwt, ? extends AbstractAuthenticationToken> jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
            Collection<GrantedAuthority> authorities = scopes.convert(jwt);
            Set<GrantedAuthority> mapped = new HashSet<>();
            if (authorities != null) mapped.addAll(authorities);
            // Map resource roles if needed (e.g., "roles" claim) - optional
            return mapped;
        });
        return converter;
    }
}
