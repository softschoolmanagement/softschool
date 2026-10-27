package com.softschool.backend.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Light projection of Attendance for the parent portal: the six plain columns the attendance screen
 * shows, and NEVER capturedPhoto (a LONGTEXT that the old query dragged along for every day of the
 * child's history). Populated by a JPQL constructor expression in AttendanceRepository.
 */
public class ParentAttendanceDTO {
    private final String memberType;
    private final LocalDate date;
    private final String status;
    private final String reason;
    private final LocalTime checkIn;
    private final LocalTime checkOut;

    public ParentAttendanceDTO(String memberType, LocalDate date, String status, String reason,
                               LocalTime checkIn, LocalTime checkOut) {
        this.memberType = memberType;
        this.date = date;
        this.status = status;
        this.reason = reason;
        this.checkIn = checkIn;
        this.checkOut = checkOut;
    }

    public String getMemberType() { return memberType; }
    public LocalDate getDate() { return date; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public LocalTime getCheckIn() { return checkIn; }
    public LocalTime getCheckOut() { return checkOut; }
}
