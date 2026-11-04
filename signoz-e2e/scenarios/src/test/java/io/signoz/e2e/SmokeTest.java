package io.signoz.e2e;

import io.signoz.e2e.support.Await;
import io.signoz.e2e.support.Http;
import io.signoz.e2e.support.OtlpReceiver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The harness itself: two services start, call each other, log JSON and export to the receiver. */
class SmokeTest extends E2eTestBase {

    @Test
    void twoServicesStartCallEachOtherAndExport() throws Exception {
        Pair pair = startPair(Options.servlet());

        Http.Response hello = Http.get(pair.a.baseUrl() + "/e2e/hello?name=smoke");
        assertThat(hello.status).isEqualTo(200);
        assertThat(hello.body).isEqualTo("hello smoke");
        assertThat(Http.get(pair.a.baseUrl() + "/e2e/chain/rest").status).isEqualTo(200);

        pair.a.awaitJson(messageContains("hello smoke"), 10_000, "JSON hello line");
        Await.until(() -> receiver().logs().stream().anyMatch(l -> "hello smoke".equals(l.body)), 15_000,
                "exported hello log; got " + receiver().logs());
        assertHealthy(pair.a);
        assertHealthy(pair.b);
    }
}
