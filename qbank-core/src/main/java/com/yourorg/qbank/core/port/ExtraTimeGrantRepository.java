package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.ExtraTimeGrant;

import java.util.List;

public interface ExtraTimeGrantRepository {
    ExtraTimeGrant save(ExtraTimeGrant grant);
    List<ExtraTimeGrant> findBySessionId(long sessionId);
}
