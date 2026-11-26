package com.softschool.backend.repository;

import com.softschool.backend.model.ExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, Long> {

    /** Newest first, matching how the Examination screens list papers. */
    List<ExamPaper> findBySchoolIdOrderByCreatedAtDescIdDesc(String schoolId);

    Optional<ExamPaper> findBySchoolIdAndPaperId(String schoolId, String paperId);

    long countBySchoolId(String schoolId);

    /**
     * How many OTHER papers of the school still use this picture path. A duplicated paper
     * shares its pictures with the original, so a file is only deleted when this is 0.
     */
    @Query("select count(p) from ExamPaper p where p.schoolId = :sid and p.paperId <> :pid and p.layoutJson like concat('%', :path, '%')")
    long countOtherLayoutReferences(@Param("sid") String schoolId, @Param("pid") String paperId, @Param("path") String path);

    @Transactional
    long deleteBySchoolIdAndPaperId(String schoolId, String paperId);

    // Used ONLY when a school is permanently deleted by the super admin.
    @Transactional
    long deleteBySchoolId(String schoolId);
}
