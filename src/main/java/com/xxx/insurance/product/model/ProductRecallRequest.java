package com.xxx.insurance.product.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 产品召回请求。
 *
 * @param query 召回查询文本
 * @param conversationId 会话编号
 * @param topK 最大候选数量
 * @param filters 召回过滤条件
 */
@Schema(description = "产品召回请求")
public record ProductRecallRequest(
        @Schema(description = "召回查询文本", example = "我想找一款重大疾病保险")
        @NotBlank(message = "query must not be blank")
        @Size(max = 2000, message = "query length must be less than or equal to 2000")
        String query,

        @Schema(description = "会话编号", example = "recall-test-001")
        @Size(max = 64, message = "conversationId length must be less than or equal to 64")
        String conversationId,

        @Schema(description = "最大候选数量，未传时默认为 3", example = "3")
        @Min(value = 1, message = "topK must be greater than or equal to 1")
        @Max(value = 10, message = "topK must be less than or equal to 10")
        Integer topK,

        @Schema(description = "召回过滤条件，当前 Mock 实现仅保留并记录，不执行过滤")
        Map<String, String> filters) {
}
