package com.paper.mill.dto;

import java.util.List;

/**
 * 切裁领料追溯面板：成功记录与失败请求分开返回，页面刷新后失败原因仍可见。
 */
public record IssueTrace(
        List<IssueView> issues,
        List<IssueAttemptView> attempts
) {
}
