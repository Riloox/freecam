package dev.riloox.freecam;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector2f;
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
import com.hypixel.hytale.protocol.SavedMovementStates;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.camera.SetFlyCameraMode;
import com.hypixel.hytale.protocol.packets.player.SetMovementStates;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.protocol.MovementStates;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class FreecamService {

    private static final Logger LOGGER = Logger.getLogger(FreecamService.class.getName());
    private final Map<UUID, FreecamState> active = new ConcurrentHashMap<>();
    private final Map<UUID, TripodState> tripodActive = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> speeds = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lookLocked = new ConcurrentHashMap<>();
    private static final int DEFAULT_SPEED = 5;
    private static final float DEFAULT_EYE_HEIGHT = 1.62f;

    public boolean toggle(PlayerRef playerRef,
                          World world,
                          Store<EntityStore> store,
                          Ref<EntityStore> entityRef) {
        UUID playerId = playerRef.getUuid();
        if (active.containsKey(playerId)) {
            disable(playerId, playerRef, world, store, entityRef);
            return false;
        }
        if (tripodActive.containsKey(playerId)) {
            disableTripod(playerId, playerRef);
        }
        enable(playerId, playerRef, world, store, entityRef);
        return true;
    }

    public boolean isActive(UUID playerId) {
        return active.containsKey(playerId);
    }

    public boolean isTripodActive(UUID playerId) {
        return tripodActive.containsKey(playerId);
    }

    public boolean isLookLocked(UUID playerId) {
        return lookLocked.getOrDefault(playerId, false);
    }

    public int getSpeed(UUID playerId) {
        return speeds.getOrDefault(playerId, DEFAULT_SPEED);
    }

    public void setSpeed(PlayerRef playerRef, World world, int speed) {
        int clamped = clampSpeed(speed);
        speeds.put(playerRef.getUuid(), clamped);
        if (isActive(playerRef.getUuid())) {
            Transform transform = safeTransform(playerRef);
            Rotation3f head = safeHeadRotation(playerRef);
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, false, buildFreecamSettings(transform, head, clamped, isLookLocked(playerRef.getUuid())))
            );
        }
    }

    public boolean setLookLocked(PlayerRef playerRef, World world, boolean locked) {
        lookLocked.put(playerRef.getUuid(), locked);
        if (isActive(playerRef.getUuid())) {
            Transform transform = safeTransform(playerRef);
            Rotation3f head = safeHeadRotation(playerRef);
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, false, buildFreecamSettings(transform, head, getSpeed(playerRef.getUuid()), locked))
            );
        }
        return locked;
    }

    public boolean toggleTripod(PlayerRef playerRef,
                                World world,
                                Store<EntityStore> store,
                                Ref<EntityStore> entityRef) {
        UUID playerId = playerRef.getUuid();
        if (tripodActive.containsKey(playerId)) {
            disableTripod(playerId, playerRef);
            return false;
        }
        // Tripod is a standalone camera mode. If freecam happens to be active, leave it cleanly
        // first and plant the tripod at the player's restored body viewpoint.
        if (active.containsKey(playerId)) {
            disable(playerId, playerRef, world, store, entityRef);
        }
        Transform cameraTransform = safeTransform(playerRef);
        Rotation3f cameraHeadRotation = safeHeadRotation(playerRef);
        Vector3d position = cameraTransform.getPosition();
        LOGGER.info("Tripod snapshot for " + playerId
                + " pos=(" + position.x + "," + position.y + "," + position.z + ")"
                + " yaw=" + cameraHeadRotation.yaw()
                + " pitch=" + cameraHeadRotation.pitch()
                + " roll=" + cameraHeadRotation.roll());
        enableTripod(playerId, playerRef, cameraTransform, cameraHeadRotation);
        return true;
    }

    public void tick(PlayerRef playerRef, World world, Store<EntityStore> store, PlayerInput input, float deltaSeconds) {
        UUID playerId = playerRef.getUuid();
        FreecamState state = active.get(playerId);
        if (state == null) {
            return;
        }
        // Creative flight is required for vertical fly-camera input in the current client. Force
        // the state every tick because a Space gesture can toggle it client-side without first
        // changing the server component. Keep the body anchored as a second line of defense.
        if (store != null && world != null) {
            forceFlyingInput(input);
            setFlying(store, playerRef.getReference(), true);
            playerRef.getPacketHandler().writeNoCache(
                    new SetMovementStates(new SavedMovementStates(true))
            );
            playerRef.updatePosition(world, state.bodyTransform, state.bodyHeadRotation);
            restoreInventory(store, playerRef.getReference(), state.inventorySnapshot);
        }
        InputSnapshot snapshot = readInputSnapshot(state.transform, state.headRotation, input, state.lastInputIndex);
        if (snapshot == null || !snapshot.changed()) {
            return;
        }
        if (!state.loggedInputSummary) {
            LOGGER.info("Freecam input sample for " + playerId
                    + " hasMovement=" + snapshot.hasMovement()
                    + " hasWishMovement=" + snapshot.hasWishMovement()
                    + " wish=(" + snapshot.wishX() + "," + snapshot.wishY() + "," + snapshot.wishZ() + ")"
                    + " setHeadUpdates=" + snapshot.setHeadUpdates()
                    + " yaw=" + snapshot.headRotation().yaw()
                    + " pitch=" + snapshot.headRotation().pitch()
                    + " roll=" + snapshot.headRotation().roll());
            state.loggedInputSummary = true;
        }
        Transform nextTransform = snapshot.transform();
        Rotation3f nextHeadRotation = snapshot.headRotation();
        if (snapshot.hasWishMovement()) {
            Vector3d position = nextTransform.getPosition();
            double x = position != null ? position.x : 0.0;
            double y = position != null ? position.y : 0.0;
            double z = position != null ? position.z : 0.0;
            double localX = snapshot.wishX();
            double localY = snapshot.wishY();
            double localZ = snapshot.wishZ();
            double yaw = nextHeadRotation.yaw();
            double cos = Math.cos(yaw);
            double sin = Math.sin(yaw);
            double worldX = (localX * cos) - (localZ * sin);
            double worldZ = (localX * sin) + (localZ * cos);
            double horizontal = Math.max(1.0, getSpeed(playerId));
            double vertical = Math.max(0.5, 0.4 + (getSpeed(playerId) * 0.12));
            nextTransform = new Transform(
                    x + (worldX * horizontal * deltaSeconds),
                    y + (localY * vertical * deltaSeconds),
                    z + (worldZ * horizontal * deltaSeconds)
            );
        }
        // Track the camera position/rotation from the client's reported input so tripod can plant an
        // accurate snapshot, but DO NOT re-send the camera every tick: the Custom camera is driven
        // client-side via movementMultiplier/lookMultiplier, and re-sending a server-computed
        // position each tick fought that and pinned/sank the camera ("can't fly"). The camera is
        // (re)sent only on enable and on speed/lock changes.
        state.transform = nextTransform;
        state.headRotation = nextHeadRotation;
        state.lastInputIndex = snapshot.nextIndex();
    }

    public void tickTripod(PlayerRef playerRef, World world, PlayerInput input) {
        // Tripod is a "planted camera": the client holds the static Custom camera we installed in
        // enableTripod, while the player body is under normal server control. Nothing to do per
        // tick — we intentionally no longer pin the player's position or force its rotation, which
        // previously fought normal movement and caused the instability that disabled tripod.
    }

    private void enable(UUID playerId,
                        PlayerRef playerRef,
                        World world,
                        Store<EntityStore> store,
                        Ref<EntityStore> entityRef) {
        dismountIfMounted(store, entityRef);
        FreecamState state = new FreecamState();
        state.transform = safeTransform(playerRef);
        state.headRotation = safeHeadRotation(playerRef);
        state.bodyTransform = state.transform.clone();
        state.bodyHeadRotation = state.headRotation.clone();
        state.lastInputIndex = 0;
        state.hasServerCameraUpdates = false;
        state.previousGameMode = readGameMode(store, entityRef);
        state.executeBlockDamage = readExecuteBlockDamage(store, entityRef);
        state.inventorySnapshot = snapshotInventory(store, entityRef);
        captureMotion(store, entityRef, state);
        active.put(playerId, state);

        playerRef.getPacketHandler().writeNoCache(new SetFlyCameraMode(true));
        // Creative supplies the client-side Space/Ctrl vertical controls required by fly-camera.
        // Inventory is snapshotted above and continuously restored while this temporary mode is on.
        setGameMode(store, entityRef, GameMode.Creative);
        setFlying(store, entityRef, true);
        setExecuteBlockDamage(store, entityRef, false);
        playerRef.updatePosition(world, state.bodyTransform, state.bodyHeadRotation);
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, false, buildFreecamSettings(state.transform, state.headRotation, getSpeed(playerId), isLookLocked(playerId)))
        );
    }

    private void disable(UUID playerId,
                         PlayerRef playerRef,
                         World world,
                         Store<EntityStore> store,
                         Ref<EntityStore> entityRef) {
        disable(playerId, playerRef, world, store, entityRef, true);
    }

    private void disable(UUID playerId,
                         PlayerRef playerRef,
                         World world,
                         Store<EntityStore> store,
                         Ref<EntityStore> entityRef,
                         boolean restorePosition) {
        FreecamState state = active.remove(playerId);
        if (state == null) {
            return;
        }

        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        playerRef.getPacketHandler().writeNoCache(new SetFlyCameraMode(false));
        if (restorePosition) {
            playerRef.updatePosition(world, state.bodyTransform, state.bodyHeadRotation);
        }
        // Restore once more before leaving Creative so an inventory packet received between ticks
        // cannot survive freecam shutdown.
        restoreInventory(store, entityRef, state.inventorySnapshot);
        restoreMotion(store, entityRef, state);
        // Fall back to Adventure so a missed capture never strands the player in Creative.
        setGameMode(store, entityRef, state.previousGameMode != null ? state.previousGameMode : GameMode.Adventure);
        restoreMotion(store, entityRef, state);
        if (state.executeBlockDamage != null) {
            setExecuteBlockDamage(store, entityRef, state.executeBlockDamage);
        }
    }

    private void enableTripod(UUID playerId,
                              PlayerRef playerRef,
                              Transform transform,
                              Rotation3f headRotation) {
        TripodState state = new TripodState();
        state.transform = transform.clone();
        state.headRotation = headRotation.clone();
        tripodActive.put(playerId, state);

        LOGGER.info("Tripod enable for " + playerId
                + " pos=(" + transform.getPosition().x + "," + transform.getPosition().y + "," + transform.getPosition().z + ")"
                + " yaw=" + headRotation.yaw()
                + " pitch=" + headRotation.pitch()
                + " roll=" + headRotation.roll());
        // Plant a static camera at the player's current viewpoint. Tripod does not borrow or
        // create any freecam state, so the body remains under normal server control.
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, false, buildTripodSettings(transform, headRotation))
        );
    }

    private void disableTripod(UUID playerId, PlayerRef playerRef) {
        TripodState state = tripodActive.remove(playerId);
        if (state == null) {
            return;
        }
        // Clear the planted static camera and return directly to the normal player camera.
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
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

    private static void setFlying(Store<EntityStore> store, Ref<EntityStore> entityRef, boolean flying) {
        if (store == null || entityRef == null) {
            return;
        }
        MovementStatesComponent component = store.getComponent(entityRef, MovementStatesComponent.getComponentType());
        if (component == null) {
            return;
        }
        MovementStates states = component.getMovementStates();
        if (states == null) {
            states = new MovementStates();
        }
        states.flying = flying;
        if (flying) {
            states.onGround = false;
            states.falling = false;
            states.fallingFar = false;
            states.jumping = false;
        }
        component.setMovementStates(states);
    }

    private static void forceFlyingInput(PlayerInput input) {
        if (input == null) {
            return;
        }
        List<PlayerInput.InputUpdate> updates = input.getMovementUpdateQueue();
        if (updates == null) {
            return;
        }
        for (PlayerInput.InputUpdate update : updates) {
            if (update instanceof PlayerInput.SetMovementStates movementUpdate) {
                MovementStates states = movementUpdate.movementStates();
                if (states != null) {
                    states.flying = true;
                    states.jumping = false;
                    states.falling = false;
                    states.fallingFar = false;
                }
            }
        }
    }

    private static void captureMotion(Store<EntityStore> store,
                                      Ref<EntityStore> entityRef,
                                      FreecamState state) {
        if (store == null || entityRef == null) {
            return;
        }
        Velocity velocity = store.getComponent(entityRef, Velocity.getComponentType());
        if (velocity != null) {
            state.velocity = new Vector3d(velocity.getVelocity());
            state.clientVelocity = new Vector3d(velocity.getClientVelocity());
        }
        MovementStatesComponent movement = store.getComponent(
                entityRef, MovementStatesComponent.getComponentType()
        );
        if (movement != null && movement.getMovementStates() != null) {
            state.movementStates = movement.getMovementStates().clone();
        }
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player != null) {
            state.fallDistance = player.getCurrentFallDistance();
        }
    }

    private static void restoreMotion(Store<EntityStore> store,
                                      Ref<EntityStore> entityRef,
                                      FreecamState state) {
        if (store == null || entityRef == null) {
            return;
        }
        Velocity velocity = store.getComponent(entityRef, Velocity.getComponentType());
        if (velocity != null && state.velocity != null) {
            velocity.set(state.velocity);
            velocity.setClient(state.clientVelocity != null ? state.clientVelocity : state.velocity);
        }
        MovementStatesComponent movement = store.getComponent(
                entityRef, MovementStatesComponent.getComponentType()
        );
        if (movement != null && state.movementStates != null) {
            movement.setMovementStates(state.movementStates.clone());
        }
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player != null) {
            player.setCurrentFallDistance(state.fallDistance);
        }
    }

    private static ItemContainer[] snapshotInventory(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        Player player = store != null && entityRef != null
                ? store.getComponent(entityRef, Player.getComponentType())
                : null;
        Inventory inventory = player != null ? player.getInventory() : null;
        if (inventory == null) {
            return null;
        }
        return new ItemContainer[] {
                inventory.getStorage().clone(),
                inventory.getArmor().clone(),
                inventory.getHotbar().clone(),
                inventory.getUtility().clone(),
                inventory.getTools().clone(),
                inventory.getBackpack().clone()
        };
    }

    private static void restoreInventory(Store<EntityStore> store,
                                         Ref<EntityStore> entityRef,
                                         ItemContainer[] snapshot) {
        if (snapshot == null || snapshot.length != 6 || store == null || entityRef == null) {
            return;
        }
        Player player = store.getComponent(entityRef, Player.getComponentType());
        Inventory inventory = player != null ? player.getInventory() : null;
        if (inventory == null) {
            return;
        }
        ItemContainer[] current = {
                inventory.getStorage(),
                inventory.getArmor(),
                inventory.getHotbar(),
                inventory.getUtility(),
                inventory.getTools(),
                inventory.getBackpack()
        };
        for (int section = 0; section < current.length; section++) {
            ItemContainer target = current[section];
            ItemContainer original = snapshot[section];
            short capacity = (short) Math.min(target.getCapacity(), original.getCapacity());
            for (short slot = 0; slot < capacity; slot++) {
                ItemStack expected = original.getItemStack(slot);
                ItemStack actual = target.getItemStack(slot);
                if (!java.util.Objects.equals(expected, actual)) {
                    target.setItemStackForSlot(slot, expected != null ? expected : ItemStack.EMPTY);
                }
            }
        }
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
                                                             Rotation3f headRotation,
                                                             int speed,
                                                             boolean lockLook) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0f;
        settings.rotationLerpSpeed = 1.0f;
        settings.speedModifier = 1.0f;
        settings.allowPitchControls = true;
        settings.displayCursor = false;
        settings.displayReticle = false;
        settings.mouseInputTargetType = MouseInputTargetType.None;
        settings.sendMouseMotion = !lockLook;
        settings.skipCharacterPhysics = true;
        settings.isFirstPerson = false;
        settings.movementForceRotationType = com.hypixel.hytale.protocol.MovementForceRotationType.CameraRotation;
        settings.movementForceRotation = new Direction(0.0f, 0.0f, 0.0f);
        settings.attachedToType = AttachedToType.None;
        settings.attachedToEntityId = 0;
        settings.eyeOffset = false;
        settings.positionDistanceOffsetType = com.hypixel.hytale.protocol.PositionDistanceOffsetType.None;
        settings.positionOffset = new Position(0.0, 0.0, 0.0);
        settings.rotationOffset = new Direction(0.0f, 0.0f, 0.0f);
        settings.positionType = PositionType.Custom;
        settings.rotationType = RotationType.Custom;
        settings.position = buildEyePosition(transform);
        settings.rotation = new Direction(
                headRotation.yaw(),
                headRotation.pitch(),
                headRotation.roll()
        );
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.Position;
        float horizontal = Math.max(1.0f, speed);
        float vertical = Math.max(0.5f, 0.4f + (speed * 0.12f));
        settings.movementMultiplier = new Vector3f(horizontal, vertical, horizontal);
        settings.applyLookType = ApplyLookType.Rotation;
        settings.lookMultiplier = new Vector2f(lockLook ? 0.0f : 1.0f, lockLook ? 0.0f : 1.0f);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new Vector3f(0.0f, 1.0f, 0.0f);
        return settings;
    }

    private static ServerCameraSettings buildTripodSettings(Transform transform,
                                                           Rotation3f headRotation) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0f;
        settings.rotationLerpSpeed = 1.0f;
        settings.speedModifier = 1.0f;
        settings.allowPitchControls = true;
        settings.displayCursor = false;
        settings.displayReticle = false;
        settings.mouseInputTargetType = MouseInputTargetType.Any;
        settings.sendMouseMotion = false;
        settings.skipCharacterPhysics = false;
        settings.isFirstPerson = false;
        settings.movementForceRotationType = com.hypixel.hytale.protocol.MovementForceRotationType.CameraRotation;
        settings.movementForceRotation = new Direction(0.0f, 0.0f, 0.0f);
        settings.attachedToType = AttachedToType.None;
        settings.attachedToEntityId = 0;
        settings.eyeOffset = false;
        settings.positionDistanceOffsetType = com.hypixel.hytale.protocol.PositionDistanceOffsetType.None;
        settings.positionOffset = new Position(0.0, 0.0, 0.0);
        settings.rotationOffset = new Direction(0.0f, 0.0f, 0.0f);
        settings.positionType = PositionType.Custom;
        settings.rotationType = RotationType.Custom;
        settings.position = buildEyePosition(transform);
        settings.rotation = new Direction(
                headRotation.yaw(),
                headRotation.pitch(),
                headRotation.roll()
        );
        // The camera remains frozen because its position and rotation are Custom. Route input
        // through the normal character controller and player look orientation so tripod affects
        // only the viewpoint, not gameplay movement.
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.CharacterController;
        settings.movementMultiplier = new Vector3f(1.0f, 1.0f, 1.0f);
        settings.applyLookType = ApplyLookType.LocalPlayerLookOrientation;
        settings.lookMultiplier = new Vector2f(1.0f, 1.0f);
        settings.mouseInputType = MouseInputType.LookAtTarget;
        return settings;
    }

    private static Position buildEyePosition(Transform transform) {
        Vector3d position = transform != null ? transform.getPosition() : null;
        if (position == null) {
            position = new Vector3d(0.0, 0.0, 0.0);
        }
        return new Position(
                position.x,
                position.y + DEFAULT_EYE_HEIGHT,
                position.z
        );
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

    private static void dismountIfMounted(Store<EntityStore> store, Ref<EntityStore> entityRef) {
        if (store == null || entityRef == null) {
            return;
        }
        MountedComponent mounted = store.getComponent(entityRef, MountedComponent.getComponentType());
        if (mounted == null) {
            return;
        }
        Ref<EntityStore> mountedTo = mounted.getMountedToEntity();
        if (mountedTo != null) {
            MountedByComponent mountedBy = store.getComponent(mountedTo, MountedByComponent.getComponentType());
            if (mountedBy != null) {
                mountedBy.removePassenger(entityRef);
            }
        }
        store.tryRemoveComponent(entityRef, MountedComponent.getComponentType());
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player != null) {
            player.setMountEntityId(0);
        }
    }

    private static final class FreecamState {
        private Transform transform;
        private Rotation3f headRotation;
        private Transform bodyTransform;
        private Rotation3f bodyHeadRotation;
        private ItemContainer[] inventorySnapshot;
        private Vector3d velocity;
        private Vector3d clientVelocity;
        private MovementStates movementStates;
        private double fallDistance;
        private GameMode previousGameMode;
        private Boolean executeBlockDamage;
        private int lastInputIndex;
        private boolean hasServerCameraUpdates;
        private boolean loggedInputSummary;
    }

    private static final class TripodState {
        private Transform transform;
        private Rotation3f headRotation;
    }

    private record InputSnapshot(Transform transform,
                                 Rotation3f headRotation,
                                 boolean changed,
                                 int nextIndex,
                                 boolean hasMovement,
                                 boolean hasWishMovement,
                                 int setHeadUpdates,
                                 double wishX,
                                 double wishY,
                                 double wishZ) {}

    private static InputSnapshot readInputSnapshot(Transform baseTransform,
                                                   Rotation3f baseHeadRotation,
                                                   PlayerInput input,
                                                   int lastIndex) {
        if (input == null) {
            return null;
        }
        List<PlayerInput.InputUpdate> updates = input.getMovementUpdateQueue();
        if (updates == null || updates.isEmpty()) {
            return null;
        }
        int size = updates.size();
        int startIndex = Math.max(0, Math.min(lastIndex, size));
        Transform transform = baseTransform != null ? baseTransform.clone() : new Transform(0.0f, 0.0f, 0.0f);
        Rotation3f headRotation = baseHeadRotation != null ? baseHeadRotation.clone() : new Rotation3f(0.0f, 0.0f, 0.0f);
        boolean changed = false;
        boolean hasMovement = false;
        boolean hasWishMovement = false;
        int setHeadUpdates = 0;
        double wishX = 0.0;
        double wishY = 0.0;
        double wishZ = 0.0;
        for (int i = startIndex; i < size; i++) {
            PlayerInput.InputUpdate update = updates.get(i);
            if (update instanceof PlayerInput.AbsoluteMovement absolute) {
                transform = new Transform(absolute.getX(), absolute.getY(), absolute.getZ());
                changed = true;
                hasMovement = true;
                continue;
            }
            if (update instanceof PlayerInput.RelativeMovement relative) {
                Vector3d position = transform.getPosition();
                double x = position != null ? position.x : 0.0;
                double y = position != null ? position.y : 0.0;
                double z = position != null ? position.z : 0.0;
                transform = new Transform(x + relative.getX(), y + relative.getY(), z + relative.getZ());
                changed = true;
                hasMovement = true;
                continue;
            }
            if (update instanceof PlayerInput.WishMovement wish) {
                wishX = wish.getX();
                wishY = wish.getY();
                wishZ = wish.getZ();
                changed = true;
                hasWishMovement = true;
                continue;
            }
            if (update instanceof PlayerInput.SetHead headUpdate) {
                com.hypixel.hytale.protocol.Direction direction = headUpdate.direction();
                headRotation = new Rotation3f(direction.pitch, direction.yaw, direction.roll);
                setHeadUpdates++;
                changed = true;
            }
        }
        return new InputSnapshot(transform, headRotation, changed, size, hasMovement, hasWishMovement, setHeadUpdates, wishX, wishY, wishZ);
    }

    private static Transform safeTransform(PlayerRef playerRef) {
        Transform transform = playerRef.getTransform();
        if (transform == null) {
            return new Transform(0.0f, 0.0f, 0.0f);
        }
        return transform.clone();
    }

    private static Rotation3f safeHeadRotation(PlayerRef playerRef) {
        Rotation3f headRotation = playerRef.getHeadRotation();
        if (headRotation == null) {
            return new Rotation3f(0.0f, 0.0f, 0.0f);
        }
        return headRotation.clone();
    }
}
