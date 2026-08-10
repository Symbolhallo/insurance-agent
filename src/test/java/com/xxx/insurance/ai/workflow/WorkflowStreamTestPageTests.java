package com.xxx.insurance.ai.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStreamTestPageTests {

    @Test
    void packagesSameOriginWorkflowStreamTestPage() throws IOException {
        String html = resource("static/workflow-test/index.html");
        String script = resource("static/workflow-test/app.js");
        String styles = resource("static/workflow-test/styles.css");

        assertThat(html)
                .contains("保险智能体工作流测试台")
                .contains("id=\"queryForm\"")
                .contains("id=\"confirmForm\"")
                .contains("./app.js")
                .contains("./styles.css");
        assertThat(script)
                .contains("/api/v1/workflows/main")
                .contains("/runs/stream")
                .contains("/product-confirmations/stream")
                .contains("Last-Event-ID")
                .contains("response.body")
                .contains("streamId")
                .contains("chunkIndex")
                .doesNotContain("innerHTML", "eval(");
        assertThat(styles)
                .contains("@media (max-width: 680px)")
                .contains("overflow-wrap: anywhere");
    }

    /** 读取打包前的类路径资源，避免测试依赖外部浏览器或运行中的服务。 */
    private String resource(String path) throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing classpath resource: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
