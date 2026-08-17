package com.softschool.backend.model;

import java.util.List;

/**
 * Body of POST /api/biometric/sync. Sent by the local zkteco_sync_agent.py
 * script (which reads the ZKTeco Time software's database on the school's
 * own machine, since the backend itself can no longer reach that local
 * file now that it's hosted on Railway).
 */
public class BiometricSyncRequest {
    private String schoolId;
    private List<DevicePunch> punches;

    public String getSchoolId() { return schoolId; }
    public void setSchoolId(String schoolId) { this.schoolId = schoolId; }

    public List<DevicePunch> getPunches() { return punches; }
    public void setPunches(List<DevicePunch> punches) { this.punches = punches; }
}
