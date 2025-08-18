# Adapt Your Existing APIs to AI with a Spring-Boot MCP Server

The rise of agentic AI puts a spotlight on one thing above all: context—and that’s exactly where the Model Context Protocol (MCP) helps. MCP, an open standard from Anthropic, gives models a safe, uniform way to discover and call tools; with first-class support in Claude, ChatGPT, Gemini, and Perplexity, it’s fast becoming the default bridge between agents and real-world APIs. Crucially, your backend does not need to be Spring Boot: a Spring Boot–based MCP server simply acts as an adapter. It uses WebClient (or any client you prefer) to call existing APIs—REST, GraphQL, even gRPC/SOAP via appropriate clients—and exposes small, typed @Tool methods over SSE. In my ETSTUR internship, I built exactly this: a Spring Boot MCP adapter on top of ETSTUR’s existing hotel APIs that enables hotel search by common criteria (location, dates, guests, and filters.**The MCP server repo can be found at the end of the document**). You wrap current business logic (wherever it lives), call internal or vendor endpoints, and publish the SSE endpoint so any MCP-capable client can use them. In practice, that lets models search, fetch details, and execute actions across heterogeneous backends—without leaking vendor-specific payloads into prompts. This guide shows how to turn existing endpoints into AI-callable tools using Spring Boot + Spring AI MCP Server (SSE) + WebClient, following a practical project structure that exposes current integrations without rewrites—just a thin adapter layer the agent can call via MCP.

---

## Table of Contents

- [Why adapt existing APIs?](#why-adapt-existing-apis)
- [Architecture at a glance](#architecture-at-a-glance)
- [Minimal setup](#minimal-setup)
- [Project structure (suggested)](#project-structure-suggested)
- [1) DTO: translating "human asks" into code](#1-dto-translating-human-asks-into-code)
- [2) @Tool: the thin adapter surface](#2-tool-the-thin-adapter-surface)
- [3) Helper: keep vendor specifics out of prompts](#3-helper-keep-vendor-specifics-out-of-prompts)
- [4) WebClient: centralized configuration](#4-webclient-centralized-configuration)
- [How the LLM Chat Flow Works with the Hotel MCP](#how-the-llm-chat-flow-works-with-the-hotel-mcp)
- [Key Idea](#key-idea)
- [Error handling and Testing Tips](#error-handling-and-testing-tips)
- [What is n8n?](#what-is-n8n)
- [Agentic AI in practice: orchestrating tools with n8n](#agentic-ai-in-practice-orchestrating-tools-with-n8n)
- [Error handling & DX tips](#error-handling--dx-tips)
- [Conclusion](#conclusion)
- [GITHUB Link](#github-link)

---

## Why adapt existing APIs?

You already have domain logic and integrations that work. MCP lets agents **call** that logic safely without rebuilding anything.

- **Reuse** proven business logic and contracts
- **Type‑safety** between agent and tools (small DTOs over raw prompts)
- **Separation of concerns:** the tool surface stays tiny; vendor payloads live in helpers
- **Observability:** Spring logs + MCP inspector
- **Backend‑agnostic:** the adapter can call **any** API (REST/GraphQL over HTTP; gRPC/SOAP via proper clients)

---

## Architecture at a glance

```
(Agent/Client) ──(MCP over SSE)──> Spring Boot Application (Adapter)
                                     │
                                     ├─ @Tool methods (thin, typed DTOs)
                                     ├─ helper/service (payload building/parsing)
                                     └─ WebClient/clients → Existing or Vendor APIs
                                        ├─ REST / GraphQL over HTTP
                                        └─ gRPC / SOAP via appropriate clients
```

**Key idea:** don’t rewrite controllers or downstreams. The adapter exposes **human‑shaped** tool methods; helpers handle vendor specifics.

---

## Minimal setup

Before showing code, here’s the minimal configuration that turns the Spring app into an MCP server that streams tool calls over **SSE**, while `WebClient` handles outbound requests to existing endpoints.

**Dependencies (conceptual):**

- Spring Boot 3.x
- Spring WebFlux (`WebClient`)
- Spring AI MCP Server (SSE)

**`application.properties` (example):**

```properties
spring.ai.mcp.server.enabled=true
spring.ai.mcp.server.type=ASYNC
spring.ai.mcp.server.inspector.enabled=true

# Example downstream; can be any HTTP API
vendor.api.base-url=https://api.vendor.example
vendor.api.auth-token=${VENDOR_TOKEN}

logging.level.com.example=DEBUG
```

> Use environment variables for secrets; avoid logging body payloads in production.

---

## Project structure (suggested)

This structure keeps the tool surface clean and the vendor logic isolated.

```
src/main/java/com/example/mcp/
├─ McpApplication.java                 # Spring Boot starter
├─ config/
│  └─ WebClientConfig.java             # WebClient bean & filters
├─ params/
│  └─ LocationHotelSearchParams.java   # Human‑friendly DTOs
├─ service/
│  ├─ SearchTools.java                 # @Tool methods (thin adapter)
│  └─ SearchMethods.java               # Helper: build payloads, call, parse
└─ resources/
   └─ application.properties
```

> Names are illustrative; the same pattern applies to any domain.

---

## 1) DTO: translating “human asks” into code

Before the first code block, understand the goal: we want a tiny parameter object that an agent can fill easily (city, dates, party), without exposing vendor field names. This keeps prompts clean and adapters simple.

```java
// src/main/java/com/example/mcp/params/LocationHotelSearchParams.java
package com.example.mcp.params;

import java.util.List;

public record LocationHotelSearchParams(
    String city,
    String checkIn,
    String checkOut,
    String clientNationality,
    List<Room> rooms,
    Integer limit,
    Integer offset,
    Boolean allPricesFlag
) {
  public record Room(int adults, List<Integer> childrenAges) {}
}
```

---

## 2) `@Tool`: the thin adapter surface

This method is what the agent calls. It accepts the DTO, delegates to the helper, and returns a **readable string or compact JSON**. The point is to keep this surface minimal and human‑oriented while the helper handles the real work.

```java
// src/main/java/com/example/mcp/service/SearchTools.java
package com.example.mcp.service;

import com.example.mcp.params.LocationHotelSearchParams;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class SearchTools {

  private final SearchMethods methods;

  public SearchTools(SearchMethods methods) {
    this.methods = methods;
  }

  @Tool(
    name = "hotel_search_by_location",
    description = "Search hotels by city, date range, and party. City may be in natural language."
  )
  public String searchByCity(LocationHotelSearchParams params) {
    Long locationId = methods.resolveCityToLocationId(params.city());
    var payload = methods.buildLocationSearchPayload(locationId, params);
    return methods.searchHotels(payload); // string or compact JSON
  }
}
```

---

## 3) Helper: keep vendor specifics out of prompts

We handle all outgoing requests and parsing here, so the tool method stays clean while this helper deals with fragile payloads and errors. This is where the APIs are called.

```java
// src/main/java/com/example/mcp/service/SearchMethods.java
package com.example.mcp.service;

import com.example.mcp.params.LocationHotelSearchParams;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class SearchMethods {

  private final WebClient vendor;

  public SearchMethods(WebClient vendor) {
    this.vendor = vendor;
  }

  public Long resolveCityToLocationId(String city) {
    var body = Map.of("query", city, "type", List.of("CITY"));
    Map<?, ?> res = vendor.post()
        .uri("/content-service/autocomplete/search")
        .bodyValue(body)
        .retrieve()
        .bodyToMono(Map.class)
        .block();

    return JsonUtils.pickFirstCityId(res); // defensive parsing (null/shape checks)
  }

  public Map<String, Object> buildLocationSearchPayload(Long locationId, LocationHotelSearchParams p) {
    return VendorPayloads.locationSearch(locationId, p); // validate dates, party, pagination here
  }

  public String searchHotels(Map<String, Object> payload) {
    return vendor.post()
        .uri("/hotel/search")
        .bodyValue(payload)
        .retrieve()
        .onStatus(HttpStatusCode::isError, r ->
            r.bodyToMono(String.class).flatMap(msg -> Mono.error(new RuntimeException(msg))))
        .bodyToMono(String.class)
        .block();
  }
}
```

> Keep JSON parsing strict and defensive. Return **clear, model‑readable** errors instead of stack traces.

---

## 4) WebClient: centralized configuration

We keep this in a separate config bean so we can set shared headers, the base URL, simple logging, and easily mock it in tests (e.g., WireMock).

```java
// src/main/java/com/example/mcp/config/WebClientConfig.java
package com.example.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

  @Bean
  public WebClient vendorClient(
      @Value("${vendor.api.base-url}") String baseUrl,
      @Value("${vendor.api.auth-token}") String token
  ) {
    return WebClient.builder()
        .baseUrl(baseUrl)
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .filter(logBasic())
        .build();
  }

  private ExchangeFilterFunction logBasic() {
    return (request, next) -> {
      // Log method + path (mask sensitive headers); add correlation IDs if needed
      return next.exchange(request);
    };
  }
}
```

---

## How the LLM Chat Flow Works with the Hotel MCP

When a user searches for hotels, the system doesn’t force them to copy hotel codes around.  
Instead, the **client (and if needed, the server)** keeps a lightweight memory of the conversation.  
This way, the user can talk naturally, and the system fills in the missing technical details.

Example Flow

- User: _“Find family-friendly hotels in Kayseri, 2025-09-12 → 2025-09-15, 2 adults + 1 child.”_  
  → The system calls **`hotel_search_by_location`**  
  → Saves the travel dates, party size, and the list of hotel codes in context.

- User: _“Show me the second one’s details.”_  
  → The system calls **`hotel_details`**  
  → Automatically pulls the right hotel code from context.

- User: _“List its facilities.”_  
  → The system calls **`hotel_facility_check`**  
  → Same hotel code is reused from context.

- User: _“Give me a reservation link for those dates.”_  
  → The system calls **`hotel_reservation`**  
  → Reuses both the dates and the hotel code from context.

## Key Idea

- **Typed parameters** hide messy vendor fields, so the user only deals with clean, simple inputs.
- The system automatically fills in missing info (like dates or hotel codes) when needed.
- This makes the chat flow feel natural and easy, while MCP quietly does the hard work in the background.
- The real power of using APIs as MCP servers is that LLMs can **understand the context** and then make or complete requests automatically.

## Error handling and Testing Tips

- Install the MCP inspector with:

  ```bash
  npx @modelcontextprotocol/inspector

  ```

- Run the command — it will start a local web page (usually at http://localhost:5173).
- Open the page in your browser.
- Enter your sse mcp server link (usually at http://localhost:8080/sse)
- You can see the available tools, fill in request bodies, and test your MCP server directly.

---

## What is n8n?

**n8n** is an open-source, node-based **workflow automation** platform. You build flows visually by connecting **nodes** (HTTP, databases, queues, email, SaaS apps, etc.). Recently, n8n added **AI Agent** capabilities so a model can **plan**, **choose tools**, **call them**, and **react** to outcomes—all inside a workflow. For custom backends, n8n offers an **MCP Client Tool** so your agent can discover and invoke your **MCP tools** (the `@Tool` methods you expose in Spring) alongside 500+ ready nodes. The result: your LLM can operate your existing APIs, orchestrate multi-step tasks, and hand off to other services—without bespoke glue code.

---

## Agentic AI in practice: orchestrating tools with n8n

Agents rarely do a single call and stop. They usually:

1. plan what to do,
2. call a tool,
3. read the result,
4. decide the next step.

The **AI Agent** node in n8n is like a small conductor for this. It lets the model pick tools, run them in order, branch if needed, and keep state. With the **MCP Client Tool**, your Spring MCP server shows up as a set of tools the agent can call—right next to built‑in nodes.

**Why use n8n?**

- **Picks the right tool:** The agent chooses which tool to run based on your tool names and JSON schemas.
- **Easy chaining:** Connect your MCP tools with DB, HTTP, queues, email, Slack, etc.
- **Low setup:** Point the MCP Client Tool at your server URL, add auth, click **Discover tools**.

**Quickstart (connect Spring MCP → n8n)**

1. Start your MCP server (SSE or HTTP streaming).
2. In n8n add **AI Agent → Tools Agent**.
3. Add a **Tool** under it → choose **MCP Client Tool**.

   - **Server URL:** your MCP endpoint (e.g., `https://your-host/sse` or `/stream`)
   - **Auth:** set a _Bearer_ token or custom header that your server expects

4. Click **Discover tools** (n8n pulls your `@Tool` list and schemas).
5. Add a user instruction, e.g.:

   > “Find family‑friendly hotels in Kayseri for 2025‑09‑12 → 2025‑09‑15 (2 adults + 1 child) and then show details for the second result.”

6. Run the flow. The agent will call `hotel_search_by_location`, read the result, and then call `hotel_details` as needed.

**Other use cases**

- **Ops agent:** one MCP that wraps deploy/CI/incident actions. n8n schedules runs and posts Slack/Jira updates.
- **Data concierge:** MCP for search/BI; combine with n8n’s DB nodes to build quick reports.
- **Vendor unifier:** one MCP over many suppliers; compare prices and trigger purchases in one flow.

---

## Error handling & DX tips

- **MCP Inspector** during development to view tools, send test requests, and inspect responses:

  ```bash
  npx @modelcontextprotocol/inspector
  ```

  Open the local UI, paste your server URL, and (optionally) a bearer token.

- Return **friendly strings or small JSON**—avoid stack traces; include `errorCode` and `hint`.
- Add **correlation IDs**; log method/path and non-2xx bodies (mask PII).
- **Paginate** long results (limit/offset) and, if helpful, stream summarized chunks.
- Prefer **small, composable tools** over a single “god” tool.

---

## Conclusion

In conlusion, by treating Spring Boot as a thin MCP adapter—small @Tool surfaces, helper services that hide vendor quirks, and a centralized WebClient—you turn today’s APIs into AI-callable capabilities without rewrites. Pairing this with n8n’s AI Agent + MCP Client Tool lets models orchestrate real work end-to-end alongside 500+ nodes (DB, HTTP, Slack, queues). Start tiny: ship one task (e.g., “hotel_search_by_location”), wire up the MCP Inspector, add pagination and clear error codes, then grow a toolbox of focused actions. You’ll keep your contracts, observability, and security controls intact—while unlocking agentic flows across Claude/ChatGPT/Gemini/Perplexity. In short: adapt, don’t rebuild; instrument, don’t guess; and iterate tool-by-tool until your agents run your business logic reliably in production.

## GITHUB Link

## claude config file

{
"mcpServers": {
"spring-boot-hotel-mcp": {
"command": "npx",
"args": [
"-y",
"mcp-remote",
"http://localhost:8080/sse"
]
}
}
}
