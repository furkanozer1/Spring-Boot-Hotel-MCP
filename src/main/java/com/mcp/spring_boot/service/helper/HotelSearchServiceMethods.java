package com.mcp.spring_boot.service.helper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Stream;

/**
 * Helper for hotel-related look-ups that do not belong in the main service.
 *
 * <p>Current feature: fetch the first <em>CITY</em>-type <code>locationId</code>
 * from the autocomplete API by free-text query (e.g., “Kayseri”).</p>
 */
@Component
@SuppressWarnings("unchecked")
public class HotelSearchServiceMethods {

    private static final Logger log = LoggerFactory.getLogger(HotelSearchServiceMethods.class);

    private static final String AUTOCOMPLETE_PATH =
            "/content-service/autocomplete/search";

    private final WebClient webClient;

    public HotelSearchServiceMethods(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Retrieves the first CITY-type location ID that matches the given query.
     *
     * @param query city name or search keyword (case-insensitive)
     * @return the numeric location ID, or {@code null} if none found or on error
     */
    public Integer getLocationIdByQuery(String query) {

        // ---------- 1. Build request body ----------
        Map<String, Object> requestBody = Map.of(
                "query", query,
                "language", "tr",
                "size", 30
        );

        log.debug("⌕ [Autocomplete] POST {} – body: {}", AUTOCOMPLETE_PATH, requestBody);

        try {
            // ---------- 2. Execute HTTP call ----------
            Map<String, Object> response =
                    webClient.post()
                             .uri(AUTOCOMPLETE_PATH)
                             .bodyValue(requestBody)
                             .retrieve()
                             // log non-2xx responses with body content
                             .onStatus(HttpStatusCode::isError, r ->
                                     r.bodyToMono(String.class)
                                      .flatMap(body -> {
                                          log.error("✖ [Autocomplete] {} – body: {}",
                                                    r.statusCode(), body);
                                          return Mono.error(new IllegalStateException(
                                                  "Autocomplete returned non-success status"));
                                      }))
                             .bodyToMono(Map.class)
                             .doOnNext(res -> log.debug("✓ [Autocomplete] raw response: {}", res))
                             .block();

            // ---------- 3. Validate & parse ----------
            if (response == null || !response.containsKey("items")) {
                log.warn("⚠ [Autocomplete] Missing 'items' array in response");
                return null;
            }

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) response.get("items");

            Optional<Integer> firstCityId =
                    items.stream()
                         // each item may have a "locations" array
                         .flatMap(item -> {
                             List<Map<String, Object>> locations =
                                     (List<Map<String, Object>>) item.get("locations");
                             return locations != null
                                     ? locations.stream()
                                     : Stream.empty();          // ← never return null Stream
                         })
                         .filter(loc -> "CITY".equals(loc.get("locationType")))
                         .map(loc -> {
                             try {
                                 return Integer.valueOf(loc.get("id").toString());
                             } catch (Exception ex) {
                                 log.error("⚠ [Autocomplete] Cannot parse location id: {}",
                                           loc.get("id"), ex);
                                 return null;                 // skip malformed id
                             }
                         })
                         .filter(Objects::nonNull)
                         .findFirst();

            Integer result = firstCityId.orElse(null);
            log.debug("→ [Autocomplete] selected locationId={} for query='{}'", result, query);
            return result;

        } catch (WebClientResponseException ex) {
            log.error("✖ [Autocomplete] HTTP error: {} – {}",
                      ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            log.error("✖ [Autocomplete] Unexpected error: {}", ex.getMessage(), ex);
        }

        return null;
    }
}
