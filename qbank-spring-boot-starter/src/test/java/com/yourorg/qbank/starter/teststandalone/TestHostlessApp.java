package com.yourorg.qbank.starter.teststandalone;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
public class TestHostlessApp {
}
