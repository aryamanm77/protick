package com.example.hostapp;

import com.yourorg.qbank.core.event.PaperFrozenEvent;
import com.yourorg.qbank.core.exception.QBankException;
import com.yourorg.qbank.core.model.AnswerKeyEntry;
import com.yourorg.qbank.core.model.FrozenPaperMeta;
import com.yourorg.qbank.core.model.FrozenVersionView;
import com.yourorg.qbank.core.port.FrozenPaperProvider;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/host/api")
public class HostExamEngineController {

    private final FrozenPaperProvider frozenPaperProvider;
    private final JdbcTemplate jdbc;

    public HostExamEngineController(FrozenPaperProvider frozenPaperProvider, JdbcTemplate jdbc) {
        this.frozenPaperProvider = frozenPaperProvider;
        this.jdbc = jdbc;
    }

    @EventListener
    public void onPaperFrozen(PaperFrozenEvent event) {
        jdbc.update(
                "INSERT INTO host_exam_notifications (exam_id, session_id, subject_code, chosen_set, received_at) VALUES (?, ?, ?, ?, ?)",
                event.examId(),
                event.sessionId(),
                event.subjectCode(),
                event.chosenSetNumber(),
                event.occurredAt().toEpochMilli()
        );
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications() {
        return jdbc.queryForList("SELECT exam_id, session_id, subject_code, chosen_set, received_at FROM host_exam_notifications ORDER BY id");
    }

    @GetMapping("/runner/session/{sessionId}")
    public FrozenPaperMeta runnerMeta(@PathVariable long sessionId) {
        return frozenPaperProvider.get(sessionId);
    }

    @GetMapping("/runner/session/{sessionId}/version/{versionLabel}")
    public FrozenVersionView runnerVersion(@PathVariable long sessionId, @PathVariable String versionLabel) {
        return frozenPaperProvider.version(sessionId, versionLabel);
    }

    @GetMapping("/runner/session/{sessionId}/version/{versionLabel}/key")
    public List<AnswerKeyEntry> runnerKey(@PathVariable long sessionId, @PathVariable String versionLabel) {
        return frozenPaperProvider.key(sessionId, versionLabel);
    }

    @ExceptionHandler(QBankException.class)
    public ResponseEntity<Map<String, Object>> handleQBankException(QBankException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of(
                        "code", ex.getCode(),
                        "message", ex.getMessage() == null ? ex.getCode() : ex.getMessage()
                ));
    }
}
