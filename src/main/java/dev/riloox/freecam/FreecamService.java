package dev.riloox.freecam;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3d;
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
import com.hypixel.hytale.protocol.packets.camera.SetFlyCameraMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.builtin.mounts.MountedByComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;

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
    private static final float TRIPOD_STICK_DISTANCE = 0.01f;

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
            com.hypixel.hytale.math.vector.Vector3f head = safeHeadRotation(playerRef);
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, true, buildFreecamSettings(transform, head, clamped, isLookLocked(playerRef.getUuid())))
            );
        }
    }

    public boolean setLookLocked(PlayerRef playerRef, World world, boolean locked) {
        lookLocked.put(playerRef.getUuid(), locked);
        if (isActive(playerRef.getUuid())) {
            Transform transform = safeTransform(playerRef);
            com.hypixel.hytale.math.vector.Vector3f head = safeHeadRotation(playerRef);
            playerRef.getPacketHandler().writeNoCache(
                    new SetServerCamera(ClientCameraView.Custom, true, buildFreecamSettings(transform, head, getSpeed(playerRef.getUuid()), locked))
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
        FreecamState activeState = active.get(playerId);
        if (activeState == null) {
            LOGGER.info("Tripod requested but freecam state missing for player " + playerId);
            return false;
        }
        Transform cameraTransform = activeState.transform != null ? activeState.transform.clone() : safeTransform(playerRef);
        Vector3f cameraHeadRotation = activeState.headRotation != null ? activeState.headRotation.clone() : safeHeadRotation(playerRef);
        Vector3d position = cameraTransform.getPosition();
        LOGGER.info("Tripod snapshot for " + playerId
                + " pos=(" + position.x + "," + position.y + "," + position.z + ")"
                + " yaw=" + cameraHeadRotation.getYaw()
                + " pitch=" + cameraHeadRotation.getPitch()
                + " roll=" + cameraHeadRotation.getRoll());
        if (active.containsKey(playerId)) {
            disable(playerId, playerRef, world, store, entityRef, false);
        }
        enableTripod(playerId, playerRef, cameraTransform, cameraHeadRotation);
        return true;
    }

    public void tick(PlayerRef playerRef, World world, PlayerInput input, float deltaSeconds) {
        UUID playerId = playerRef.getUuid();
        FreecamState state = active.get(playerId);
        if (state == null) {
            return;
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
                    + " yaw=" + snapshot.headRotation().getYaw()
                    + " pitch=" + snapshot.headRotation().getPitch()
                    + " roll=" + snapshot.headRotation().getRoll());
            state.loggedInputSummary = true;
        }
        Transform nextTransform = snapshot.transform();
        Vector3f nextHeadRotation = snapshot.headRotation();
        if (snapshot.hasWishMovement()) {
            Vector3d position = nextTransform.getPosition();
            double x = position != null ? position.x : 0.0;
            double y = position != null ? position.y : 0.0;
            double z = position != null ? position.z : 0.0;
            double localX = snapshot.wishX();
            double localY = snapshot.wishY();
            double localZ = snapshot.wishZ();
            double yaw = nextHeadRotation.getYaw();
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
        state.transform = nextTransform;
        state.headRotation = nextHeadRotation;
        state.lastInputIndex = snapshot.nextIndex();
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true,
                        buildFreecamSettings(state.transform, state.headRotation, getSpeed(playerId), isLookLocked(playerId)))
        );
    }

    public void tickTripod(PlayerRef playerRef, World world, PlayerInput input) {
        TripodState state = tripodActive.get(playerRef.getUuid());
        if (state == null) {
            return;
        }
        if (world != null && state.transform != null) {
            Transform current = safeTransform(playerRef);
            if (!isClose(current, state.transform)) {
                playerRef.updatePosition(world, state.transform, safeHeadRotation(playerRef));
            }
        }
        int setHeadUpdates = 0;
        int setBodyUpdates = 0;
        Vector3f playerHeadRotation = state.playerHeadRotation != null
                ? state.playerHeadRotation.clone()
                : safeHeadRotation(playerRef);
        if (input != null) {
            List<PlayerInput.InputUpdate> updates = input.getMovementUpdateQueue();
            if (updates != null && !updates.isEmpty()) {
                int size = updates.size();
                int startIndex = Math.max(0, Math.min(state.lastInputIndex, size));
                for (int i = startIndex; i < size; i++) {
                    PlayerInput.InputUpdate update = updates.get(i);
                    if (update instanceof PlayerInput.SetHead headUpdate) {
                        com.hypixel.hytale.protocol.Direction direction = headUpdate.direction();
                        playerHeadRotation = new Vector3f(direction.pitch, direction.yaw, direction.roll);
                        setHeadUpdates++;
                        continue;
                    }
                    if (update instanceof PlayerInput.SetBody bodyUpdate) {
                        com.hypixel.hytale.protocol.Direction direction = bodyUpdate.direction();
                        playerHeadRotation = new Vector3f(direction.pitch, direction.yaw, direction.roll);
                        setBodyUpdates++;
                    }
                }
                state.lastInputIndex = size;
            }
        }
        if ((setHeadUpdates > 0 || setBodyUpdates > 0) && world != null) {
            state.playerHeadRotation = playerHeadRotation.clone();
            playerRef.updatePosition(world, safeTransform(playerRef), playerHeadRotation);
        }
        Vector3f currentHead = state.headRotation != null
                ? state.headRotation.clone()
                : safeHeadRotation(playerRef);
        long nowMs = System.currentTimeMillis();
        if (nowMs - state.lastDebugAtMs >= 1000L) {
            LOGGER.info("Tripod tick for " + playerRef.getUuid()
                    + " cur=(" + currentHead.getYaw() + "," + currentHead.getPitch() + "," + currentHead.getRoll() + ")"
                    + " setHeadUpdates=" + setHeadUpdates
                    + " setBodyUpdates=" + setBodyUpdates
                    + " playerHead=(" + playerHeadRotation.getYaw() + "," + playerHeadRotation.getPitch() + "," + playerHeadRotation.getRoll() + ")"
                    + " hasTransform=" + (state.transform != null)
                    + " hasHead=" + (state.headRotation != null));
            state.lastDebugAtMs = nowMs;
        }
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
        state.lastInputIndex = 0;
        state.hasServerCameraUpdates = false;
        state.previousGameMode = readGameMode(store, entityRef);
        state.executeBlockDamage = readExecuteBlockDamage(store, entityRef);
        active.put(playerId, state);

        playerRef.getPacketHandler().writeNoCache(new SetFlyCameraMode(true));
        setGameMode(store, entityRef, GameMode.Adventure);
        setExecuteBlockDamage(store, entityRef, false);
        playerRef.updatePosition(world, state.transform, state.headRotation);
        playerRef.getPacketHandler().writeNoCache(
                new SetServerCamera(ClientCameraView.Custom, true, buildFreecamSettings(state.transform, state.headRotation, getSpeed(playerId), isLookLocked(playerId)))
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
            playerRef.updatePosition(world, state.transform, state.headRotation);
        }
        if (state.previousGameMode != null) {
            setGameMode(store, entityRef, state.previousGameMode);
        }
        if (state.executeBlockDamage != null) {
            setExecuteBlockDamage(store, entityRef, state.executeBlockDamage);
        }
    }

    private void enableTripod(UUID playerId,
                              PlayerRef playerRef,
                              Transform transform,
                              Vector3f headRotation) {
        TripodState state = new TripodState();
        state.transform = transform.clone();
        state.headRotation = headRotation.clone();
        state.playerHeadRotation = safeHeadRotation(playerRef);
        state.lastInputIndex = 0;
        state.lastDebugAtMs = System.currentTimeMillis();
        tripodActive.put(playerId, state);

        LOGGER.info("Tripod enable for " + playerId
                + " pos=(" + transform.getPosition().x + "," + transform.getPosition().y + "," + transform.getPosition().z + ")"
                + " yaw=" + headRotation.getYaw()
                + " pitch=" + headRotation.getPitch()
                + " roll=" + headRotation.getRoll());
        playerRef.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
    }

    private void disableTripod(UUID playerId, PlayerRef playerRef) {
        TripodState state = tripodActive.remove(playerId);
        if (state == null) {
            return;
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
        settings.lookMultiplier = new Vector2f(lockLook ? 0.0f : 1.0f, lockLook ? 0.0f : 1.0f);
        settings.mouseInputType = MouseInputType.LookAtPlane;
        settings.planeNormal = new com.hypixel.hytale.protocol.Vector3f(0.0f, 1.0f, 0.0f);
        return settings;
    }

    private static ServerCameraSettings buildTripodSettings(Transform transform,
                                                           com.hypixel.hytale.math.vector.Vector3f headRotation) {
        ServerCameraSettings settings = new ServerCameraSettings();
        settings.positionLerpSpeed = 1.0f;
        settings.rotationLerpSpeed = 1.0f;
        settings.speedModifier = 1.0f;
        settings.allowPitchControls = true;
        settings.displayCursor = false;
        settings.displayReticle = false;
        settings.mouseInputTargetType = MouseInputTargetType.Any;
        settings.sendMouseMotion = true;
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
                headRotation.getYaw(),
                headRotation.getPitch(),
                headRotation.getRoll()
        );
        settings.canMoveType = CanMoveType.Always;
        settings.applyMovementType = ApplyMovementType.CharacterController;
        settings.movementMultiplier = new com.hypixel.hytale.protocol.Vector3f(1.0f, 1.0f, 1.0f);
        settings.applyLookType = ApplyLookType.LocalPlayerLookOrientation;
        settings.lookMultiplier = new Vector2f(1.0f, 1.0f);
        settings.mouseInputType = MouseInputType.LookAtTarget;
        settings.planeNormal = new com.hypixel.hytale.protocol.Vector3f(0.0f, 1.0f, 0.0f);
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

    private static boolean isClose(Transform a, Transform b) {
        if (a == null || b == null) {
            return false;
        }
        Vector3d posA = a.getPosition();
        Vector3d posB = b.getPosition();
        if (posA == null || posB == null) {
            return false;
        }
        double dx = posA.x - posB.x;
        double dy = posA.y - posB.y;
        double dz = posA.z - posB.z;
        return (dx * dx + dy * dy + dz * dz) <= (TRIPOD_STICK_DISTANCE * TRIPOD_STICK_DISTANCE);
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
        private Vector3f headRotation;
        private GameMode previousGameMode;
        private Boolean executeBlockDamage;
        private int lastInputIndex;
        private boolean hasServerCameraUpdates;
        private boolean loggedInputSummary;
    }

    private static final class TripodState {
        private Transform transform;
        private Vector3f headRotation;
        private Vector3f playerHeadRotation;
        private int lastInputIndex;
        private long lastDebugAtMs;
    }

    private CameraSnapshot captureCameraSnapshot(PlayerRef playerRef,
                                                 Store<EntityStore> store,
                                                 Ref<EntityStore> entityRef) {
        FreecamState activeState = active.get(playerRef.getUuid());
        if (activeState != null) {
            Transform activeTransform = activeState.transform != null ? activeState.transform.clone() : safeTransform(playerRef);
            Vector3f activeHeadRotation = activeState.headRotation != null ? activeState.headRotation.clone() : safeHeadRotation(playerRef);
            return new CameraSnapshot(activeTransform, activeHeadRotation);
        }
        Transform cameraTransform = safeTransform(playerRef);
        Vector3f cameraHeadRotation = safeHeadRotation(playerRef);
        if (store == null || entityRef == null) {
            return new CameraSnapshot(cameraTransform, cameraHeadRotation);
        }
        PlayerInput input = store.getComponent(entityRef, PlayerInput.getComponentType());
        if (input == null) {
            return new CameraSnapshot(cameraTransform, cameraHeadRotation);
        }
        var updates = input.getMovementUpdateQueue();
        if (updates == null) {
            return new CameraSnapshot(cameraTransform, cameraHeadRotation);
        }
        for (int i = updates.size() - 1; i >= 0; i--) {
            PlayerInput.InputUpdate update = updates.get(i);
            if (update instanceof PlayerInput.AbsoluteMovement absolute) {
                cameraTransform = new Transform(absolute.getX(), absolute.getY(), absolute.getZ());
                break;
            }
        }
        for (int i = updates.size() - 1; i >= 0; i--) {
            PlayerInput.InputUpdate update = updates.get(i);
            if (update instanceof PlayerInput.SetHead headUpdate) {
                com.hypixel.hytale.protocol.Direction direction = headUpdate.direction();
                cameraHeadRotation = new Vector3f(direction.pitch, direction.yaw, direction.roll);
                break;
            }
        }
        return new CameraSnapshot(cameraTransform, cameraHeadRotation);
    }

    private record CameraSnapshot(Transform transform, Vector3f headRotation) {}

    private record InputSnapshot(Transform transform,
                                 Vector3f headRotation,
                                 boolean changed,
                                 int nextIndex,
                                 boolean hasMovement,
                                 boolean hasWishMovement,
                                 int setHeadUpdates,
                                 double wishX,
                                 double wishY,
                                 double wishZ) {}

    private static InputSnapshot readInputSnapshot(Transform baseTransform,
                                                   Vector3f baseHeadRotation,
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
        Vector3f headRotation = baseHeadRotation != null ? baseHeadRotation.clone() : new Vector3f(0.0f, 0.0f, 0.0f);
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
                headRotation = new Vector3f(direction.pitch, direction.yaw, direction.roll);
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

    private static Vector3f safeHeadRotation(PlayerRef playerRef) {
        Vector3f headRotation = playerRef.getHeadRotation();
        if (headRotation == null) {
            return new Vector3f(0.0f, 0.0f, 0.0f);
        }
        return headRotation.clone();
    }
}
