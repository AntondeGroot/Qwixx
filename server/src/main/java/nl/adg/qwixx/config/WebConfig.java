package nl.adg.qwixx.config;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private WebProperties webProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/i18n/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String[] locs = webProperties.getResources().getStaticLocations();

        // index.html: never cache — it is the entry point for the SPA and
        // references content-hashed assets that change on every build.
        // Cloudflare and nginx must always fetch a fresh copy.
        registry.addResourceHandler("/", "/index.html")
                .addResourceLocations(locs)
                .setCacheControl(CacheControl.noStore());

        // Fingerprinted JS / CSS / assets: safe to cache indefinitely because
        // the filename hash changes whenever the content changes.
        registry.addResourceHandler("/**")
                .addResourceLocations(locs)
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).immutable());
    }
}
