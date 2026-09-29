package com.xxx.insurance.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册统一 RequestIdentity 参数解析器；认证本身仍由未来网关/OIDC 组件负责。 */
@Configuration
@EnableConfigurationProperties(SecurityIdentityProperties.class)
public class SecurityWebConfig implements WebMvcConfigurer {

    private final SecurityIdentityProperties properties;

    public SecurityWebConfig(SecurityIdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new RequestIdentityArgumentResolver(properties));
    }
}
