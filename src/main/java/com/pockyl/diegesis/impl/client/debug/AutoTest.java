package com.pockyl.diegesis.impl.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.ClientStations;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.focus.FocusController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * For looking at a station while porting: with {@code -Ddiegesis.autotest} the client closes whatever screen is open, finds a
 * station block beside the player, turns to it and opens it. A session on this machine can then be looked at without a hand on the
 * keyboard (the screenshots come from the test mod's own hook).
 */
public final class AutoTest {
    private static final int RADIUS = 12;
    private static BlockPos last;

    private AutoTest() {
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        place(minecraft, minecraft.player);
        int ticks = minecraft.gui.getGuiTicks();
        if (ticks % 20 == 0) {
            DiegesisMod.LOGGER.warn("AUTOTEST fps {} at tick {}, station {}", minecraft.getFps(), ticks,
                    FocusController.get().isActive());
        }
        if (ticks < 100 || ticks % 100 != 0) {
            return;
        }
        if (minecraft.screen != null) {
            minecraft.setScreen(null);
        }
        LocalPlayer player = minecraft.player;
        if (FocusController.get().isActive()) {
            click(minecraft, ticks);
            if (ticks % 400 == 0) {
                ClientStation station = FocusController.get().station();
                DiegesisMod.LOGGER.warn("AUTOTEST open {} at {}, player at {}", station.type().id(), station.pos(),
                        player.blockPosition());
            }
            return;
        }
        List<BlockPos> stations = find(minecraft, player);
        if (stations.isEmpty()) {
            if (ticks % 400 == 0) {
                DiegesisMod.LOGGER.warn("AUTOTEST no station block within {} of {}", RADIUS, player.blockPosition());
            }
            return;
        }
        // A station under another one (a pot on a stove) does not open: try them by distance until one does.
        for (BlockPos station : stations) {
            Vec3 center = Vec3.atCenterOf(station);
            double distance = Math.sqrt(center.distanceToSqr(player.position()));
            if (distance > 3.5) {
                // Out of reach (the player was left somewhere else): walk them to the station and try on a later tick.
                if (ticks % 200 == 0) {
                    DiegesisMod.LOGGER.warn("AUTOTEST walking to {} ({} blocks)", station, Math.round(distance));
                    player.connection.sendCommand("tp " + player.getScoreboardName() + " " + (station.getX() + 2) + " "
                            + station.getY() + " " + (station.getZ() + 2));
                }
                continue;
            }
            Vec3 to = center.subtract(player.getEyePosition());
            player.setYRot((float) (Math.toDegrees(Math.atan2(-to.x, to.z))));
            player.setXRot((float) Math.toDegrees(-Math.atan2(to.y, Math.hypot(to.x, to.z))));
            if (!station.equals(last)) {
                last = station;
                DiegesisMod.LOGGER.warn("AUTOTEST station block {} at {} ({} blocks)", minecraft.level.getBlockState(station),
                        station, Math.round(Math.sqrt(center.distanceToSqr(player.position()))));
            }
            // A station opens for a cook's hand: an empty one, or one holding a kitchen thing.
            if (!player.getMainHandItem().isEmpty()) {
                player.getInventory().setSelectedItem(net.minecraft.world.item.ItemStack.EMPTY);
            }
            net.minecraft.world.InteractionResult opened = DiegesisClientRuntime.onUseBlock(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(center, Direction.UP, station, false));
            if (opened != null) {
                return;
            }
        }
        if (ticks % 400 == 0) {
            DiegesisMod.LOGGER.warn("AUTOTEST no station of {} would open for the player at {}", stations.size(),
                    player.blockPosition());
        }
    }

    /** The main blocks of the stations around the player, the nearest first (only the one asked for, if asked). */
    private static List<BlockPos> find(Minecraft minecraft, LocalPlayer player) {
        BlockPos from = player.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-RADIUS, -4, -RADIUS), from.offset(RADIUS, 4, RADIUS))) {
            BlockState state = minecraft.level.getBlockState(pos);
            StationType type = StationRegistry.find(state).orElse(null);
            if (type != null && type.anchor().resolve(minecraft.level, pos, state).equals(pos)
                    && (WANTED == null || WANTED.isEmpty() || WANTED.equals(type.id().toString()))) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
        if (placeTarget != null && found.remove(placeTarget)) {
            found.add(0, placeTarget);
        }
        return found;
    }

    /** The station to work at, by id ({@code -Ddiegesis.autotest.station=handson_delight:cutting_board}); any if empty. */
    private static final String WANTED = System.getProperty("diegesis.autotest.station");

    /**
     * A block to stand in for a station in a world that has none: {@code -Ddiegesis.autotest.place=mynethersdelight:nether_stove}.
     * It is set down beside the player once, the world being single player, and then opened as any station is: an addon's stove can
     * be looked at without a hand on the keyboard and without building anything.
     */
    private static final String PLACED = System.getProperty("diegesis.autotest.place");
    private static boolean placed;
    /** Where the block to open was set down: tried first, whatever else stands around. */
    private static BlockPos placeTarget;

    private static void place(Minecraft minecraft, LocalPlayer player) {
        if (placed || PLACED == null || PLACED.isEmpty() || player.tickCount < 40) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id(PLACED));
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (block == null || server == null) {
            placed = true;
            DiegesisMod.LOGGER.warn("AUTOTEST cannot place {}: {} (single player only)", PLACED,
                    block == null ? "no such block" : "no single player server");
            return;
        }
        BlockPos target = roomFor(player);
        BlockState state = block.defaultBlockState();
        placeTarget = target;
        var dimension = player.level().dimension();
        server.execute(() -> {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) {
                DiegesisMod.LOGGER.warn("AUTOTEST cannot place {} into {}", PLACED, dimension);
                return;
            }
            level.setBlockAndUpdate(target, state);
            level.setBlockAndUpdate(target.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(target.above(2), Blocks.AIR.defaultBlockState());
            DiegesisMod.LOGGER.warn("AUTOTEST placed {} at {} ({})", PLACED, target, level.getBlockState(target));
        });
        placed = true;
    }

    /**
     * A node to click once the station is open: {@code -Ddiegesis.autotest.click=book_open} opens the recipe book without a hand
     * on the mouse. Clicked twice, half a second apart, as a cook would.
     */
    private static final String CLICK = System.getProperty("diegesis.autotest.click");
    private static int clicksLeft = 3;

    private static void click(Minecraft minecraft, int ticks) {
        if (CLICK == null || CLICK.isEmpty() || clicksLeft <= 0 || ticks % 200 != 0) {
            return;
        }
        clicksLeft--;
        ClientStation station = FocusController.get().station();
        if (station == null) {
            return;
        }
        station.onEvent(new com.pockyl.diegesis.api.event.SceneEvent.Click(CLICK, 0));
        DiegesisMod.LOGGER.warn("AUTOTEST clicked {} at {}, {} clicks left", CLICK, station.pos(), clicksLeft);
    }

    /** An air place near the player, the nearest first: the one in front of them if there is one. */    private static BlockPos roomFor(LocalPlayer player) {
        BlockPos from = player.blockPosition();
        BlockPos ahead = from.relative(player.getDirection(), 3);
        List<BlockPos> places = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(from.offset(-4, -1, -4), from.offset(4, 1, 4))) {
            if (player.level().getBlockState(pos).canBeReplaced()) {
                places.add(pos.immutable());
            }
        }
        if (places.isEmpty()) {
            return ahead;
        }
        places.sort(Comparator.comparingDouble(pos -> pos.distSqr(ahead)));
        return places.get(0);
    }
    private static Identifier id(String value) {
        int colon = value.indexOf(':');
        return colon < 0 ? Identifier.withDefaultNamespace(value)
                : Identifier.fromNamespaceAndPath(value.substring(0, colon), value.substring(colon + 1));
    }
}
