-- Examination module tables (MySQL 8).
--
-- You normally do NOT need to run this: application.properties sets
-- spring.jpa.hibernate.ddl-auto=update, so Hibernate creates these tables the
-- first time the new version starts. Run it by hand only if your database user
-- has no CREATE privilege (or you have switched ddl-auto to validate/none).
-- Safe to run more than once.

CREATE TABLE IF NOT EXISTS exam_papers (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  school_id      VARCHAR(100) NOT NULL,
  paper_id       VARCHAR(40)  NOT NULL,
  title          VARCHAR(200) NOT NULL,
  term           VARCHAR(40)  NOT NULL,
  class_name     VARCHAR(80)  NOT NULL,
  section_name   VARCHAR(40)  NULL,
  subject        VARCHAR(80)  NOT NULL,
  exam_date      VARCHAR(10)  NULL,
  duration_mins  INT          NOT NULL,
  instructions   VARCHAR(2000) NULL,
  total_marks    DOUBLE       NOT NULL,
  sections_json  LONGTEXT     NOT NULL,
  created_at     DATETIME(6)  NOT NULL,
  updated_at     DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_paper_school_pid (school_id, paper_id),
  KEY idx_exam_paper_school_class (school_id, class_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_paper_results (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  school_id   VARCHAR(100) NOT NULL,
  paper_id    VARCHAR(40)  NOT NULL,
  total_marks DOUBLE       NOT NULL,
  status      VARCHAR(12)  NOT NULL,
  marks_json  LONGTEXT     NOT NULL,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_paper_result_school_paper (school_id, paper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_datesheet (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  school_id   VARCHAR(100) NOT NULL,
  entry_id    VARCHAR(40)  NOT NULL,
  term        VARCHAR(40)  NOT NULL,
  class_name  VARCHAR(80)  NOT NULL,
  subject     VARCHAR(80)  NOT NULL,
  exam_date   VARCHAR(10)  NOT NULL,
  start_time  VARCHAR(5)   NOT NULL,
  end_time    VARCHAR(5)   NOT NULL,
  room        VARCHAR(60)  NULL,
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_ds_school_eid (school_id, entry_id),
  KEY idx_exam_ds_school_date (school_id, exam_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS exam_grading (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  school_id   VARCHAR(100) NOT NULL,
  scale_json  VARCHAR(4000) NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exam_grading_school (school_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
