package com.yourorg.qbank.starter.config;

import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mounts offline static assets cleanly under {@code qbank.base-path}.
 */
public class QBankWebMvcConfigurer implements WebMvcConfigurer {

    private final QBankProperties props;

    public QBankWebMvcConfigurer(QBankProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base = props.normalizedBasePath();
        registry.addResourceHandler(base + "/**")
                .addResourceLocations("classpath:/META-INF/resources/qbank/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        String base = props.normalizedBasePath();
        if (!base.isEmpty()) {
            registry.addRedirectViewController(base, base + "/index.html");
            registry.addRedirectViewController(base + "/", base + "/index.html");
        } else {
            registry.addRedirectViewController("/", "/index.html");
        }
    }
}
