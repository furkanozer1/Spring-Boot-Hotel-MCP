package com.mcp.spring_boot.params;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Payload for  /royal/hotel/search-by-location  endpoint.
 * feedId and locationId are injected by the service layer;
 * everything else comes from the caller.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LocationHotelSearchRequest {

    /** Format: YYYY-MM-DD */
    private String checkIn;

    /** Format: YYYY-MM-DD */
    private String checkOut;

    private String clientNationality;

    private List<HotelSearchParams.Room> rooms;          // reuse the same RoomParam class you already have

    private Boolean allPricesFlag;

    // set internally ─ not exposed to the outside world
    private String feedId;
    private Integer locationId;

    /** Pagination */
    private Integer limit;
    private Integer offset;
}
