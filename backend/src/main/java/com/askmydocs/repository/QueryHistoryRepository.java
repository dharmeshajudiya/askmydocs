package com.askmydocs.repository;

import com.askmydocs.entity.QueryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QueryHistoryRepository extends JpaRepository<QueryHistory, UUID> {
    List<QueryHistory> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
