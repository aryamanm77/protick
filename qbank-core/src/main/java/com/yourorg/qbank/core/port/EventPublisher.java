package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.event.QBankEvent;

@FunctionalInterface
public interface EventPublisher {
    void publish(QBankEvent event);
}
