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