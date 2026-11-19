package com.softschool.backend.service;

import com.softschool.backend.model.Student;
import com.softschool.backend.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SiblingLinkServiceTest {

    @Mock StudentRepository repo;
    @InjectMocks SiblingLinkService service;

    private static Student student(long id, String reg, String name, String father, String cnic) {
        Student s = new Student();
        s.setId(id); s.setSchoolId("SS_1"); s.setRegNo(reg); s.setFullName(name);
        s.setFatherFullName(father); s.setFatherCnic(cnic); s.setStatus("active");
        return s;
    }

    @Test
    void normalizers() {
        assertEquals("1730112345671", SiblingLinkService.normalizeCnic("17301-1234567-1"));
        assertNull(SiblingLinkService.normalizeCnic("12345"));
        assertEquals("muhammad ali khan", SiblingLinkService.normalizeName("  Muhammad  ALI Khan. "));
        assertNull(SiblingLinkService.normalizeName(" "));
    }

    @Test
    void linksMatchingFatherIntoNewGroup() {
        Student me = student(1, "R1", "Sara", "Ali Khan", "17301-1234567-1");
        Student other = student(2, "R2", "Ahmed", "ali  khan", "1730112345671");
        when(repo.findById(1L)).thenReturn(Optional.of(me));
        when(repo.findActiveByFatherCnicDigits("SS_1", 1L, "1730112345671")).thenReturn(List.of(other));
        when(repo.findSiblingGroupIdsBySchoolId("SS_1")).thenReturn(List.of("001", "003"));

        service.linkByFatherDetails(1L);

        assertEquals("004", me.getSiblingGroupId());
        assertEquals("004", other.getSiblingGroupId());
        assertTrue(me.getIsSibling());
        assertEquals("Sibling of Ahmed", me.getSiblingOf());
        assertEquals("Sibling of Sara", other.getSiblingOf());
        assertTrue(me.getHasSiblings().contains("\"regNo\":\"R2\""));
        verify(repo).saveAll(any());
    }

    @Test
    void sameCnicDifferentFatherNameIsNotLinked() {
        Student me = student(1, "R1", "Sara", "Ali Khan", "1730112345671");
        Student other = student(2, "R2", "Ahmed", "Bilal Khan", "1730112345671");
        when(repo.findById(1L)).thenReturn(Optional.of(me));
        when(repo.findActiveByFatherCnicDigits(anyString(), any(), anyString())).thenReturn(List.of(other));

        service.linkByFatherDetails(1L);

        assertNull(me.getSiblingGroupId());
        verify(repo, never()).saveAll(any());
    }

    @Test
    void joinsExistingGroupInsteadOfCreatingNewOne() {
        Student me = student(1, "R1", "Sara", "Ali Khan", "1730112345671");
        Student other = student(2, "R2", "Ahmed", "Ali Khan", "1730112345671");
        other.setSiblingGroupId("007");
        when(repo.findById(1L)).thenReturn(Optional.of(me));
        when(repo.findActiveByFatherCnicDigits(anyString(), any(), anyString())).thenReturn(List.of(other));
        when(repo.findBySchoolIdAndSiblingGroupId("SS_1", "007")).thenReturn(List.of(other));

        service.linkByFatherDetails(1L);

        assertEquals("007", me.getSiblingGroupId());
        verify(repo, never()).findSiblingGroupIdsBySchoolId(anyString());
    }
}
