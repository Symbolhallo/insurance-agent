package com.xxx.insurance.product.formatter;

import com.xxx.insurance.product.model.ProductAnalysisAnswerInspection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品分析模型回答检查器。
 *
 * <p>该组件只做轻量格式检查，用于本地真实模型联调时观察模型是否遵守 Skill 中定义的
 * Markdown 输出合同。它不替代金融合规审核，也不根据检查结果拦截回答，避免在 Phase1
 * 阶段引入复杂的自动评测或人工确认流程。</p>
 */
@Component
public class ProductAnalysisAnswerInspector {

    private static final List<String> LIMITED_ANALYSIS_SECTIONS = List.of(
            "## 分析结论",
            "## 产品事实",
            "## 适配分析",
            "## 风险提示",
            "## 后续建议");

    private static final List<String> BATCH_ANALYSIS_SECTIONS = List.of(
            "## 对比结论",
            "## 产品对比表",
            "## 适配排序",
            "## 关键风险",
            "## 后续建议");

    public ProductAnalysisAnswerInspection inspect(String answer) {
        if (!StringUtils.hasText(answer)) {
            return new ProductAnalysisAnswerInspection(false, List.of("answer must not be blank"));
        }

        List<String> missingLimitedSections = missingSections(answer, LIMITED_ANALYSIS_SECTIONS);
        List<String> missingBatchSections = missingSections(answer, BATCH_ANALYSIS_SECTIONS);
        if (missingLimitedSections.isEmpty() || missingBatchSections.isEmpty()) {
            return new ProductAnalysisAnswerInspection(true, List.of());
        }

        List<String> missingSections = missingLimitedSections.size() <= missingBatchSections.size()
                ? missingLimitedSections
                : missingBatchSections;
        return new ProductAnalysisAnswerInspection(false, missingSections);
    }

    private List<String> missingSections(String answer, List<String> expectedSections) {
        List<String> missingSections = new ArrayList<>();
        for (String expectedSection : expectedSections) {
            if (!answer.contains(expectedSection)) {
                missingSections.add(expectedSection);
            }
        }
        return missingSections;
    }
}
