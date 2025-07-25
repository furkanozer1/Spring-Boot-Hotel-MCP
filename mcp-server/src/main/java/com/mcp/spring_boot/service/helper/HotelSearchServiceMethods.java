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
import org.springframework.beans.factory.annotation.Qualifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

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
    private final ObjectMapper objectMapper;

    public HotelSearchServiceMethods(@Qualifier("etscoreWebClient") WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
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

    /**
     * Extracts facility names from ETS Score API hotel detail response.
     *
     * @param response JSON response from ETS Score API
     * @return list of facility names
     */
    public List<String> extractFacilitiesFromResponse(String response) {
        List<String> facilityNames = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode detail = root.path("detail");
            JsonNode facilityGroups = detail.path("facilityGroups");
            
            if (facilityGroups.isArray()) {
                for (JsonNode group : facilityGroups) {
                    JsonNode facilities = group.path("facilities");
                    if (facilities.isArray()) {
                        for (JsonNode facility : facilities) {
                            String name = facility.path("name").asText();
                            if (!name.isEmpty()) {
                                facilityNames.add(name);
                            }
                        }
                    }
                }
            }
            
            log.debug("Extracted {} facilities from ETS response", facilityNames.size());
            
        } catch (Exception e) {
            log.error("Error parsing facilities from ETS response: {}", e.getMessage(), e);
        }
        
        return facilityNames;
    }

    /**
     * Extracts key hotel details from ETS Score API response.
     *
     * @param response JSON response from ETS Score API
     * @return formatted hotel details string
     */
    public String extractHotelDetailsFromResponse(String response) {
        StringBuilder details = new StringBuilder();

        try {
            JsonNode root   = objectMapper.readTree(response);
            JsonNode detail = root.path("detail");

            // ——————————————————————————————
            // 1) Hotel adı
            // ——————————————————————————————
            String hotelName = detail.path("hotelName").asText("");
            details.append("Hotel: ")
                .append(hotelName)
                .append("\n");

            // ——————————————————————————————
            // 2) Konum: city, stateProvinceName, country
            // ——————————————————————————————
            JsonNode loc = detail.path("location");
            String city    = loc.path("city").asText("");
            String state   = loc.path("stateProvinceName").asText("");
            String country = loc.path("country").asText("");
            details.append("Location: ")
                .append(city);
            if (!state.isEmpty())   details.append(", ").append(state);
            if (!country.isEmpty()) details.append(", ").append(country);
            details.append("\n");

            // ——————————————————————————————
            // 3) Adres satırları (contact.addressLines)
            // ——————————————————————————————
            JsonNode addressLines = detail.path("contact").path("addressLines");
            if (addressLines.isArray() && addressLines.size() > 0) {
                details.append("Address: ");
                for (int i = 0; i < addressLines.size(); i++) {
                    details.append(addressLines.get(i).asText());
                    if (i < addressLines.size() - 1) {
                        details.append(", ");
                    }
                }
                details.append("\n");
            }

            // ——————————————————————————————
            // 4) Koordinatlar (location.location.lat & lon)
            // ——————————————————————————————
            JsonNode coords = loc.path("location");
            if (!coords.isMissingNode()) {
                double lat = coords.path("lat").asDouble();
                double lon = coords.path("lon").asDouble();
                details.append("Coordinates: ")
                    .append(lat)
                    .append(", ")
                    .append(lon)
                    .append("\n");
            }

            // ——————————————————————————————
            // 5) Telefon (financialInfo.tel)
            // ——————————————————————————————
            String phone = detail.path("financialInfo").path("tel").asText("");
            if (!phone.isEmpty()) {
                details.append("Phone: ")
                    .append(phone)
                    .append("\n");
            }

            // ——————————————————————————————
            // 6) Yıldız / Tip (star)
            // ——————————————————————————————
            String star = detail.path("star").asText("");
            if (!star.isEmpty()) {
                details.append("Star: ")
                    .append(star)
                    .append("\n");
            }

            log.debug("Extracted hotel details for: {}", hotelName);

        } catch (Exception e) {
            log.error("Error parsing hotel details from response: {}", e.getMessage(), e);
            return "Error parsing hotel details";
        }

        return details.toString();
    }


    /**
     * Extracts image URLs from ETS Score API response.
     *
     * @param response JSON response from ETS Score API
     * @return list of image URLs
     */
    public List<String> extractImageUrlsFromResponse(String response) {
            List<String> urls = new ArrayList<>();
        try {
            JsonNode root   = objectMapper.readTree(response);
            JsonNode detail = root.path("detail");
            
            // 1) detail.images → imageUrls
            JsonNode images = detail.path("images");
            if (images.isArray()) {
                for (JsonNode imgBlock : images) {
                    JsonNode imgUrls = imgBlock.path("imageUrls");
                    if (imgUrls.isArray()) {
                        for (JsonNode urlNode : imgUrls) {
                            String url = urlNode.path("url").asText("");
                            if (!url.isEmpty()) {
                                urls.add(url);
                            }
                        }
                    }
                }
            }
            
            // 2) detail.rooms → her oda için imageLinks → imageUrls
            JsonNode rooms = detail.path("rooms");
            if (rooms.isArray()) {
                for (JsonNode room : rooms) {
                    JsonNode links = room.path("imageLinks");
                    if (links.isArray()) {
                        for (JsonNode linkBlock : links) {
                            JsonNode imgUrls = linkBlock.path("imageUrls");
                            if (imgUrls.isArray()) {
                                for (JsonNode urlNode : imgUrls) {
                                    String url = urlNode.path("url").asText("");
                                    if (!url.isEmpty()) {
                                        urls.add(url);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            log.debug("Extracted {} image URLs", urls.size());
        } catch (Exception e) {
            log.error("Error extracting image URLs: {}", e.getMessage(), e);
        }
        return urls;
    }

    /**
     * Extracts hotel description from ETS Score API response.
     *
     * @param response JSON response from ETS Score API
     * @return hotel description text
     */
    public String extractDescriptionFromResponse(String response) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonNode root  = objectMapper.readTree(response);
            JsonNode descs = root.at("/detail/descriptions");

            if (!descs.isMissingNode() && descs.isObject()) {
                // Her dil dizisini gez
                for (JsonNode langArray : descs) {
                    if (langArray.isArray()) {
                        for (JsonNode entry : langArray) {
                            String text = entry.path("description").asText();
                            if (!text.isEmpty()) {
                                // Önceki metin varsa iki satır boşlukla ayır
                                if (sb.length() > 0) {
                                    sb.append("\n\n");
                                }
                                sb.append(text);
                            }
                        }
                    }
                }
                log.debug("Extracted {} characters of descriptions", sb.length());
            } else {
                log.warn("/detail/descriptions bulunamadı veya beklenen formatta değil.");
            }
        } catch (Exception e) {
            log.error("Error parsing descriptions: {}", e.getMessage(), e);
        }
        return sb.toString();
    }


}
