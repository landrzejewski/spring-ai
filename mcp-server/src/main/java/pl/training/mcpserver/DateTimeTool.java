package pl.training.mcpserver;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

@Component
public class DateTimeTool {

    @McpTool(name = "getCurrentTime", description = "Get the current date and time in the server's timezone")
    public String getCurrentTime() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toString();
    }

    @McpTool(name = "daysUntil", description = "Count the number of days between today and a given date")
    public String daysUntil(
            @McpToolParam(description = "Target date in ISO format, e.g. 2026-12-24", required = true)
            String targetDate) {
        try {
            var days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(targetDate));
            return "%d days until %s".formatted(days, targetDate);
        }
        catch (DateTimeParseException exception) {
            // A RuntimeException reaches the model as an error result, so it can correct itself
            // and call the tool again with a properly formatted date.
            throw new IllegalArgumentException("'%s' is not an ISO date (expected yyyy-MM-dd)".formatted(targetDate));
        }
    }

    @McpTool(name = "listTimezones", description = "List the available timezone identifiers matching a prefix")
    public java.util.List<String> listTimezones(
            @McpToolParam(description = "Prefix to filter by, e.g. Europe", required = true)
            String prefix) {
        return ZoneId.getAvailableZoneIds().stream()
                .filter(zone -> zone.startsWith(prefix))
                .sorted()
                .limit(20)
                .toList();
    }

}