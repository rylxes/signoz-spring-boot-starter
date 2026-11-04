package io.signoz.e2e.app.reactive;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** The demo service on Spring WebFlux. */
@SpringBootApplication(scanBasePackages = {"io.signoz.e2e.app.common", "io.signoz.e2e.app.reactive"})
public class ReactiveApp {

    public static void main(String[] args) {
        new SpringApplicationBuilder(ReactiveApp.class).web(WebApplicationType.REACTIVE).run(args);
    }
}
