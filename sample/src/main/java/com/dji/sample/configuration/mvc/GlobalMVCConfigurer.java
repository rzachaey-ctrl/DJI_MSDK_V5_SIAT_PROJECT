package com.dji.sample.configuration.mvc;

import com.dji.sample.component.AuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class GlobalMVCConfigurer implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    private static List<String> excludePaths = new ArrayList<>();

    @Value("${url.manage.prefix}")
    private String managePrefix;

    @Value("${url.manage.version}")
    private String manageVersion;


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Exclude the login interface.
        excludePaths.add("/" + managePrefix + manageVersion + "/login");
        excludePaths.add("/" + managePrefix + manageVersion + "/token/refresh");
        excludePaths.add("/swagger-ui.html");
        excludePaths.add("/swagger-ui/**");
        excludePaths.add("/v3/**");
        excludePaths.add("/ui/**");
        // WebSocket upgrades are authenticated by MsdkControlHandshakeInterceptor.
        excludePaths.add("/api/v1/msdk/control");
        // The RC Pro authenticates this binary download with the MSDK bridge token.
        excludePaths.add("/api/v1/msdk/missions/*/file");
        // Intercept for all request interfaces.
        registry.addInterceptor(authInterceptor).addPathPatterns("/**").excludePathPatterns(excludePaths);
    }
}
