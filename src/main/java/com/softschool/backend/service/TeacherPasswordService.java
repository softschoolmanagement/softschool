package com.softschool.backend.service;

import com.softschool.backend.model.SchoolSettings;
import com.softschool.backend.model.Staff;
import com.softschool.backend.repository.SchoolSettingsRepository;
import com.softschool.backend.security.PasswordHashUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Teacher-portal password rules.
 *
 *  - The school admin sets ONE default teacher password in Settings
 *    (stored hashed on SchoolSettings).
 *  - A teacher who has not changed her password signs in with that default.
 *  - A teacher may change her own password exactly once. After that her
 *    personal hash (Staff.passwordHash) is used and the default no longer
 *    works for her. An admin reset clears her hash, going back to the default.
 */
@Component
public class TeacherPasswordService {

    @Autowired
    private SchoolSettingsRepository settingsRepository;

    /** True once the teacher has used her one-time password change. */
    public boolean hasChanged(Staff staff) {
        return staff.getPasswordHash() != null && !staff.getPasswordHash().isEmpty();
    }

    /** Personal hash if she changed it, otherwise the school's default hash (may be null if admin never set one). */
    public String effectiveHash(Staff staff) {
        if (hasChanged(staff)) return staff.getPasswordHash();
        return settingsRepository.findBySchoolId(staff.getSchoolId())
                .map(SchoolSettings::getTeacherPasswordHash)
                .orElse(null);
    }

    public boolean verify(Staff staff, String password) {
        String hash = effectiveHash(staff);
        return hash != null && PasswordHashUtil.verify(password, hash);
    }
}
