package com.yourorg.qbank.persistence.adapter;

import com.yourorg.qbank.core.port.ClockPort;

import java.time.Instant;

public class SystemClockAdapter implements ClockPort {
    @Override
    public Instant now() {
        return Instant.now();
    }
}
