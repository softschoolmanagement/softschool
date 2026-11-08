package com.softschool.backend.repository;

import com.softschool.backend.model.FeedbackSeen;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackSeenRepository extends JpaRepository<FeedbackSeen, String> {
}
