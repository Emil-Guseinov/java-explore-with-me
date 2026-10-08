package ru.practicum.stats.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class StatsClient {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RestTemplate restTemplate;
    private final String serverUrl;

    public StatsClient(RestTemplateBuilder builder,
                       @Value("${stats-server.url:http://localhost:9090}") String serverUrl) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
        this.serverUrl = serverUrl.replaceAll("/+$", "");
    }

    public void hit(EndpointHit hit) {
        URI uri = UriComponentsBuilder.fromHttpUrl(serverUrl).path("/hit").build().toUri();
        restTemplate.postForEntity(uri, hit, Void.class);
    }

    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, boolean unique) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(serverUrl)
                .path("/stats")
                .queryParam("start", "{start}")
                .queryParam("end", "{end}")
                .queryParam("unique", unique);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("start", start.format(DATE_FORMAT));
        parameters.put("end", end.format(DATE_FORMAT));
        if (uris != null) {
            for (int i = 0; i < uris.size(); i++) {
                String name = "uri" + i;
                builder.queryParam("uris", "{" + name + "}");
                parameters.put(name, uris.get(i));
            }
        }
        URI uri = builder.encode().buildAndExpand(parameters).toUri();
        List<ViewStats> result = restTemplate.exchange(uri, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<ViewStats>>() {}).getBody();
        if (result == null) {
            throw new RestClientException("Сервис статистики вернул пустое тело ответа");
        }
        return result;
    }
}
