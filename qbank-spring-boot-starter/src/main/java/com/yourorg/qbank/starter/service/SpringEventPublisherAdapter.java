package com.yourorg.qbank.starter.service;

import com.yourorg.qbank.core.event.QBankEvent;
import com.yourorg.qbank.core.port.EventPublisher;
import org.springframework.context.ApplicationEventPublisher;

public class SpringEventPublisherAdapter implements EventPublisher {

    private final ApplicationEventPublisher springPublisher;

    public SpringEventPublisherAdapter(ApplicationEventPublisher springPublisher) {
        this.springPublisher = springPublisher;
    }

    @Override
    public void publish(QBankEvent event) {
        if (event != null) {
            springPublisher.publishEvent(event);
        }
    }
}
