package ru.practicum.stats.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.MockServerRestTemplateCustomizer;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class StatsClientTest {
    private static final LocalDateTime START = LocalDateTime.of(2025, 1, 1, 10, 0);
    private static final LocalDateTime END = START.plusHours(1);
    private static final String STATS_URL = "http://stats:9090/stats"
            + "?start=2025-01-01%2010%3A00%3A00&end=2025-01-01%2011%3A00%3A00";

    private StatsClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        MockServerRestTemplateCustomizer customizer = new MockServerRestTemplateCustomizer();
        client = new StatsClient(new RestTemplateBuilder(customizer), "http://stats:9090/");
        server = customizer.getServer();
    }

    @Test
    void sendsHitWithExpectedTimestampFormat() {
        server.expect(requestTo("http://stats:9090/hit"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"app\": \"main\", \"uri\": \"/events/1\", \"ip\": \"127.0.0.1\", \"timestamp\": \"2025-01-01 10:00:00\"}"))
                .andRespond(withStatus(HttpStatus.CREATED));

        client.hit(new EndpointHit(null, "main", "/events/1", "127.0.0.1", START));

        server.verify();
    }

    @Test
    void encodesQueryValuesOnceAndDeserializesCounts() {
        server.expect(requestTo(STATS_URL + "&unique=true&uris=%2Fevents%2F1"
                        + "&uris=%2Fevents%2F2%3Fx%3Da%2Bb%26y%3Dc%20d"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"app":"main","uri":"/events/1","hits":3}]
                        """, MediaType.APPLICATION_JSON));

        List<ViewStats> result = client.getStats(START, END,
                List.of("/events/1", "/events/2?x=a+b&y=c d"), true);

        assertThat(result).containsExactly(new ViewStats("main", "/events/1", 3L));
        server.verify();
    }

    @Test
    void omitsUrisWhenNoFilterWasProvided() {
        server.expect(requestTo(STATS_URL + "&unique=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getStats(START, END, null, false)).isEmpty();
        server.verify();
    }

    @Test
    void omitsUrisForEmptyList() {
        server.expect(requestTo(STATS_URL + "&unique=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.getStats(START, END, List.of(), false)).isEmpty();
        server.verify();
    }

    @Test
    void doesNotHideServerErrorOnStatsRequest() {
        server.expect(requestTo(STATS_URL + "&unique=false"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.getStats(START, END, null, false))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void doesNotHideServerErrorOnHitRequest() {
        server.expect(requestTo("http://stats:9090/hit"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.hit(new EndpointHit(null, "main", "/events/1", "::1", START)))
                .isInstanceOf(HttpServerErrorException.class);
        server.verify();
    }

    @Test
    void rejectsMissingStatsBodyInsteadOfReportingZeroViews() {
        server.expect(requestTo(STATS_URL + "&unique=false"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getStats(START, END, null, false))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("пустое тело");
        server.verify();
    }
}
