package com.ulp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration.
 *
 * <p>Public avatar/exam files are served by
 * {@link com.ulp.features.upload.PublicUploadsController} (dual-read object
 * storage). The previous broad {@code /uploads/**} disk resource handler was
 * removed so lesson/library blobs cannot be reached via static URLs.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    // Intentionally empty — no /uploads/** resource handler.
}
