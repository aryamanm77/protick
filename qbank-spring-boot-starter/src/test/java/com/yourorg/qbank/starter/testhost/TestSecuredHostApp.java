package com.yourorg.qbank.starter.testhost;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication
@EnableWebSecurity
public class TestSecuredHostApp {

    @Bean
    public UserDetailsService hostUsers() {
        return new InMemoryUserDetailsManager(
                User.withUsername("dept.head")
                        .password("{noop}host-pass-1")
                        .authorities("HOST_DEPT_HEAD")
                        .build(),
                User.withUsername("proctor.hall1")
                        .password("{noop}host-pass-2")
                        .authorities("HOST_PROCTOR", "HALL:HALL-204")
                        .build()
        );
    }

    @Bean
    public SecurityFilterChain hostFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/qbank/api/v1/auth/info", "/qbank/api/v1/student/**").permitAll()
                        .anyRequest().authenticated()
                )
                .build();
    }
}
