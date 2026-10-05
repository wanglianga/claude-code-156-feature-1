package com.county.coldchain.repo;

import com.county.coldchain.domain.DomainEnums.TempCaseStatus;
import com.county.coldchain.domain.entity.TempExceedCase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TempExceedCaseRepository extends JpaRepository<TempExceedCase, Long> {
    List<TempExceedCase> findByBatchIdOrderByCreatedAtDesc(Long batchId);

    List<TempExceedCase> findByStatusOrderByCreatedAtDesc(TempCaseStatus status);

    List<TempExceedCase> findAllByOrderByCreatedAtDesc();

    @Query("select coalesce(max(c.id),0) from TempExceedCase c")
    long maxId();
}
