package dev.riloox.freecam;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;

public class FreecamSpeedArgumentType extends SingleArgumentType<Integer> {

    public FreecamSpeedArgumentType() {
        super("speed", "Freecam speed (1-10)");
    }

    @Override
    public Integer parse(String input, ParseResult result) {
        try {
            int value = Integer.parseInt(input);
            if (value < 1 || value > 10) {
                result.fail(Message.raw("Speed must be between 1 and 10."));
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
