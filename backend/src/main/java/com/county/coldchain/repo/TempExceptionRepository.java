package com.county.coldchain.repo;

import com.county.coldchain.domain.DomainEnums.TempExceptionStatus;
import com.county.coldchain.domain.entity.TempExceptionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TempExceptionRepository extends JpaRepository<TempExceptionRecord, Long> {
    List<TempExceptionRecord> findByBatchIdOrderByCreatedAtDesc(Long batchId);
    List<TempExceptionRecord> findByStatusOrderByCreatedAtDesc(TempExceptionStatus status);
    List<TempExceptionRecord> findAllByOrderByCreatedAtDesc();
}
