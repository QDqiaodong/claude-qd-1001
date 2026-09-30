package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.CuttingOrder;

public interface CuttingOrderRepository extends JpaRepository<CuttingOrder, Long> {

    Optional<CuttingOrder> findByOrderNo(String orderNo);

    List<CuttingOrder> findByStatus(String status);

    List<CuttingOrder> findByCustomerName(String customerName);
}
