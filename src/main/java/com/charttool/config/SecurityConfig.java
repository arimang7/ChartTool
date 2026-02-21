package com.charttool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

/**
 * Configuration class for web security and authentication filters.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

        /**
         * Configures the main security filter chain for the app.
         *
         * @param http The HttpSecurity object to build security settings.
         * @return The configured SecurityFilterChain instance.
         * @throws Exception If configuration or building fails.
         */
        @Bean
        public SecurityFilterChain filterChain(final HttpSecurity http)
                        throws Exception {
                http.authorizeHttpRequests(auth -> auth
                                .anyRequest().permitAll());
                http.oauth2Login(oauth2 -> oauth2.defaultSuccessUrl(
                                "/", true));

                http.logout(logout -> {
                        logout.logoutRequestMatcher(
                                        new AntPathRequestMatcher("/logout"));
                        logout.logoutSuccessUrl("/");
                        logout.invalidateHttpSession(true);
                        logout.deleteCookies("JSESSIONID");
                });

                http.csrf(csrf -> csrf.disable());

                return http.build();
        }
}
