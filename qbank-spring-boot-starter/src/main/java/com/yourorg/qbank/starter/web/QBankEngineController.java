package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.exception.ForbiddenException;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.FrozenPaperMeta;
import com.yourorg.qbank.core.model.FrozenVersionView;
import com.yourorg.qbank.core.port.CryptoPort;
import com.yourorg.qbank.core.port.FrozenPaperProvider;
import com.yourorg.qbank.starter.config.QBankProperties;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Machine-to-machine HTTP mirror of {@link FrozenPaperProvider}.
 * Protected strictly by constant-time configured service token; NEVER accessible to staff browsers or students.
 */
@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/engine/sessions")
public class QBankEngineController {

    private final QBankProperties props;
    private final CryptoPort cryptoPort;
    private final FrozenPaperProvider frozenPaperProvider;

    public QBankEngineController(
            QBankProperties props,
            CryptoPort cryptoPort,
            FrozenPaperProvider frozenPaperProvider
    ) {
        this.props = props;
        this.cryptoPort = cryptoPort;
        this.frozenPaperProvider = frozenPaperProvider;
    }

    @GetMapping("/{sessionId}")
    public FrozenPaperMeta getMeta(
            @PathVariable long sessionId,
            @RequestHeader(value = "X-QBank-Engine-Token", required = false) String serviceToken
    ) {
        requireServiceToken(serviceToken);
        return frozenPaperProvider.get(sessionId);
    }

    @GetMapping("/{sessionId}/versions/{versionLabel}")
    public FrozenVersionView getVersion(
            @PathVariable long sessionId,
            @PathVariable String versionLabel,
            @RequestHeader(value = "X-QBank-Engine-Token", required = false) String serviceToken
    ) {
        requireServiceToken(serviceToken);
        return frozenPaperProvider.version(sessionId, versionLabel);
    }

    @GetMapping("/{sessionId}/versions/{versionLabel}/key")
    public List<AnswerKeyEntry> getKey(
            @PathVariable long sessionId,
            @PathVariable String versionLabel,
            @RequestHeader(value = "X-QBank-Engine-Token", required = false) String serviceToken
    ) {
        requireServiceToken(serviceToken);
        return frozenPaperProvider.key(sessionId, versionLabel);
    }

    private void requireServiceToken(String supplied) {
        String configured = props.getEngine().getServiceToken();
        if (configured == null || configured.isBlank() || supplied == null
                || !cryptoPort.constantTimeEquals(configured, supplied.trim())) {
            throw new ForbiddenException("Valid engine service token required");
        }
    }
}
