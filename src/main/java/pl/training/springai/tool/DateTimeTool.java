package pl.training.springai.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.Logger;

// @Component
public class DateTimeTool {

    private final Logger logger = Logger.getLogger(DateTimeTool.class.getSimpleName());

    @Tool(description = "Get the current date and time in the user's timezone", returnDirect = true)
    public String getCurrentTime(ToolContext toolContext) {
        logger.info("User id: " + toolContext.getContext().get("userId"));
        logger.info("Tool: getCurrentTime()");
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toString();
    }

    @Tool(description = "Get the current date and time in the specified timezone"/*, returnDirect = true*/)
    public String getCurrentTimeWithTimezone(@ToolParam(description = "The IANA timezone identifier, e.g. 'Europe/Warsaw', 'America/New_York', 'Asia/Tokyo'") String timeZone) {
        logger.info("Tool: getCurrentTimeForTimezone");
        try {
            var zoneId = ZoneId.of(timeZone);
            var dateTime = ZonedDateTime.now(zoneId);
            return dateTime.toString();
        } catch (Exception e) {
            return "Invalid time zone "  + timeZone;
        }
    }

}
