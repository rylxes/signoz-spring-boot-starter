package io.signoz.springboot.web;

import io.signoz.springboot.TestSigNozApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for multipart uploads while request-body logging is enabled.
 *
 * <p>Uses a REAL embedded servlet container ({@code webEnvironment = RANDOM_PORT})
 * so that multipart parts are parsed from the raw request stream by Tomcat — the
 * same path that breaks when {@link HttpLoggingFilter} wraps the request in a
 * body-caching wrapper that does not support {@code getParts()}. A {@code MockMvc}
 * test would NOT reproduce this because {@code MockMultipartHttpServletRequest}
 * has pre-parsed parts.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestSigNozApplication.class, HttpLoggingFilterMultipartTest.UploadController.class},
        properties = {
                "signoz.web.log-request-body=true"
        })
@ActiveProfiles("test")
class HttpLoggingFilterMultipartTest {

    @RestController
    static class UploadController {
        @PostMapping("/upload")
        public String upload(@RequestParam("file") MultipartFile file) throws IOException {
            return "received:" + new String(file.getBytes(), StandardCharsets.UTF_8);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    public void multipartUploadSucceedsWhenRequestBodyLoggingEnabled() {
        String csv = "id,amount\n1,100";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "upload.csv";
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = rest.postForEntity(
                "http://localhost:" + port + "/upload",
                new HttpEntity<>(body, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("multipart 'file' part must survive request-body logging")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("received:" + csv);
    }
}
