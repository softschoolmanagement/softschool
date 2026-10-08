-- Teacher portal tables (MySQL 8). Spring's ddl-auto=update will also create them from the entities.

CREATE TABLE IF NOT EXISTS teacher_tests (
  id          VARCHAR(40)  NOT NULL,
  school_id   VARCHAR(64)  NOT NULL,
  staff_id    VARCHAR(64)  NOT NULL,
  staff_name  VARCHAR(120),
  kind        VARCHAR(30),
  title       VARCHAR(120) NOT NULL,
  cls         VARCHAR(60)  NOT NULL,
  subject     VARCHAR(80)  NOT NULL,
  test_date   VARCHAR(10),
  total_marks DOUBLE       NOT NULL,
  marked      BIT(1)       NOT NULL DEFAULT 0,
  payload     LONGTEXT     NOT NULL,          -- full test JSON as the app sends it (syllabus, results, ...)
  created_at  BIGINT,
  updated_at  BIGINT,
  PRIMARY KEY (school_id, id),
  KEY idx_tt_staff (school_id, staff_id),
  KEY idx_tt_class (school_id, cls)
);

CREATE TABLE IF NOT EXISTS announcements (      -- admin -> teachers
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  school_id  VARCHAR(64)  NOT NULL,
  audience   VARCHAR(20)  NOT NULL DEFAULT 'teachers',
  title      VARCHAR(120) NOT NULL,
  body       TEXT         NOT NULL,
  created_by VARCHAR(120),
  priority   VARCHAR(10)  NOT NULL DEFAULT 'normal',
  created_at BIGINT       NOT NULL,
  KEY idx_an_school (school_id, audience, created_at)
);

CREATE TABLE IF NOT EXISTS teacher_messages (   -- teacher -> admin / parents of a class
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  school_id  VARCHAR(64)  NOT NULL,
  staff_id   VARCHAR(64)  NOT NULL,
  staff_name VARCHAR(120),
  audience   VARCHAR(20)  NOT NULL,           -- admin | parents
  class_name VARCHAR(60),
  title      VARCHAR(120) NOT NULL,
  body       TEXT         NOT NULL,
  priority   VARCHAR(10)  NOT NULL DEFAULT 'normal',
  created_at BIGINT       NOT NULL,
  read_by_admin BIT(1)    NOT NULL DEFAULT 0,
  KEY idx_tm_school (school_id, audience, created_at)
);
