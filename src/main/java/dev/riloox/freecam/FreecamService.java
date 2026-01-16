package dev.riloox.freecam;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.ApplyLookType;
import com.hypixel.hytale.protocol.ApplyMovementType;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.MouseInputType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.Vector2f;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreecamService {

    private final Map<UUID, FreecamState> active = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> speeds = new ConcurrentHashMap<>();
    private static final int DEFAULT_SPEED = 5;

    public boolean toggle(PlayerRef playerRef,
                          World world,
                          Store<EntityStore> store,
                          Ref<EntityStore> entityRef) {
        UUID playerId = playerRef.getUuid();
        if (active.containsKey(playerId)) {
            disable(playerId, playerRef, world, store, entityRef);
            return false;
        }
        enable(playerId, playerRef, world, store, entityRef);
        return true;
    }

    public boolean isActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public int getSpeed(UUID playerId) {
        return speeds.getOrDefault(playerId, DEFAULT_SPEED);
    }

    public void setSpeed(PlayerRef playerRef, World world, int speed) {
        int clamped = clampSpeed(speed);
        speeds.put(playerRef.getUuid(), clamped);
        if (isActive(playerRef.getUuid())) {
            Transform transform = playerRef.getTransform().clone();
            com.hypixel.hytale.math.vector.Vector3f head = playerRef.getHeadRotation().clone();
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, true, buildFreecamSettings(transform, head, clamped))
            );
        }
    }

    private void enable(UUID playerId,
                        PlayerRef playerRef,
                        World world,
                        Store<EntityStore> store,
                        Ref<EntityStore> entityRef) {
        FreecamState state = new FreecamState();
        state.transform = playerRef.getTransform().clone();
        state.headRotation = playerRef.getHeadRotation().clone();
        state.previousGameMode = readGameMode(store, entityRef);
        state.executeBlockDamage = readExecuteBlockDamage(store, entityRef);
        active.put(playerId, state);

        setGameMode(store, entityRef, GameMode.Adventure);
        setExecuteBlockDamage(store, entityRef, false);
        playerRef.updatePosition(world, state.transform, state.headRotation);
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, buildFreecamSettings(state.transform, state.headRotation, getSpeed(playerId)))
        );
    }

    private void disable(UUID playerId,
                         PlayerRef playerRef,
                         World world,
                         Store<EntityStore> store,
                         Ref<EntityStore> entityRef) {
        FreecamState state = active.remove(playerId);
        if (state == null) {
            return;
        }

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        playerRef.updatePosition(world, state.transform, state.headRotation);
        if (state.previousGameMode != null) {
            setGameMode(store, entityRef, state.previousGameMode);
        }
        if (state.executeBlockDamage != null) {
            setExecuteBlockDamage(store, entityRef, state.executeBlockDamage);
        }
    }

    private static GameMode readGameMode(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) {
            return null;
        }
        return player.getGameMode();
    }

    private static void setGameMode(Store<EntityStore> store, Ref<EntityStore> entityRef, GameMode mode) {
        if (mode == null) {
            return;
        }
        Player.setGameMode(entityRef, mode, store);
    }

    private static Boolean readExecuteBlockDamage(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) {
            return null;
        }
        return player.executeBlockDamage;
    }

    private static void setExecuteBlockDamage(Store<EntityStore> store, Ref<EntityStore> entityRef, boolean value) {
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player == null) {
            return;
        }
        player.executeBlockDamage = value;
    }

    private static ServerCameraSettings buildFreecamSettings(Transform transform,
                                                             com.hypixel.hytale.math.vector.Vector3f headRotation,
                                                             int speed) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0f;
        settings.rotationLerpSpeed = 1.0f;
        settings.speedModifier = 1.0f;
        settings.allowPitchControls = true;
        settings.displayCursor = false;
        settings.displayReticle = false;
        settings.mouseInputTargetType = MouseInputTargetType.Any;
        settings.sendMouseMotion = true;
        settings.skipCharacterPhysics = true;
        settings.isFirstPerson = false;
        settings.movementForceRotationType = com.hypixel.hytale.protocol.MovementForceRotationType.CameraRotation;
        settings.movementForceRotation = new Direction(0.0f, 0.0f, 0.0f);
        settings.attachedToType = AttachedToType.None;
        settings.attachedToEntityId = 0;
        settings.eyeOffset = true;
        settings.positionDistanceOffsetType = com.hypixel.hytale.protocol.PositionDistanceOffsetType.DistanceOffset;
        settings.positionOffset = new Position(0.0, 0.0, 0.0);
        settings.rotationOffset = new Direction(0.0f, 0.0f, 0.0f);
        settings.positionType = PositionType.Custom;
        settings.rotationType = RotationType.Custom;
        settings.position = new Position(
                transform.getPosition().x,
                transform.getPosition().y,
                transform.getPosition().z
        );
        settings.rotation = new Direction(
                headRotation.getYaw(),
                headRotation.getPitch(),
                headRotation.getRoll()
        );
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.Position;
        float horizontal = Math.max(1.0f, speed);
        float vertical = Math.max(0.5f, 0.4f + (speed * 0.12f));
        settings.movementMultiplier = new com.hypixel.hytale.protocol.Vector3f(horizontal, vertical, horizontal);
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(1.0f, 1.0f);
        settings.mouseInputType = MouseInputType.LookAtTarget;
        settings.planeNormal = new com.hypixel.hytale.protocol.Vector3f(0.0f, 1.0f, 0.0f);
        return settings;
    }

    private static int clampSpeed(int speed) {
        if (speed < 1) {
            return 1;
        }
        if (speed > 10) {
            return 10;
        }
        return speed;
    }

    private static final class FreecamState {
        private Transform transform;
        private Vector3f headRotation;
        private GameMode previousGameMode;
        private Boolean executeBlockDamage;
    }
}
