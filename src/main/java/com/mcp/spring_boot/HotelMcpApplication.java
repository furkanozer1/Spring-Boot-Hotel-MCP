package com.mcp.spring_boot;


import com.mcp.spring_boot.service.HotelSearchService;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
public class HotelMcpApplication {

	public static void main(String[] args) {
		SpringApplication.run(HotelMcpApplication.class, args);
	}

	@Bean
	public List<ToolCallback> hotelTools(HotelSearchService tool1) {
		return List.of(ToolCallbacks.from(tool1));
	}


}
