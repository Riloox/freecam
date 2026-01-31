package dev.riloox.freecam.events;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.riloox.freecam.FreecamService;

public class FreecamCameraTickSystem extends EntityTickingSystem<EntityStore> {

    private final FreecamService freecamService;
    private final ComponentType<EntityStore, PlayerRef> playerRefType;
    private final ComponentType<EntityStore, PlayerInput> playerInputType;
    private final Query<EntityStore> query;

    public FreecamCameraTickSystem(FreecamService freecamService) {
        this.freecamService = freecamService;
        this.playerRefType = PlayerRef.getComponentType();
        this.playerInputType = PlayerInput.getComponentType();
        this.query = Archetype.of(playerRefType, playerInputType);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int count, int parallelism) {
        return false;
    }

    @Override
    public void tick(float delta,
                     int index,
                     ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store,
                     CommandBuffer<EntityStore> commandBuffer) {
        PlayerRef playerRef = chunk.getComponent(index, playerRefType);
        if (playerRef == null) {
            return;
        }
        PlayerInput input = chunk.getComponent(index, playerInputType);
        if (input == null) {
            return;
        }
        EntityStore entityStore = store.getExternalData();
        World world = entityStore != null ? entityStore.getWorld() : null;
        if (freecamService.isTripodActive(playerRef.getUuid())) {
            freecamService.tickTripod(playerRef, world, input);
            return;
        }
        freecamService.tick(playerRef, world, input, delta);
    }
}
