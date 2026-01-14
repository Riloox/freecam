package dev.riloox.freecam;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;

public class FreecamCommand extends AbstractPlayerCommand {

    private final FreecamService freecamService;
    private final OptionalArg<Integer> speedArg;

    public FreecamCommand(String name, String description, FreecamService freecamService) {
        super(name, description);
        this.freecamService = freecamService;
        this.speedArg = withOptionalArg("speed", "Freecam speed (1-10)", new FreecamSpeedArgumentType());
        addAliases("fc");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> entityRef,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        Integer speed = null;
        boolean hasSpeedArg = context.provided(speedArg);
        boolean wasActive = freecamService.isActive(playerRef.getUuid());

        String rawInput = context.getInputString();
        Integer parsedSpeed = parseSpeedInput(rawInput);
        if (parsedSpeed != null) {
            speed = parsedSpeed;
            hasSpeedArg = true;
        }

        if (hasSpeedArg) {
            if (speed == null) {
                speed = context.get(speedArg);
            }
            if (speed != null) {
                freecamService.setSpeed(playerRef, world, speed);
                context.sendMessage(Message.raw("Freecam speed set to " + speed + "."));
            } else {
                context.sendMessage(Message.raw("Speed must be a number between 1 and 10."));
                return;
            }
        }

        boolean toggled = false;
        boolean enabled = wasActive;
        if (hasSpeedArg && speed != null && !wasActive) {
            enabled = freecamService.toggle(playerRef, world, store, entityRef);
            toggled = true;
        } else if (!hasSpeedArg) {
            enabled = freecamService.toggle(playerRef, world, store, entityRef);
            toggled = true;
        }

        if (toggled) {
            context.sendMessage(Message.raw(enabled ? "Freecam enabled." : "Freecam disabled."));
        }
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        return true;
    }

    private static Integer parseSpeedInput(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        if (tokens.length >= 2) {
            Integer positional = parseSpeedValue(tokens[1]);
            if (positional != null) {
                return positional;
            }
        }
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if ("--speed".equalsIgnoreCase(token)) {
                if (i + 1 >= tokens.length) {
                    return null;
                }
                return parseSpeedValue(tokens[i + 1]);
            }
            if (token.toLowerCase().startsWith("--speed=")) {
                String value = token.substring("--speed=".length());
                return parseSpeedValue(value);
            }
        }
        return null;
    }

    private static Integer parseSpeedValue(String value) {
        try {
            int speed = Integer.parseInt(value);
            if (speed < 1 || speed > 10) {
                return null;
            }
            return speed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
