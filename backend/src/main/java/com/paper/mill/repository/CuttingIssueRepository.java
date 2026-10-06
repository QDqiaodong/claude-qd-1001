package com.paper.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.CuttingIssue;

public interface CuttingIssueRepository extends JpaRepository<CuttingIssue, Long> {

    List<CuttingIssue> findByOrderIdOrderByOperatedAtDesc(Long orderId);

    List<CuttingIssue> findByRollIdOrderByOperatedAtDesc(Long rollId);

    boolean existsByRequestId(String requestId);
}
