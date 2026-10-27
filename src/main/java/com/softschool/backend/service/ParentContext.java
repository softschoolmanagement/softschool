package com.softschool.backend.service;

import com.softschool.backend.dto.ParentChildDTO;
import com.softschool.backend.model.ParentAccount;
import com.softschool.backend.repository.ParentAccountRepository;
import com.softschool.backend.security.SchoolSessionService;
import com.softschool.backend.service.DataCacheService.Dep;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Turns the Authorization header into "which parent is this and which children are theirs".
 *
 * A parent session is a normal signed school session whose username is "parent:<last 10 digits>"
 * (the same trick teachers use with "teacher:<staffId>"). The school comes from the signed token,
 * never from the request, and EVERY child id a request names is checked against the parent's own
 * children before anything is read or written.
 */
@Component
public class ParentContext {

    public static final String PREFIX = "parent:";

    @Autowired private SchoolSessionService sessionService;
    @Autowired private ParentChildrenService childrenService;
    @Autowired private ParentAccountRepository accountRepository;
    @Autowired private DataCacheService cache;

    /**
     * The school-wide default password is the same for every parent, and phone numbers are not secret,
     * so on its own it would let anyone who knows a parent's number open that parent's account. With
     * this on (the default) the default password is only an ACTIVATION code: a session that signed in
     * with it can do nothing but change the password, and sees no child data until it has.
     * Turn off with parent.require-password-change=false.
     */
    @Value("${parent.require-password-change:true}")
    private boolean requirePasswordChange;

    public boolean isPasswordChangeRequired() { return requirePasswordChange; }

    public static boolean isParent(SchoolSessionService.Principal p) {
        return p != null && p.getUsername() != null && p.getUsername().startsWith(PREFIX);
    }

    /** For every data endpoint: also refuses a session that still uses the default password (see above). */
    public Ctx resolve(HttpServletRequest req) {
        return resolveInternal(req, true);
    }

    /** ONLY for POST /change-password: accepts a session that is still on the default password. */
    public Ctx resolveForPasswordChange(HttpServletRequest req) {
        return resolveInternal(req, false);
    }

    /** Returns null when the token is missing/invalid, is not a parent token, or the parent has no active child any more. */
    private Ctx resolveInternal(HttpServletRequest req, boolean strict) {
        String h = req.getHeader("Authorization");
        SchoolSessionService.Principal p = sessionService.verifyToken(
                h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null);
        if (!isParent(p)) return null;
        final String schoolId = p.getSchoolId();
        final String phone = p.getUsername().substring(PREFIX.length());
        if (strict && requirePasswordChange) {
            // PERFORMANCE: this runs on EVERY parent request (and the portal polls every few seconds), so the
            // "has this parent already chosen their own password" lookup is cached. It is dropped the moment
            // any ParentAccount row changes (password change, admin reset), see DataCacheInvalidationListener.
            Boolean activated = cache.get(schoolId, "acct|" + phone, 30, () -> {
                ParentAccount a = accountRepository.findBySchoolIdAndPhone(schoolId, phone).orElse(null);
                return Boolean.valueOf(a != null && a.getPasswordHash() != null && !a.getPasswordHash().isEmpty());
            }, Dep.ACCOUNTS);
            if (activated == null || !activated.booleanValue()) return null;
        }
        // PERFORMANCE: working out "which children does this phone number own" costs a scan of the school's
        // students (the phone is matched with LIKE over replace(...), which no index can serve) plus several
        // sibling lookups. The result only changes when a student row, a signup link or a sibling mark
        // changes, all of which invalidate this entry immediately; the TTL is just a safety net.
        List<ParentChildDTO> kids = cache.get(schoolId, "kids|" + phone, 60,
                () -> childrenService.childrenOf(schoolId, phone), Dep.STUDENTS, Dep.LINKS);
        if (kids == null || kids.isEmpty()) return null;
        return new Ctx(schoolId, phone, kids);
    }

    public static final class Ctx {
        public final String schoolId;
        public final String phone;
        public final List<ParentChildDTO> kids;

        Ctx(String schoolId, String phone, List<ParentChildDTO> kids) {
            this.schoolId = schoolId;
            this.phone = phone;
            this.kids = Collections.unmodifiableList(kids);
        }

        /** The child with this regNo, but ONLY if it is this parent's own child; otherwise null. */
        public ParentChildDTO child(String regNo) {
            if (regNo == null) return null;
            for (ParentChildDTO c : kids) if (c.getRegNo() != null && c.getRegNo().equals(regNo.trim())) return c;
            return null;
        }
    }
}
