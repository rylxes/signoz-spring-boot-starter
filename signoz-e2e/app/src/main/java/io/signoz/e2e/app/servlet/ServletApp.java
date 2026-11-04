package io.signoz.e2e.app.servlet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** The demo service on Spring MVC. Started twice by the tests, as service A and service B. */
@SpringBootApplication(scanBasePackages = {"io.signoz.e2e.app.common", "io.signoz.e2e.app.servlet", "io.signoz.e2e.app.sqs"})
@EnableAsync
public class ServletApp {

    public static void main(String[] args) {
        SpringApplication.run(ServletApp.class, args);
    }
}
