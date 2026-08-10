package com.xxx.insurance.asset.tool;

import com.xxx.insurance.asset.model.AssetQueryResult;
import com.xxx.insurance.asset.service.AssetQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 客户资产查询 Tool，当前委托本地 Mock Service。 */
@Component
public class AssetQueryTool {

    public static final String TOOL_NAME = "customer_asset_query";

    private static final Logger log = LoggerFactory.getLogger(AssetQueryTool.class);

    private final AssetQueryService assetQueryService;

    public AssetQueryTool(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    @Tool(
            name = TOOL_NAME,
            description = "查询固定Mock客户的脱敏资产持仓。当前只支持客户编号MOCK-CUSTOMER-001。")
    public AssetQueryResult queryAssets(
            @ToolParam(description = "客户编号，当前固定为MOCK-CUSTOMER-001") String customerId,
            @ToolParam(description = "可选资产类型：DEPOSIT、WEALTH_MANAGEMENT或FUND", required = false)
            String assetType) {
        log.info("[Tool] name={} customerId={} assetType={}", TOOL_NAME, customerId, assetType);
        return assetQueryService.queryAssets(customerId, assetType);
    }
}
