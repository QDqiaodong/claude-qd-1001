package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
