package com.charttool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()) // 모든 요청 허용 (Guest access)
                .oauth2Login(oauth2 -> oauth2
                        .defaultSuccessUrl("/", true)) // 로그인 성공 시 홈으로 이동
                .logout(logout -> logout
                        .logoutRequestMatcher(
                                new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/logout")) // GET
                                                                                                                    // /logout
                                                                                                                    // 허용
                        .logoutSuccessUrl("/") // 로그아웃 성공 시 홈으로 이동
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}