package com.yourorg.qbank.core.event;

import java.time.Instant;

public interface QBankEvent {
    Instant occurredAt();
}
