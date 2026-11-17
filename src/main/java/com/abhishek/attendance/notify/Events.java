package com.abhishek.attendance.notify;

/**
 * Things that happened, published by the modules and turned into notifications by {@code NotificationService}.
 * Published inside the transaction; listeners run after commit.
 */
public final class Events {

    public record LeaveRequested(long requestId, long employeeId) {
    }

    public record LeaveDecided(long requestId, long employeeId, String status) {
    }

    public record CorrectionRequested(long correctionId, long employeeId) {
    }

    public record CorrectionDecided(long correctionId, long employeeId, String status) {
    }

    private Events() {
    }
}
