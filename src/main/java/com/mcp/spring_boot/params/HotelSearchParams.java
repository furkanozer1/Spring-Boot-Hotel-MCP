package com.mcp.spring_boot.params;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HotelSearchParams {

    private List<String> hotelCodes;
    private String checkIn;
    private String checkOut;
    private String clientNationality;
    private String feedId;
    private List<Room> rooms;
    private String currency; // optional

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Room {
        private int adults;
        private int child;
        private List<Integer> childAges;
    }
}
