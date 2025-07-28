package com.mcp.spring_boot.params;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationHotelSearchParams {
    private String city;
    private String checkIn;
    private String checkOut;
    private String clientNationality;
    private List<HotelSearchParams.Room> rooms;
    private Boolean allPricesFlag = true;
    private Integer limit;
    private Integer offset;
}


