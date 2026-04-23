package pl.training.springai.chat.tool;

import org.springframework.context.annotation.Description;
import pl.training.springai.chat.model.DoubleValue;

import java.util.function.Function;

@Description("Calculates the square of a number (value * value)")
public class PowerTool implements Function<DoubleValue, Double> {

    @Override
    public Double apply(DoubleValue doubleValue) {
        return doubleValue.value() * doubleValue.value();
    }

}