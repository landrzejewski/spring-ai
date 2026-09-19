package pl.training.tools;

import jdk.jfr.Description;
import pl.training.model.DoubleValue;

import java.util.function.Function;

@Description("Calculates the square of a number (value * value)")
public class PowerTool implements Function<DoubleValue, Double> {

    @Override
    public Double apply(DoubleValue doubleValue) {
        return doubleValue.value() * doubleValue.value();
    }

}
