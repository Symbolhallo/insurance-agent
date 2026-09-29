package com.xxx.insurance.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为内置 React 联调页关闭浏览器缓存。
 *
 * <p>该页面的生产脚本使用固定的 {@code assets/app.js} 路径。禁用缓存可以保证重新构建并重启
 * Spring Boot 后，浏览器加载当前 classpath 中的页面、脚本和样式，而不是继续执行旧版前端逻辑。</p>
 */
@Configuration
public class WorkflowTestResourceConfig implements WebMvcConfigurer {

    /** 仅覆盖联调页静态资源，不改变其他业务静态资源的缓存策略。 */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/workflow-test/**")
                .addResourceLocations("classpath:/static/workflow-test/")
                .setCacheControl(CacheControl.noStore());
    }
}
