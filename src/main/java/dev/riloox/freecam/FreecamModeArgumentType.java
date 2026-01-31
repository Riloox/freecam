package dev.riloox.freecam;

import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;

public class FreecamModeArgumentType extends SingleArgumentType<String> {

    public FreecamModeArgumentType() {
        super("mode", "Optional mode (tripod/lock/unlock)");
    }

    @Override
    public String parse(String input, ParseResult result) {
        return input;
    }
}
