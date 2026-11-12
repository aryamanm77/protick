package com.example.hostapp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class HostSecurityConfig {

    @Bean
    public PasswordEncoder hostPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService hostUserDetailsService(JdbcTemplate jdbc) {
        return username -> {
            List<HostUserRow> rows = jdbc.query(
                    "SELECT username, password_hash, authorities, enabled FROM host_users WHERE username = ?",
                    (rs, n) -> new HostUserRow(
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("authorities"),
                            rs.getBoolean("enabled")
                    ),
                    username
            );
            if (rows.isEmpty()) {
                throw new UsernameNotFoundException("Host user not found: " + username);
            }
            HostUserRow u = rows.get(0);
            List<GrantedAuthority> auths = Arrays.stream(u.authorities().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                    .toList();
            return User.withUsername(u.username())
                    .password(u.passwordHash())
                    .disabled(!u.enabled())
                    .authorities(auths)
                    .build();
        };
    }

    @Bean
    public SecurityFilterChain hostSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/exam-bank/index.html",
                                "/exam-bank/student.html",
                                "/exam-bank/*.js",
                                "/exam-bank/*.css",
                                "/exam-bank/api/v1/auth/info",
                                "/exam-bank/api/v1/student/**"
                        ).permitAll()
                        .requestMatchers("/host/api/runner/**").hasAuthority("INTERNAL_ENGINE")
                        .anyRequest().authenticated()
                )
                .build();
    }

    private record HostUserRow(String username, String passwordHash, String authorities, boolean enabled) {}
}
