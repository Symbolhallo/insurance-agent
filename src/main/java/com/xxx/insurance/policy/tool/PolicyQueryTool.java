package com.xxx.insurance.policy.tool;

import com.xxx.insurance.policy.model.PolicyQueryResult;
import com.xxx.insurance.policy.service.PolicyQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 客户保单查询 Tool，当前委托本地 Mock Service。 */
@Component
public class PolicyQueryTool {

    public static final String TOOL_NAME = "customer_policy_query";

    private static final Logger log = LoggerFactory.getLogger(PolicyQueryTool.class);

    private final PolicyQueryService policyQueryService;

    public PolicyQueryTool(PolicyQueryService policyQueryService) {
        this.policyQueryService = policyQueryService;
    }

    @Tool(
            name = TOOL_NAME,
            description = "查询固定Mock客户的脱敏保单数据。当前只支持客户编号MOCK-CUSTOMER-001。")
    public PolicyQueryResult queryPolicies(
            @ToolParam(description = "客户编号，当前固定为MOCK-CUSTOMER-001") String customerId,
            @ToolParam(description = "可选保单状态：IN_FORCE或PAID_UP", required = false)
            String policyStatus) {
        log.info("[Tool] name={} customerId={} policyStatus={}", TOOL_NAME, customerId, policyStatus);
        return policyQueryService.queryPolicies(customerId, policyStatus);
    }
}
