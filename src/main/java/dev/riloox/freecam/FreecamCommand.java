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
    private final OptionalArg<String> modeArg;

    public FreecamCommand(String name, String description, FreecamService freecamService) {
        super(name, description);
        this.freecamService = freecamService;
        this.speedArg = withOptionalArg("speed", "Freecam speed (1-10)", new FreecamSpeedArgumentType());
        this.modeArg = withOptionalArg("mode", "Optional mode (tripod/lock/unlock)", new FreecamModeArgumentType());
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
        boolean wasTripodActive = freecamService.isTripodActive(playerRef.getUuid());

        String rawInput = context.getInputString();
        Integer parsedSpeed = parseSpeedInput(rawInput);
        Boolean lockInput = parseLockInput(rawInput);
        Boolean tripodInput = parseTripodInput(rawInput);
        String modeInput = context.provided(modeArg) ? context.get(modeArg) : null;
        if (parsedSpeed != null) {
            speed = parsedSpeed;
            hasSpeedArg = true;
        }
        if (modeInput != null && !modeInput.isBlank()) {
            Boolean modeTripod = parseTripodInput(modeInput);
            Boolean modeLock = parseLockInput(modeInput);
            if (modeTripod != null) {
                tripodInput = modeTripod;
            }
            if (modeLock != null) {
                lockInput = modeLock;
            }
        }

        if (lockInput != null) {
            boolean locked = freecamService.setLookLocked(playerRef, world, lockInput);
            context.sendMessage(Message.raw(locked ? "Freecam look lock enabled." : "Freecam look lock disabled."));
        }

        if (Boolean.TRUE.equals(tripodInput)) {
            if (!wasActive && !wasTripodActive) {
                context.sendMessage(Message.raw("Tripod can only be enabled from freecam."));
                return;
            }
            boolean enabled = freecamService.toggleTripod(playerRef, world, store, entityRef);
            context.sendMessage(Message.raw(enabled ? "Tripod enabled." : "Tripod disabled."));
            return;
        }

        if (hasSpeedArg) {
            if (speed == null) {
                speed = context.get(speedArg);
            }
            if (speed != null) {
                freecamService.setSpeed(playerRef, world, speed);
                context.sendMessage(Message.raw("Freecam speed set to " + speed + "."));
            } else {
                hasSpeedArg = false;
            }
        }

        boolean toggled = false;
        boolean enabled = wasActive;
        if (hasSpeedArg && speed != null && !wasActive) {
            enabled = freecamService.toggle(playerRef, world, store, entityRef);
            toggled = true;
        } else if (!hasSpeedArg) {
            if (lockInput == null) {
                enabled = freecamService.toggle(playerRef, world, store, entityRef);
                toggled = true;
            } else if (!wasActive && Boolean.TRUE.equals(lockInput)) {
                enabled = freecamService.toggle(playerRef, world, store, entityRef);
                toggled = true;
            }
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

    private static Boolean parseLockInput(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        for (String token : tokens) {
            if ("lock".equalsIgnoreCase(token) || "--lock".equalsIgnoreCase(token)) {
                return true;
            }
            if ("unlock".equalsIgnoreCase(token) || "--unlock".equalsIgnoreCase(token)) {
                return false;
            }
        }
        return null;
    }

    private static Boolean parseTripodInput(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        String[] tokens = input.trim().split("\\s+");
        for (String token : tokens) {
            if ("tripod".equalsIgnoreCase(token) || "trip".equalsIgnoreCase(token) || "t".equalsIgnoreCase(token)) {
                return true;
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
