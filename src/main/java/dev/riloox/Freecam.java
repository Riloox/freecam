package dev.riloox;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.riloox.freecam.FreecamCommand;
import dev.riloox.freecam.FreecamService;
import dev.riloox.freecam.TripodCommand;
import dev.riloox.freecam.events.FreecamCameraTickSystem;
import dev.riloox.freecam.events.FreecamBreakBlockEventSystem;
import dev.riloox.freecam.events.FreecamDamageBlockEventSystem;

import javax.annotation.Nonnull;

public class Freecam extends JavaPlugin {

    private final FreecamService freecamService = new FreecamService();

    public Freecam(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.getCommandRegistry().registerCommand(
                new FreecamCommand("freecam", "Toggle freecam mode", freecamService)
        );
        this.getCommandRegistry().registerCommand(
                new TripodCommand("tripod", "Toggle tripod camera mode", freecamService)
        );
        EntityStore.REGISTRY.registerSystem(new FreecamCameraTickSystem(freecamService));
        EntityStore.REGISTRY.registerSystem(new FreecamBreakBlockEventSystem(freecamService));
        EntityStore.REGISTRY.registerSystem(new FreecamDamageBlockEventSystem(freecamService));
    }
}
