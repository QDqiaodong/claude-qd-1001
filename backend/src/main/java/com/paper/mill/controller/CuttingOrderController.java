package com.paper.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.service.CuttingOrderService;

/** 切纸工段 · 切纸订单接口 */
@RestController
@RequestMapping("/api/cutting-orders")
public class CuttingOrderController {

    private final CuttingOrderService orderService;

    public CuttingOrderController(CuttingOrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ApiResponse<List<CuttingOrder>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String customerName) {
        return ApiResponse.success(orderService.list(status, customerName));
    }

    @GetMapping("/{id}")
    public ApiResponse<CuttingOrder> get(@PathVariable Long id) {
        return ApiResponse.success(orderService.get(id));
    }

    @PostMapping
    public ApiResponse<CuttingOrder> create(@RequestBody CuttingOrder input) {
        return ApiResponse.success("订单已创建", orderService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<CuttingOrder> update(@PathVariable Long id, @RequestBody CuttingOrder input) {
        return ApiResponse.success("已更新", orderService.update(id, input));
    }

    /** 校验母卷能否用于本订单 */
    @GetMapping("/{id}/check-roll")
    public ApiResponse<Map<String, Object>> checkRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success(orderService.checkRoll(id, rollNo));
    }

    /** 排产：指定母卷上机，订单推进到切裁中 */
    @PostMapping("/{id}/assign-roll")
    public ApiResponse<CuttingOrder> assignRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success("已排产", orderService.assignRoll(id, rollNo));
    }

    /** 状态推进 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<CuttingOrder> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", orderService.transfer(id, target));
    }
}
