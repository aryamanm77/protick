package com.yourorg.qbank.core;

import com.yourorg.qbank.core.model.AuditLogEntry;
import com.yourorg.qbank.core.model.AuditVerificationResult;
import com.yourorg.qbank.core.security.AuditChainCalculator;
import com.yourorg.qbank.core.security.FormulaSanitizer;
import com.yourorg.qbank.core.testkit.InMemoryQBankFixture;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditAndFormulaSecurityTest {

    @Test
    void hashChainDetectsPayloadTamperAndGapWhileToleratingSubMillisecondPrecisionLoss() {
        var crypto = new InMemoryQBankFixture.RealAesCryptoPort();
        Instant t1 = Instant.parse("2026-11-11T09:00:00.123999Z");
        Instant t1Truncated = Instant.parse("2026-11-11T09:00:00.123000Z");

        String h1 = AuditChainCalculator.computeEntryHash(
                crypto, 1L, t1, "LOGIN_SUCCESS", "admin", "ADMIN", "USER", "1", "{}", AuditChainCalculator.GENESIS_PREV_HASH);
        String h2 = AuditChainCalculator.computeEntryHash(
                crypto, 2L, t1.plusSeconds(5), "SUBJECT_CREATED", "admin", "ADMIN", "SUBJECT", "CS101", "{}", h1);

        List<AuditLogEntry> chain = new ArrayList<>(List.of(
                new AuditLogEntry(1L, 1L, "LOGIN_SUCCESS", "admin", "ADMIN", "USER", "1", "{}", t1Truncated,
                        AuditChainCalculator.GENESIS_PREV_HASH, h1),
                new AuditLogEntry(2L, 2L, "SUBJECT_CREATED", "admin", "ADMIN", "SUBJECT", "CS101", "{}", t1.plusSeconds(5),
                        h1, h2)
        ));

        AuditVerificationResult ok = AuditChainCalculator.verify(crypto, chain);
        assertTrue(ok.intact());
        assertEquals(2L, ok.totalChecked());

        // Tamper entry 1 details
        chain.set(0, new AuditLogEntry(1L, 1L, "LOGIN_SUCCESS", "hacker", "ADMIN", "USER", "1", "{}", t1Truncated,
                AuditChainCalculator.GENESIS_PREV_HASH, h1));
        AuditVerificationResult tampered = AuditChainCalculator.verify(crypto, chain);
        assertFalse(tampered.intact());
        assertEquals(1L, tampered.firstBrokenSeqNo());
    }

    @Test
    void formulaSanitizerNeutralizesCsvAndSpreadsheetInjectionPayloads() {
        assertEquals("'=CMD|'/C calc'!A0", FormulaSanitizer.neutralizeSpreadsheetCell("=CMD|'/C calc'!A0"));
        assertEquals("'+1+1", FormulaSanitizer.neutralizeSpreadsheetCell("+1+1"));
        assertEquals("'-2+3", FormulaSanitizer.neutralizeSpreadsheetCell("-2+3"));
        assertEquals("'@SUM(1+1)", FormulaSanitizer.neutralizeSpreadsheetCell("@SUM(1+1)"));
        assertEquals("' =1+1", FormulaSanitizer.neutralizeSpreadsheetCell(" =1+1"));
        assertEquals("CS26-001", FormulaSanitizer.neutralizeSpreadsheetCell("CS26-001"));
        assertEquals("\"'=A1,B1\"", FormulaSanitizer.escapeCsvField("=A1,B1"));
    }
}
