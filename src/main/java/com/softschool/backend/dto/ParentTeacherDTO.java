package com.softschool.backend.dto;

/**
 * Light projection of Staff (teachers only) for the parent portal: just what is needed to decide
 * which classes a teacher teaches and to show her name and subjects. No photo / agreement / salary.
 */
public class ParentTeacherDTO {
    private final String staffId;
    private final String name;
    private final String subjects;
    private final String classes;
    private final String assignedClass;
    private final String assignedSection;
    private final String classAssignments;
    private final String inchargeAssignments;
    private final Boolean isClassIncharge;
    private final String incharge;

    public ParentTeacherDTO(String staffId, String name, String subjects, String classes,
                            String assignedClass, String assignedSection, String classAssignments,
                            String inchargeAssignments, Boolean isClassIncharge, String incharge) {
        this.staffId = staffId;
        this.name = name;
        this.subjects = subjects;
        this.classes = classes;
        this.assignedClass = assignedClass;
        this.assignedSection = assignedSection;
        this.classAssignments = classAssignments;
        this.inchargeAssignments = inchargeAssignments;
        this.isClassIncharge = isClassIncharge;
        this.incharge = incharge;
    }

    public String getStaffId() { return staffId; }
    public String getName() { return name; }
    public String getSubjects() { return subjects; }
    public String getClasses() { return classes; }
    public String getAssignedClass() { return assignedClass; }
    public String getAssignedSection() { return assignedSection; }
    public String getClassAssignments() { return classAssignments; }
    public String getInchargeAssignments() { return inchargeAssignments; }
    public Boolean getIsClassIncharge() { return isClassIncharge; }
    public String getIncharge() { return incharge; }
}
