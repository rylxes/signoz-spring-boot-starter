package io.signoz.springboot.outbound;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fake response types below mirror the shapes the real Spring types take across versions,
 * which a single test classpath cannot load side by side.
 */
class ResponseStatusReaderTest {

    /** Public status type, like {@code HttpStatusCode}. */
    public interface StatusCode {
        int value();
    }

    /** Non-public implementation, like Spring 6's {@code DefaultHttpStatusCode}. */
    static final class HiddenStatusCode implements StatusCode {
        private final int value;

        HiddenStatusCode(int value) {
            this.value = value;
        }

        @Override
        public int value() {
            return value;
        }
    }

    /** Spring 7: only the typed status, no raw method. */
    public interface TypedOnlyResponse {
        StatusCode getStatusCode();
    }

    /** Spring 5 and 6: both methods. */
    public interface RawAndTypedResponse {
        int getRawStatusCode();

        StatusCode getStatusCode();
    }

    @Test
    void readsTheTypedStatusWhenTheRawMethodIsGone() {
        ResponseStatusReader reader =
                new ResponseStatusReader(TypedOnlyResponse.class, "getRawStatusCode", "getStatusCode");

        TypedOnlyResponse response = () -> new HiddenStatusCode(201);

        assertThat(reader.read(response)).isEqualTo(201);
    }

    @Test
    void prefersTheRawStatusWhichSpring5CanReadForNonStandardCodes() {
        ResponseStatusReader reader =
                new ResponseStatusReader(RawAndTypedResponse.class, "getRawStatusCode", "getStatusCode");

        RawAndTypedResponse response = new RawAndTypedResponse() {
            @Override
            public int getRawStatusCode() {
                return 299;
            }

            @Override
            public StatusCode getStatusCode() {
                // What Spring 5's HttpStatus.valueOf does for a code it does not know.
                throw new IllegalArgumentException("No matching constant for [299]");
            }
        };

        assertThat(reader.read(response)).isEqualTo(299);
    }

    @Test
    void fallsBackToTheTypedStatusWhenTheRawMethodThrows() {
        ResponseStatusReader reader =
                new ResponseStatusReader(RawAndTypedResponse.class, "getRawStatusCode", "getStatusCode");

        RawAndTypedResponse response = new RawAndTypedResponse() {
            @Override
            public int getRawStatusCode() {
                throw new IllegalStateException("closed");
            }

            @Override
            public StatusCode getStatusCode() {
                return new HiddenStatusCode(204);
            }
        };

        assertThat(reader.read(response)).isEqualTo(204);
    }

    @Test
    void returnsUnknownInsteadOfThrowing() {
        ResponseStatusReader reader =
                new ResponseStatusReader(TypedOnlyResponse.class, "getRawStatusCode", "getStatusCode");
        TypedOnlyResponse failing = () -> {
            throw new IllegalStateException("closed");
        };

        assertThat(reader.read(failing)).isEqualTo(ResponseStatusReader.UNKNOWN);
        assertThat(reader.read(null)).isEqualTo(ResponseStatusReader.UNKNOWN);
        assertThat(new ResponseStatusReader(Object.class, "missing", "alsoMissing").read(new Object()))
                .isEqualTo(ResponseStatusReader.UNKNOWN);
    }

    // The real Spring 5 types this module compiles against, read the way the outbound clients do.

    private static final ResponseStatusReader CLIENT_RESPONSE =
            new ResponseStatusReader(ClientResponse.class, "rawStatusCode", "statusCode");
    private static final ResponseStatusReader CLIENT_HTTP_RESPONSE =
            new ResponseStatusReader(ClientHttpResponse.class, "getRawStatusCode", "getStatusCode");

    @Test
    void readsAWebClientResponseStatus() {
        assertThat(CLIENT_RESPONSE.read(ClientResponse.create(HttpStatus.CREATED).build())).isEqualTo(201);
        // Spring 5's statusCode() throws for a code with no HttpStatus constant.
        assertThat(CLIENT_RESPONSE.read(ClientResponse.create(499, ExchangeStrategies.withDefaults()).build()))
                .isEqualTo(499);
    }

    @Test
    void readsARestTemplateResponseStatus() {
        assertThat(CLIENT_HTTP_RESPONSE.read(new MockClientHttpResponse(new byte[0], HttpStatus.CREATED)))
                .isEqualTo(201);
        assertThat(CLIENT_HTTP_RESPONSE.read(new MockClientHttpResponse(new byte[0], 499))).isEqualTo(499);
    }
}
