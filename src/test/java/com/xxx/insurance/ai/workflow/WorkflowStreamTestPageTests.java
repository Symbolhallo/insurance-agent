package com.xxx.insurance.ai.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStreamTestPageTests {

    @Test
    void packagesSameOriginWorkflowStreamTestPage() throws IOException {
        String html = resource("static/workflow-test/index.html");
        String script = resource("static/workflow-test/assets/app.js");
        String styles = resource("static/workflow-test/assets/styles.css");
        String source = source("frontend/workflow-test/src/App.jsx");
        String workflowProgress = source("frontend/workflow-test/src/WorkflowProgress.jsx");
        String packageJson = source("frontend/workflow-test/package.json");
        String resourceConfig = source(
                "src/main/java/com/xxx/insurance/common/config/WorkflowTestResourceConfig.java");

        assertThat(html)
                .contains("保险智能体工作流测试台")
                .contains("id=\"root\"")
                .contains("/workflow-test/assets/app.js")
                .contains("/workflow-test/assets/styles.css")
                .doesNotContain("http://", "https://");
        assertThat(script)
                .contains("/api/v1/workflows/main")
                .contains("/runs/stream")
                .contains("/events")
                .contains("/product-confirmations/stream")
                .contains("/api/v1/ai/memory")
                .contains("X-Workflow-Instance-Id")
                .contains("Last-Event-ID")
                .contains("sessionStorage")
                .contains("streamId")
                .contains("chunkIndex");
        assertThat(source)
                .contains("function App()")
                .contains("function useAutoFollow(changeToken)")
                .contains("response.body")
                .contains("pendingFrameRef.current = requestAnimationFrame")
                .contains("onWheel: handleWheel")
                .contains("event.deltaY < 0")
                .contains("onTouchMove: handleTouchMove")
                .contains("currentY > startY + 4")
                .contains("followingRef.current = false")
                .contains("cancelAnimationFrame(pendingFrameRef.current)")
                .contains("distanceFromBottom <= 12")
                .contains("aria-label=\"恢复自动跟随\"")
                .contains("function ConversationSidebar(")
                .contains("function ConversationHistory(")
                .contains("historyManageMode")
                .contains("selectedConversationIds")
                .contains("requestDeleteSelectedConversations")
                .contains("toggleAllConversations")
                .contains("批量删除历史会话")
                .contains("每个会话仍会独立校验运行状态")
                .contains("aria-label=\"新建对话\"")
                .contains("function DeleteConversationDialog(")
                .contains("历史消息和审计数据仍会保留")
                .contains("method: \"DELETE\"")
                .contains("toHistoryMessages(snapshot)")
                .contains("limit=200")
                .contains("ReactMarkdown")
                .contains("remarkGfm")
                .contains("function MarkdownContent(")
                .contains("stream.finished")
                .contains("stream-markdown")
                .contains("WORKFLOW_STATUS_WAITING_CONFIRM")
                .contains("readActiveWorkflow")
                .contains("updateActiveWorkflow")
                .contains("abortableDelay")
                .contains("MAX_RECONNECT_ATTEMPTS")
                .contains("id=\"queryForm\"")
                .contains("id=\"confirmForm\"")
                .doesNotContain("innerHTML", "dangerouslySetInnerHTML", "eval(", "window.confirm");
        assertThat(workflowProgress)
                .contains("visibleNodeCodes")
                .contains("stages.forEach(event => appendNode(event.node))")
                .contains("streams.forEach(stream => appendNode(PHASE_NODES.get(stream.phase)))")
                .doesNotContain("NODES.map");
        assertThat(packageJson)
                .contains("\"react\": \"18.3.1\"")
                .contains("\"vite\": \"6.4.3\"")
                .contains("\"react-markdown\"")
                .contains("\"remark-gfm\"")
                .contains("\"lucide-react\"");
        assertThat(resourceConfig)
                .contains("/workflow-test/**")
                .contains("classpath:/static/workflow-test/")
                .contains("CacheControl.noStore()");
        assertThat(styles)
                .contains("@media(max-width:680px)")
                .contains("overflow-wrap:anywhere")
                .contains("scrollbar-gutter:stable");
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

    /** 读取仓库中的 React 源码，防止仅提交旧 bundle 而遗漏可维护的前端实现。 */
    private String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
