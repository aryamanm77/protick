package com.yourorg.qbank.starter.web;

import com.yourorg.qbank.core.model.ExtraTimeGrant;
import com.yourorg.qbank.core.service.CoreStudentRosterAndInvigilatorService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("${qbank.base-path:/qbank}/api/v1/invigilator")
public class QBankInvigilatorController {

    private final CoreStudentRosterAndInvigilatorService rosterService;

    public QBankInvigilatorController(CoreStudentRosterAndInvigilatorService rosterService) {
        this.rosterService = rosterService;
    }

    @GetMapping("/sessions/{sessionId}/monitor")
    public CoreStudentRosterAndInvigilatorService.RoomMonitorView monitorRoom(
            @PathVariable long sessionId,
            @RequestParam(required = false) String roomCode
    ) {
        return rosterService.getRoomMonitor(sessionId, roomCode);
    }

    @PostMapping("/sessions/{sessionId}/students/{rollNumber}/reset-login")
    public Map<String, String> resetStudentLogin(
            @PathVariable long sessionId,
            @PathVariable String rollNumber,
            @RequestBody ResetLoginRequest req
    ) {
        rosterService.resetStudentLogin(sessionId, rollNumber, req.reason());
        return Map.of("status", "reset");
    }

    @PostMapping("/sessions/{sessionId}/extra-time")
    public ExtraTimeGrant grantExtraTime(
            @PathVariable long sessionId,
            @RequestBody ExtraTimeRequest req
    ) {
        return rosterService.grantExtraTime(sessionId, req.roomCode(), req.rollNumber(), req.extraMinutes(), req.reason());
    }

    public record ResetLoginRequest(String reason) {}
    public record ExtraTimeRequest(String roomCode, String rollNumber, int extraMinutes, String reason) {}
}
