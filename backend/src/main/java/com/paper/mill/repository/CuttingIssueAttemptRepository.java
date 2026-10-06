package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import com.paper.mill.entity.CuttingIssueAttempt;

public interface CuttingIssueAttemptRepository extends JpaRepository<CuttingIssueAttempt, Long> {

    Optional<CuttingIssueAttempt> findByRequestId(String requestId);

    List<CuttingIssueAttempt> findByOrderIdOrderByCreatedAtDesc(Long orderId);

    List<CuttingIssueAttempt> findByRollIdOrderByCreatedAtDesc(Long rollId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select a from CuttingIssueAttempt a where a.requestId = :requestId")
    Optional<CuttingIssueAttempt> findByRequestIdForUpdate(@Param("requestId") String requestId);

    @Query("select distinct a from CuttingIssueAttempt a left join fetch a.issue "
            + "where a.orderId = :orderId order by a.createdAt desc")
    List<CuttingIssueAttempt> findDetailedByOrderIdOrderByCreatedAtDesc(@Param("orderId") Long orderId);

    @Query("select distinct a from CuttingIssueAttempt a left join fetch a.issue "
            + "where a.rollId = :rollId order by a.createdAt desc")
    List<CuttingIssueAttempt> findDetailedByRollIdOrderByCreatedAtDesc(@Param("rollId") Long rollId);

    @Query("select a from CuttingIssueAttempt a left join fetch a.issue i "
            + "left join fetch i.order left join fetch i.roll "
            + "where a.requestId = :requestId")
    Optional<CuttingIssueAttempt> findDetailedByRequestId(@Param("requestId") String requestId);
}
