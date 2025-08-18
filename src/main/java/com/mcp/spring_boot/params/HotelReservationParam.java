package com.mcp.spring_boot.params;

public class HotelReservationParam {
    @lombok.Getter
    @lombok.Setter
    private String hotelCode;

    
    @lombok.Getter
    @lombok.Setter
    private String checkIn;

    @lombok.Getter
    @lombok.Setter
    private String checkOut;
    
}
