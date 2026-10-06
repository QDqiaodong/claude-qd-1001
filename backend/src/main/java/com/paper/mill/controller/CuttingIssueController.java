package com.paper.mill.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.dto.CuttingIssueRequest;
import com.paper.mill.dto.IssueResult;
import com.paper.mill.dto.IssueTrace;
import com.paper.mill.service.CuttingIssueService;

/** 切纸工段 · 切裁领料闭环接口 */
@RestController
@RequestMapping("/api/cutting-issues")
public class CuttingIssueController {

    private final CuttingIssueService issueService;

    public CuttingIssueController(CuttingIssueService issueService) {
        this.issueService = issueService;
    }

    /** 登记一次领料；重复 requestId 返回原结果，失败不会写成功记录或扣减余量 */
    @PostMapping
    public ApiResponse<IssueResult> issue(@RequestBody CuttingIssueRequest request) {
        IssueResult result = issueService.issue(request);
        String message = result.success()
                ? result.message()
                : (result.duplicate() ? "原领料请求失败" : "领料失败");
        return ApiResponse.success(message, result);
    }

    /** 订单详情中的领料成功记录与失败原因 */
    @GetMapping("/orders/{orderId}/trace")
    public ApiResponse<IssueTrace> traceOrder(@PathVariable Long orderId) {
        return ApiResponse.success(issueService.traceOrder(orderId));
    }

    /** 母卷导轨中的领料成功记录与失败原因 */
    @GetMapping("/rolls/{rollId}/trace")
    public ApiResponse<IssueTrace> traceRoll(@PathVariable Long rollId) {
        return ApiResponse.success(issueService.traceRoll(rollId));
    }
}
