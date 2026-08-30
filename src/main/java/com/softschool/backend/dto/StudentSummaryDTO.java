package com.softschool.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * PERFORMANCE FIX — "Student" dashboard card is slow.
 *
 * Student.photo / Student.certData / Student.otherFeesData are @Lob
 * LONGTEXT columns holding base64 images/certificates — often hundreds of
 * KB each. Hibernate fetches @Lob string columns EAGERLY by default (no
 * bytecode-enhancement lazy loading configured in this project), so every
 * plain `findBySchoolId(...)` used to pull down every one of those blobs
 * for every student, even when the caller (main.js's dashboard) only
 * wants a headcount, status, admission date and admission fee to compute
 * the Student attendance-ring card and Total Revenue.
 *
 * This DTO is populated via a JPQL constructor expression
 * (`select new ...StudentSummaryDTO(...)`), which tells Hibernate to only
 * SELECT these 4 columns at the SQL level — the LOB columns are never
 * read off disk or sent over the wire for this endpoint.
 */
@Data
@NoArgsConstructor
public class StudentSummaryDTO {
    private String regNo;
    private String status;
    private Date admissionDate;
    private Double admissionFee;

    public StudentSummaryDTO(String regNo, String status, Date admissionDate, Double admissionFee) {
        this.regNo = regNo;
        this.status = status;
        this.admissionDate = admissionDate;
        this.admissionFee = admissionFee;
    }
}
