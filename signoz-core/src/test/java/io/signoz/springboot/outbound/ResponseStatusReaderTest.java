package io.signoz.springboot.outbound;

import org.junit.jupiter.api.Test;

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
}
