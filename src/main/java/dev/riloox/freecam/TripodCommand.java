package dev.riloox.freecam;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import javax.annotation.Nonnull;

public class TripodCommand extends AbstractPlayerCommand {

    private final FreecamService freecamService;

    public TripodCommand(String name, String description, FreecamService freecamService) {
        super(name, description);
        this.freecamService = freecamService;
        addAliases("trip", "t");
    }

    @Override
    protected void execute(@Nonnull CommandContext context,
                           @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> entityRef,
                           @Nonnull PlayerRef playerRef,
                           @Nonnull World world) {
        boolean wasActive = freecamService.isActive(playerRef.getUuid());
        boolean wasTripodActive = freecamService.isTripodActive(playerRef.getUuid());
        if (!wasActive && !wasTripodActive) {
            context.sendMessage(Message.raw("Tripod can only be enabled from freecam."));
            return;
        }
        boolean enabled = freecamService.toggleTripod(playerRef, world, store, entityRef);
        context.sendMessage(Message.raw(enabled ? "Tripod enabled." : "Tripod disabled."));
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        return true;
    }
}
