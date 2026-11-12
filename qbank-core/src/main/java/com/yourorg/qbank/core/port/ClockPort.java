package com.yourorg.qbank.core.port;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Authoritative server clock abstraction enabling deterministic testing and strict exam window control.
 */
public interface ClockPort {

    Instant now();

    default LocalDate today() {
        return now().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
