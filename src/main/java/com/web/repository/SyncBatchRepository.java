package com.web.repository;

import com.web.entity.SyncBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncBatchRepository extends JpaRepository<SyncBatch, Long> {
}
