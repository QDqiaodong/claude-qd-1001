package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.PaperRoll;

public interface PaperRollRepository extends JpaRepository<PaperRoll, Long> {

    Optional<PaperRoll> findByRollNo(String rollNo);

    List<PaperRoll> findByMachineId(Long machineId);

    List<PaperRoll> findByStatus(String status);

    List<PaperRoll> findByMachineIdAndStatus(Long machineId, String status);

    List<PaperRoll> findByGrade(String grade);

    /** 挑可切母卷：可用状态 + 定量匹配 + 幅宽足够，按剩余长度倒序 */
    List<PaperRoll> findByStatusAndGrammageGsmAndWidthMmGreaterThanEqualOrderByLengthMDesc(
            String status, Integer grammageGsm, Integer widthMm);

    /** 领料事务先锁母卷行，避免两名操作员同时按旧余量扣减 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select r from PaperRoll r where r.id = :id")
    Optional<PaperRoll> findByIdForUpdate(@Param("id") Long id);
}
