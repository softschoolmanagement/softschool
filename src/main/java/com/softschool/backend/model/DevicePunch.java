package com.softschool.backend.model;

/**
 * One staff member's punch summary for "today", as computed by the local
 * zkteco_sync_agent.py script from the ZKTeco Time software's local
 * database (badge -> first/last CHECKTIME). Sent to
 * POST /api/biometric/sync as part of BiometricSyncRequest.
 *
 * firstPunch / lastPunch are ISO-8601 local date-time strings
 * (e.g. "2026-08-17T08:03:12"), no timezone offset — parsed with
 * LocalDateTime.parse() in ZktecoService.
 */
public class DevicePunch {
    private String badgeNumber;
    private String name;
    private String firstPunch;
    private String lastPunch;

    public String getBadgeNumber() { return badgeNumber; }
    public void setBadgeNumber(String badgeNumber) { this.badgeNumber = badgeNumber; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFirstPunch() { return firstPunch; }
    public void setFirstPunch(String firstPunch) { this.firstPunch = firstPunch; }

    public String getLastPunch() { return lastPunch; }
    public void setLastPunch(String lastPunch) { this.lastPunch = lastPunch; }
}
