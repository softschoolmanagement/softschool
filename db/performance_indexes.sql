-- Soft School: performance indexes (safe to run more than once on MySQL 8+).
-- The same indexes are declared on the entities, so Hibernate (ddl-auto=update) creates them on the next
-- start. Run this script instead if you want them NOW, or on a large production table without a restart.
-- Tip: run it in a quiet period; building an index on a big table takes a moment.

DELIMITER $$
DROP PROCEDURE IF EXISTS add_idx$$
CREATE PROCEDURE add_idx(t VARCHAR(64), i VARCHAR(64), cols VARCHAR(255))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = t)
     AND NOT EXISTS (SELECT 1 FROM information_schema.statistics
                     WHERE table_schema = DATABASE() AND table_name = t AND index_name = i) THEN
    SET @s = CONCAT('CREATE INDEX ', i, ' ON ', t, ' (', cols, ')');
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

-- attendance (had NO index at all; every read scanned the table)
CALL add_idx('attendance', 'idx_att_member',          'school_id, member_id, attendance_date');
CALL add_idx('attendance', 'idx_att_school_date',     'school_id, attendance_date');
CALL add_idx('attendance', 'idx_att_school_type_date','school_id, member_type, attendance_date');

-- students (parent login / children lookup, dashboards)
CALL add_idx('students', 'idx_students_school_status',  'school_id, status');
CALL add_idx('students', 'idx_students_school_sibling', 'school_id, sibling_group_id');
CALL add_idx('students', 'idx_students_school_class',   'school_id, student_class');

-- staff
CALL add_idx('staff', 'idx_staff_school_type', 'school_id, type');

-- announcements, tests, chat
CALL add_idx('announcement',    'idx_ann_school_aud_created', 'school_id, audience, created_at');
CALL add_idx('teacher_test',    'idx_tt_school_class',        'school_id, class_name, updated_at');
CALL add_idx('teacher_message', 'idx_tmsg_channel_class',     'school_id, channel, class_name, created_at');

DROP PROCEDURE add_idx;
