package com.university.ums.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Stateless-style JSON API called from scripts/Postman; CSRF protection is meant
            // for browser form posts. Re-enable it if you add a browser front end.
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/error").permitAll()
                // The web UI itself is public; all data still sits behind /api/** rules below
                .requestMatchers("/", "/index.html", "/style.css", "/app.js", "/favicon.ico").permitAll()
                // RBAC: admins manage everything. A teacher / student may only read and update
                // their OWN record (GET/PUT /{id}); the controller checks the id is theirs.
                // Listing, creating and deleting are admin-only.
                .requestMatchers("/api/admins/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/teachers/*").hasAnyRole("ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/teachers/*").hasAnyRole("ADMIN", "TEACHER")
                .requestMatchers("/api/teachers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/students/*").hasAnyRole("ADMIN", "STUDENT")
                .requestMatchers(HttpMethod.PUT, "/api/students/*").hasAnyRole("ADMIN", "STUDENT")
                .requestMatchers("/api/students/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .httpBasic(withDefaults())   // handy for curl/Postman; session login is also supported
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setStatus(HttpServletResponse.SC_OK);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write("{\"message\":\"Logged out\"}");
                })
                .deleteCookies("JSESSIONID"))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    json(res, 401, "Unauthorized", "Authentication required"))
                .accessDeniedHandler((req, res, e) ->
                    json(res, 403, "Forbidden", "You do not have permission to access this resource")));
        return http.build();
    }

    private static void json(HttpServletResponse res, int status, String error, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"status\":" + status + ",\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}
