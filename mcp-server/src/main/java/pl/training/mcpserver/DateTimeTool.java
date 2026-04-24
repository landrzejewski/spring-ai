package pl.training.mcpserver;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class DateTimeTool {

    @Tool(description = "Get the current date and time in the user's timezone", returnDirect = true)
    public String getCurrentTime() {
        return LocalDateTime.now().atZone(ZoneId.systemDefault()).toString();
    }

}
