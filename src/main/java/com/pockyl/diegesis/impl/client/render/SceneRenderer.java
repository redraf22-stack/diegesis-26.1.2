package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.SubmitNodeStorage.ItemSubmit;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.DynamicTexture;import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.client.NodePainter;
import com.pockyl.diegesis.api.scene.InterfaceOptions;
import com.pockyl.diegesis.api.scene.InventoryLayout;
import com.pockyl.diegesis.api.scene.LightDefinition;
import com.pockyl.diegesis.api.scene.NodeDefinition;
import com.pockyl.diegesis.api.scene.PlaceSurface;
import com.pockyl.diegesis.api.scene.PlacingOptions;
import com.pockyl.diegesis.api.scene.PourOptions;
import com.pockyl.diegesis.api.scene.SlotBinding;
import com.pockyl.diegesis.api.scene.TankBinding;
import com.pockyl.diegesis.api.scene.Visual;
import com.pockyl.diegesis.api.state.ItemPose;
import com.pockyl.diegesis.impl.action.StationAction.Target;
import com.pockyl.diegesis.impl.client.ClientStation;
import com.pockyl.diegesis.impl.client.ClientStations;
import com.pockyl.diegesis.impl.client.debug.DebugView;
import com.pockyl.diegesis.impl.client.fx.LiquidRenderer;
import com.pockyl.diegesis.impl.client.fx.SceneParticles;
import com.pockyl.diegesis.impl.client.focus.FocusController;
import com.pockyl.diegesis.impl.client.focus.FocusInput;
import com.pockyl.diegesis.impl.client.focus.Projector;
import com.pockyl.diegesis.impl.client.scene.NodeInstance;
import com.pockyl.diegesis.impl.client.scene.PlacedItem;
import com.pockyl.diegesis.impl.client.scene.PlacedShape;
import com.pockyl.diegesis.impl.client.scene.SceneInstance;
import com.pockyl.diegesis.platform.ClientPlatform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws every shown station's scene into the level, after the entities, with the same render types as entities and
 * item frames - so shader packs light and shade them like the rest of the world. Carried things cast a shadow of
 * their own shape onto the work surface. Also captures the frame's camera for the cursor ray.
 */
public final class SceneRenderer {
    /** Stations farther than this (blocks) are not drawn. */
    private static final double RENDER_RANGE = 40.0;
    /** Shadows and highlights: block-atlas geometry, see-through, back faces culled (no double layers in a shadow). */
    private static final RenderType OVERLAY = RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    /** Everything see-through of the scene goes here, in a batch of its own: it stays one sorted batch. */
    private static final RenderType CLEAR = DiegesisRenderTypes.clearBlockSheet();
    /** The frame's scene, kept until the frame draws it (see {@link SceneSubmit}). */
    private static final SceneSubmit SCENE = new SceneSubmit();
    /** The counts over the scene: a batch of their own, drawn after everything else. */
    private static final SceneSubmit COUNTS = new SceneSubmit();
    /** Order of the scene against the counts: the counts lie over the scene, so they are later. */
    private static final int SCENE_ORDER = 0;
    private static final int COUNTS_ORDER = 2;
    private static final Identifier HIGHLIGHT_SPRITE = Identifier.withDefaultNamespace("block/white_concrete");
    /** Shadow lean per pixel of height: light from above, a little from the front left, like the GUI's light. */
    private static final float SHADOW_SHEAR_X = -0.22F;
    private static final float SHADOW_SHEAR_Z = 0.3F;
    /** Where the scene's light comes from, for highlights on liquids: the opposite of the shadows' lean. */
    private static final Vector3fc LIGHT_DIRECTION = new Vector3f(-SHADOW_SHEAR_X, 1.0F, -SHADOW_SHEAR_Z).normalize();
    private static final Set<Identifier> WARNED_MODELS = new HashSet<>();
    /** Pools of light: a soft round gradient made in code (no texture file), laid on the surface. */
    private static RenderType pool;
    private static final List<Count> PENDING_COUNTS = new ArrayList<>();
    /** What gets an outline this frame (the usable object under the cursor), drawn once the scene is done. */
    private static final List<Consumer<MultiBufferSource>> PENDING_OUTLINES = new ArrayList<>();
    private static int outlineColor = 0xFFFFFF;
    /** Thickness of the board under a laid-out inventory, in pixels. */
    private static final float BOARD_THICKNESS = 1.0F;
    /** How much larger the copy drawn as the mark round the thing under the cursor is. */
    private static final float OUTLINE_GROW = 1.04F;
    /** Whether the shapes drawn are logged ({@code -Ddiegesis.diag}), once per node. */
    private static final boolean DIAG_SHAPE = Boolean.getBoolean("diegesis.diag");
    private static final Set<String> SHAPES_LOGGED = new HashSet<>();
    /** The sides a model's quads are asked for, and the ones that face nowhere, last. */
    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
            null};
    /** An item is drawn through the game's own render state; the quads it submits are then written into the scene's buffers. */
    private static final ItemStackRenderState ITEM_STATE = new ItemStackRenderState();
    private static final SubmitNodeStorage SUBMIT_NODES = new SubmitNodeStorage();
    private static final QuadInstance QUAD = new QuadInstance();
    private static final List<BlockStateModelPart> PARTS = new ArrayList<>();
    private static final RandomSource MODEL_RANDOM = RandomSource.createThreadLocalInstance(42L);
    private static ItemModelResolver itemModels;
    private static ModelBlockRenderer blockRenderer;

    private static long lastFrame = Util.getNanos();
    /** The interface options of the station being drawn. */
    private static InterfaceOptions ui = InterfaceOptions.DEFAULT;
    /** The frame's camera and level pose (camera-relative, not turned), for the debug view's labels. */
    private static Camera debugCamera;
    private static PoseStack debugPose;

    /** The level's view turned back: from the poses of this frame into the world's directions. */
    private static final Matrix4f TO_WORLD = new Matrix4f();
    /** The block of the station being drawn, which a visual lit as its block takes its light from. */
    private static net.minecraft.core.BlockPos drawnPos;

    private SceneRenderer() {
    }

    /**
     * Called by the loader's level-render hook after the entities.
     *
     * @param poseStack  the level pose stack: relative to the camera, not turned to it (the view rotation is in the model-view
     *                   matrix the buffers are drawn with)
     * @param modelView  the frame's view matrix: the camera's rotation
     * @param projection the frame's projection matrix
     * @param collector  where the frame's geometry is submitted: nothing may be drawn into the world directly since 26.1
     */
    public static void render(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, Camera camera,
            SubmitNodeCollector collector) {
        long started = Util.getNanos();
        SCENE.clear();
        COUNTS.clear();
        try {
            renderStations(poseStack, modelView, projection, camera, collector);
        } finally {
            // After every station's frame: the blocks their scenes now stand in for are left out of the next frame's chunks.
            ClientStations.get().syncHiddenBlocks();
            diagFrame(Util.getNanos() - started);
        }
    }

    /** How long the scenes take to put their geometry together each frame ({@code -Ddiegesis.diag} while looking at speed). */
    private static void diagFrame(long nanos) {
        if (!DIAG) {
            return;
        }
        diagFrames++;
        diagSpent += nanos;
        long now = System.nanoTime();
        if (diagSince == 0L) {
            diagSince = now;
        }
        if (now - diagSince >= 5_000_000_000L) {
            double each = diagSpent / 1.0e6 / Math.max(1, diagFrames);
            DiegesisMod.LOGGER.warn("DIAG scene {} ms/frame over {} frames", String.format("%.2f", each), diagFrames);
            diagSince = now;
            diagFrames = 0;
            diagSpent = 0L;
        }
    }

    private static final boolean DIAG = Boolean.getBoolean("diegesis.diag");
    private static int diagFrames;
    private static long diagSpent;
    private static long diagSince;

    private static void renderStations(PoseStack poseStack, Matrix4f modelView, Matrix4f projection, Camera camera,
            SubmitNodeCollector collector) {
        Projector.capture(projection, new Matrix4f(modelView).mul(poseStack.last().pose()), camera.position());
        // The level pose is not turned to the camera here: turning it back to the world only undoes what it holds.
        TO_WORLD.set(poseStack.last().pose()).invert();
        SmoothPass.begin(projection, modelView);
        long now = Util.getNanos();
        float dt = Math.min((now - lastFrame) / 1.0e9F, 0.1F);
        lastFrame = now;

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        if (FocusController.get().isInteractive()) {
            FocusInput.get().frameFromRenderer();
        }
        Vec3 cameraPos = camera.position();
        debugCamera = camera;
        debugPose = new PoseStack();
        debugPose.last().pose().set(poseStack.last().pose());
        debugPose.last().normal().set(poseStack.last().normal());
        List<ClientStation> stations = new ArrayList<>(ClientStations.get().all());
        if (stations.isEmpty()) {
            return;
        }
        RenderType translucent = Sheets.translucentBlockSheet();
        // See-through block things go into their own batch (one sorted pass), everything else into its own type's.
        MultiBufferSource scene = type -> SCENE.getBuffer(type != translucent ? type : CLEAR);
        for (ClientStation station : stations) {
            station.frame(dt);
            if (!isDrawn(station, cameraPos)) {
                continue;
            }
            // Each station draws on its own copy of the pose stack: a failure halfway can never leave the level's stack
            // unbalanced (the game checks it and crashes).
            PoseStack own = new PoseStack();
            own.last().pose().set(poseStack.last().pose());
            own.last().normal().set(poseStack.last().normal());
            try {
                renderStation(station, own, cameraPos, scene, level);
            } catch (RuntimeException e) {
                DiegesisMod.LOGGER.error("Drawing station {} at {} failed", station.type().id(), station.pos(), e);
            }
        }
        // The scene goes into the frame's own batches: the game draws them where the frame needs them, see-through ones after
        // the opaque. The mark round the thing under the cursor is part of the scene (drawn with it, so it stays behind it).
        drawOutlines();
        SCENE.submit(collector, SCENE_ORDER);
        flushCounts(collector);
    }

    /**
     * The outline round the usable object under the cursor: its silhouette goes into the game's entity outline target
     * (the glowing effect's), the outline effect turns the silhouette into a line round its edge, and the game lays that
     * over the frame as it does for glowing entities.
     */
    /**
     * The mark round what the cursor is on. The game's own outline (a silhouette into its glow target, turned into a line by a
     * post chain) cannot be used for this any more: the silhouette is flushed into the frame itself, and with nothing glowing the
     * target is not even there - the thing turned white. So the rim is drawn here: the thing again, a hair larger and turned
     * inside out, so that only the back of that copy shows round it, behind it.
     */
    private static void drawOutlines() {
        if (PENDING_OUTLINES.isEmpty()) {
            return;
        }
        int color = 0xFF000000 | outlineColor & 0xFFFFFF;
        MultiBufferSource hulk = type -> new BackFaceConsumer(new TintedConsumer(SCENE.getBuffer(type), color));
        for (Consumer<MultiBufferSource> draw : PENDING_OUTLINES) {
            draw.accept(hulk);
        }
        PENDING_OUTLINES.clear();
    }

    /** Whether the player can do something with a node: carry it, operate it, click it, put or take an item. */
    private static boolean isUsable(NodeDefinition definition, ClientStation station) {
        if (definition.control().isPresent()) {
            return !FocusInput.isLockedControl(station, definition);
        }
        if (definition.grabbable().isPresent()) {
            return true;
        }
        if (definition.slot().isPresent()) {
            return !station.state().getItem(definition.slot().get().slot()).isEmpty();
        }
        if (definition.output().isPresent()) {
            return !station.state().getOutput(definition.output().get().output()).isEmpty();
        }
        // A node with a hit box of its own and something to see is there to be clicked.
        return definition.collider().isPresent() && definition.visual().isPresent();
    }

    /** A copy of the pose stack's top, for drawing later; grown a hair so that an outline drawn with it shows round it. */
    private static PoseStack snapshot(PoseStack poseStack, float grow) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(poseStack.last().pose());
        copy.last().normal().set(poseStack.last().normal());
        if (grow != 1.0F) {
            copy.last().pose().mul(new Matrix4f().scale(grow));
        }
        return copy;
    }

    /** Whether {@code station}'s scene is drawn from a camera at {@code camera}: it has one, and it is near enough. */
    public static boolean isDrawn(ClientStation station, Vec3 camera) {
        return station.scene() != null && station.center().distanceToSqr(camera) <= RENDER_RANGE * RENDER_RANGE;
    }

    private static void renderStation(ClientStation station, PoseStack poseStack, Vec3 camera, MultiBufferSource buffers,
            ClientLevel level) {
        drawnPos = station.pos();
        BlockPos pos = station.pos();
        int light = lightAt(level, pos);
        poseStack.pushPose();
        // The block's place relative to the camera, in doubles: on a moving structure its blocks are kept far away and the pose
        // brings them back, which floats would lose.
        multiply(poseStack, new Matrix4f(station.blockToWorld(new Matrix4d()).translateLocal(-camera.x, -camera.y, -camera.z)));
        multiply(poseStack, station.stationToWorld(new Matrix4f()));

        SceneInstance scene = station.scene();
        ui = scene.definition().ui();
        boolean local = FocusController.get().station() == station && FocusController.get().isInteractive();
        FocusInput input = FocusInput.get();
        FocusInput.Carried carried = local ? input.carried() : null;
        float workPlane = scene.definition().workPlane();
        MultiBufferSource shadows = new ShadowBuffers(buffers);
        List<float[]> lights = lightsOf(scene);

        for (NodeInstance node : scene.nodes()) {
            if (!node.isVisible()) {
                continue;
            }
            NodeDefinition definition = node.definition();
            if (DIAG_SHAPE && SHAPES_LOGGED.add(station.type().id() + " " + node.id())) {
                DiegesisMod.LOGGER.warn("DIAG node {} {} shape={} visual={} slot={} visible={}", station.type().id(), node.id(),
                        node.shapeTexture(), definition.visual().map(visual -> visual.item().map(Object::toString)
                                .orElseGet(() -> visual.model().map(Object::toString).orElse("block"))).orElse("-"),
                        definition.slot().map(slot -> slot.size() + "/" + slot.display()).orElse("-"), node.isVisible());
            }
            int nodeLight = lit(light, node.matrix().getTranslation(new Vector3f()), lights);
            if (node.placed() != null) {
                renderPlaced(node, local && ui.hoverOutline() && input.isHandFree(), poseStack, buffers, shadows, nodeLight, level);
                continue;
            }
            definition.control().flatMap(control -> control.base()).ifPresent(base -> {
                poseStack.pushPose();
                multiply(poseStack, node.restMatrix());
                renderVisual(base, node.tint(), poseStack, buffers, nodeLight, level);
                poseStack.popPose();
            });
            poseStack.pushPose();
            multiply(poseStack, node.matrix());
            // Only for an empty hand: while something is held, the things it passes over are not what the player uses.
            boolean pointedAt = local && ui.hoverOutline() && input.isHandFree() && node.isHovered() && !node.isCarried()
                    && !node.isOutlineByCode() && isUsable(definition, station);
            if (pointedAt || local && ui.hoverOutline() && (input.pourTarget() == node || node.isOutlined())) {
                outlineColor = ui.outlineColor();
                PoseStack at = snapshot(poseStack, OUTLINE_GROW);
                // Only things with a body of their own get the rim: a flat icon has none, and drawing it "inside out" left a
                // doubled mark instead of an outline (slot items keep the well's highlight).
                node.visual().ifPresent(visual -> PENDING_OUTLINES.add(target -> renderVisual(visual, 0xFFFFFFFF, at, target,
                        LightCoordsUtil.FULL_BRIGHT, level)));
            }
            boolean overAll = local && node.isOnTop() && SmoothPass.defer(overEverything(node, level), poseStack, nodeLight);
            if (!overAll) {
                node.visual().ifPresent(visual -> renderVisual(visual, node.tint(), poseStack, buffers, nodeLight, level));
                if (node.shape() != null) {
                    renderShape(node, poseStack, buffers, nodeLight);
                }
            }
            if (node.painter() != null && !(node.painter().smooth() && SmoothPass.defer(node.painter(), poseStack, nodeLight))) {
                node.painter().paint(poseStack, buffers, nodeLight);
            }
            if (!overAll) {
                definition.tank().ifPresent(binding -> renderTank(node, binding, poseStack, buffers, nodeLight));
            }
            definition.slot().ifPresent(slot -> {
                boolean marked = local && (node.isHovered() || input.isSpreadTarget(new Target.Station(slot.slot())));
                if (marked && slot.highlight()) {
                    renderHighlight(poseStack, buffers, slot.size() + 0.2F, node.isHovered() ? 1.0F : 0.66F);
                }
                ItemStack stack = station.state().getItem(slot.slot());
                renderSlotItem(stack, slot.display(), slot.shownItemSize(), slot.slot().index(), station.age(), poseStack, buffers, light,
                        level);
                boolean counted = slot.counts() == SlotBinding.Counts.ALWAYS || slot.counts() == SlotBinding.Counts.HOVER && local && node.isHovered();
                if (slot.display() != SlotBinding.ItemDisplay.HIDDEN && counted) {
                    renderCount(poseStack, buffers, stack, slot.size());
                }
            });
            definition.output().ifPresent(output -> {
                ItemStack stack = station.state().getOutput(output.output());
                renderSlotItem(stack, output.display(), output.size(), 0, station.age(), poseStack, buffers,
                        local && node.isHovered() ? LightCoordsUtil.FULL_BRIGHT : light, level);
                renderCount(poseStack, buffers, stack, output.size());
            });
            poseStack.popPose();
            if (node.isCarried() && node.visual().isPresent()) {
                // The carried object's own silhouette falls onto the work surface.
                projectShadow(poseStack, workPlane, node.carryPosition().y() - workPlane, () -> {
                    multiply(poseStack, node.matrix());
                    renderVisual(node.visual().get(), 0xFFFFFFFF, poseStack, shadows, light, level);
                });
            }
        }

        if (local && scene.inventoryLayout() != null && input.inventoryReveal() > 0.0F) {
            renderInventory(scene, input, poseStack, buffers, light, level);
        }
        if (carried != null && carried.inWorld() && carried.preview() != null) {
            renderPreview(carried, scene.definition().placing().orElseThrow(), poseStack, buffers, shadows, light, level);
        } else if (carried != null && carried.inWorld() && !station.isCarriedHidden()) {
            renderCarried(carried.stack(), carried.stationPos(), carried.lean(), carried.size(), poseStack, buffers, shadows, workPlane,
                    light, level);
            poseStack.pushPose();
            Vector3fc at = carried.stationPos();
            poseStack.translate(at.x(), at.y() + 0.4F, at.z());
            renderCount(poseStack, buffers, carried.stack(), carried.size() * 1.1F);
            poseStack.popPose();
        }
        for (ClientStation.RemoteHolding holding : station.remoteHoldings()) {
            if (!holding.item().isEmpty()) {
                renderCarried(holding.item(), holding.position(), holding.rotation(), 4.5F, poseStack, buffers, shadows, workPlane, light,
                        level);
            }
        }
        renderLightPools(scene, poseStack, buffers, workPlane);
        if (local) {
            renderPourHints(scene, input, poseStack, buffers);
        }
        if ((!station.particles().isEmpty() || !station.pourStreams().isEmpty()) && debugCamera != null) {
            Matrix4f toStation = new Matrix4f(station.blockToWorld(new Matrix4d()).setTranslation(0.0, 0.0, 0.0))
                    .mul(station.stationToWorld(new Matrix4f())).invert();
            Vector3f right = toStation.transformDirection(new Vector3f(debugCamera.leftVector()).negate()).normalize();
            Vector3f up = toStation.transformDirection(new Vector3f(debugCamera.upVector())).normalize();
            Vector3f view = toStation.transformDirection(new Vector3f(debugCamera.forwardVector())).normalize();
            if (scene.definition().pouring().stream()) {
                float width = scene.definition().pouring().streamWidth();
                boolean deferred = local && station.pourStreams().anyOverAll() && SmoothPass.defer(new NodePainter() {
                    @Override
                    public void paint(PoseStack pose, MultiBufferSource target, int packedLight) {
                        station.pourStreams().render(pose, target, view, packedLight, width, true);
                    }

                    @Override
                    public boolean smooth() {
                        return true;
                    }
                }, poseStack, light);
                station.pourStreams().render(poseStack, buffers, view, light, width, false);
                if (!deferred) {
                    station.pourStreams().render(poseStack, buffers, view, light, width, true);
                }
            }
            station.particles().render(poseStack, buffers, right, up, view, light);
        }
        if (DebugView.isOn()) {
            DebugView.render(station, poseStack, buffers, debugCamera, debugPose, camera);
        }
        poseStack.popPose();
    }

    /** An item standing freely: as it is, with an outline under the cursor and its shape falling on what it stands on. */
    private static void renderPlaced(NodeInstance node, boolean outline, PoseStack poseStack, MultiBufferSource buffers,
            MultiBufferSource shadows, int light, ClientLevel level) {
        PlacedItem item = node.placed();
        ItemStack stack = item.stack();
        PlacedShape shape = item.shape();
        ItemPose pose = item.pose();
        int seed = item.slot().index();
        poseStack.pushPose();
        multiply(poseStack, node.matrix());
        if (outline && node.isHovered()) {
            outlineColor = ui.outlineColor();
            PoseStack at = snapshot(poseStack, OUTLINE_GROW);
            PENDING_OUTLINES.add(target -> shape.draw(stack, at, target, LightCoordsUtil.FULL_BRIGHT, level, seed));
        }
        shape.draw(stack, poseStack, buffers, light, level, seed);
        poseStack.popPose();
        if (item.options().shadows() && shape.castsShadow() && pose != null && pose.on() < 0) {
            projectShadow(poseStack, pose.y(), 0.0F, () -> {
                multiply(poseStack, node.matrix());
                shape.draw(stack, poseStack, shadows, light, level, seed);
            });
        }
    }

    /** The stack in the hand, see-through, where it would stand; the place comes from the cursor, the look from the turn it has. */
    private static void renderPreview(FocusInput.Carried carried, PlacingOptions options, PoseStack poseStack, MultiBufferSource buffers,
            MultiBufferSource shadows, int light, ClientLevel level) {
        ItemPose pose = carried.preview();
        ItemStack stack = carried.stack();
        PlacedShape shape = PlacedShape.of(stack, level, options.blockModels());
        Vector3fc at = carried.stationPos();
        Matrix4f matrix = new Matrix4f().translation(at.x(), at.y(), at.z()).rotate(new org.joml.Quaternionf(pose.rotation()))
                .scale(shape.modelScale(pose.scale() * options.itemSize()));
        MultiBufferSource ghost = type -> {
            if (!isBlockSheet(type)) {
                return buffers.getBuffer(type);
            }
            if (type != Sheets.translucentBlockSheet()) {
                KeptTypes.keep(buffers, type);
            }
            return new TintedConsumer(buffers.getBuffer(Sheets.translucentBlockSheet()), options.previewColor());
        };
        poseStack.pushPose();
        multiply(poseStack, matrix);
        shape.draw(stack, poseStack, ghost, light, level, 0);
        poseStack.popPose();
        if (options.shadows() && shape.castsShadow() && pose.on() < 0) {
            projectShadow(poseStack, pose.y(), 0.0F, () -> {
                multiply(poseStack, matrix);
                shape.draw(stack, poseStack, shadows, light, level, 0);
            });
        }
    }

    private static boolean isBlockSheet(RenderType type) {
        return type == Sheets.cutoutBlockSheet() || type == Sheets.translucentBlockSheet() || type == Sheets.translucentItemSheet()
                || type == Sheets.cutoutBlockItemSheet() || type == Sheets.translucentBlockItemSheet();
    }

    private static void renderVisual(Visual visual, int tint, PoseStack poseStack, MultiBufferSource buffers, int light, ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        int packedLight = visual.emissive() ? LightCoordsUtil.FULL_BRIGHT : light;
        Vector3fc anchor = visual.anchor();
        if ((tint >>> 24) == 0) {
            return;
        }
        poseStack.pushPose();
        if (visual.item().isPresent()) {
            // The item's quads take no tint of their own: a tinted item (one offered see-through) is drawn through tinting buffers.
            poseStack.translate(8.0F - anchor.x(), 8.0F - anchor.y(), 8.0F - anchor.z());
            poseStack.scale(16.0F, 16.0F, 16.0F);
            MultiBufferSource drawn = tint == Visual.WHITE ? buffers : com.pockyl.diegesis.api.client.DiegesisClient.tinted(buffers, tint);
            drawItem(new ItemStack(visual.item().get()), ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, 0, poseStack,
                    drawn, level);
            poseStack.popPose();
            return;
        }
        if (visual.mesh().isPresent()) {
            renderMesh(visual, tint, poseStack, buffers, packedLight);
            poseStack.popPose();
            return;
        }
        BlockState state = visual.block().orElse(null);
        BlockStateModel model = null;
        QuadCollection modelQuads = null;
        boolean translucent = visual.translucent() || (tint >>> 24) < 0xFF;
        if (state != null) {
            model = minecraft.getModelManager().getBlockStateModelSet().get(state);
            translucent |= model.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
        } else {
            Identifier id = visual.model().orElseThrow();
            modelQuads = ClientPlatform.INSTANCE.getModel(id);
            if (modelQuads.getAll().isEmpty() && WARNED_MODELS.add(id)) {
                DiegesisMod.LOGGER.warn("Scene model {} is not loaded: put it under models/diegesis/ or register it with"
                        + " DiegesisClient.registerModel", id);
            }
            translucent |= modelQuads.hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
        }
        poseStack.translate(-anchor.x(), -anchor.y(), -anchor.z());
        poseStack.scale(16.0F, 16.0F, 16.0F);
        if (visual.litAsBlock() && !translucent && state != null && drawnPos != null && level != null) {
            // The way the chunks draw the block itself: its faces lit from the blocks around it, corners shaded, so that
            // the copy looks the same as the block it stands in for.
            VertexConsumer lit = buffers.getBuffer(Sheets.cutoutBlockSheet());
            drawLitBlock(model, state, drawnPos, level, poseStack, tint != Visual.WHITE ? new TintedConsumer(lit, tint) : lit);
            poseStack.popPose();
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(translucent ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet());
        if (tint != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, tint);
        }
        if (visual.glass() > 0.0F) {
            consumer = new GlassConsumer(consumer, visual.glass());
        }
        drawModel(model, modelQuads, poseStack.last(), consumer, packedLight);
        if (visual.doubleSided()) {
            // The same quads turned round, in the same render type: an open glass shows its far walls from inside.
            drawModel(model, modelQuads, poseStack.last(), new BackFaceConsumer(consumer), packedLight);
        }
        poseStack.popPose();
    }

    /** Draws a block state's model, or a model registered by id, flat lit as the scene asks. */
    private static void drawModel(BlockStateModel model, QuadCollection quads, PoseStack.Pose pose, VertexConsumer consumer, int light) {
        QUAD.setLightCoords(light);
        QUAD.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        QUAD.setColor(-1);
        if (model == null) {
            putQuads(quads.getAll(), pose, consumer);
            return;
        }
        PARTS.clear();
        try {
            MODEL_RANDOM.setSeed(42L);
            model.collectParts(MODEL_RANDOM, PARTS);
            for (BlockStateModelPart part : PARTS) {
                for (Direction side : SIDES) {
                    putQuads(part.getQuads(side), pose, consumer);
                }
            }
        } finally {
            PARTS.clear();
        }
    }

    private static void putQuads(List<BakedQuad> quads, PoseStack.Pose pose, VertexConsumer consumer) {
        for (int i = 0, size = quads.size(); i < size; i++) {
            consumer.putBakedQuad(pose, quads.get(i), QUAD);
        }
    }

    /**
     * A block state's model drawn as the chunks draw the block itself: {@code tesselateBlock} lights every face from the
     * blocks around it and shades the corners, the same way the block the station hides is lit.
     */
    private static void drawLitBlock(BlockStateModel model, BlockState state, BlockPos pos, ClientLevel level, PoseStack poseStack,
            VertexConsumer consumer) {
        BlockQuadOutput output = (x, y, z, quad, instance) -> {
            poseStack.pushPose();
            poseStack.translate(x, y, z);
            consumer.putBakedQuad(poseStack.last(), quad, instance);
            poseStack.popPose();
        };
        blockRenderer().tesselateBlock(output, 0.0F, 0.0F, 0.0F, level, pos, state, model, 42L);
    }

    private static ModelBlockRenderer blockRenderer() {
        if (blockRenderer == null) {
            Minecraft minecraft = Minecraft.getInstance();
            blockRenderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), false, minecraft.getBlockColors());
        }
        return blockRenderer;
    }

    /**
     * An item, drawn the way the game draws items: its model is resolved into a render state, and the quads it hands to a
     * submit node collector are written straight into the scene's buffers, with the item's own render type per quad (as
     * the game's item feature renderer does it).
     */
    private static void drawItem(ItemStack stack, ItemDisplayContext context, int light, int overlay, int seed, PoseStack poseStack,
            MultiBufferSource buffers, ClientLevel level) {
        if (stack.isEmpty()) {
            return;
        }
        ITEM_STATE.clear();
        itemModels().updateForTopItem(ITEM_STATE, stack, context, level, null, seed);
        if (ITEM_STATE.isEmpty()) {
            return;
        }
        SUBMIT_NODES.clear();
        try {
            ITEM_STATE.submit(poseStack, SUBMIT_NODES, light, overlay, 0);
            for (ItemSubmit submit : SUBMIT_NODES.order(0).getItemSubmits()) {
                drawItemSubmit(submit, buffers);
            }
        } finally {
            SUBMIT_NODES.clear();
        }
    }

    private static void drawItemSubmit(ItemSubmit submit, MultiBufferSource buffers) {
        QUAD.setLightCoords(submit.lightCoords());
        QUAD.setOverlayCoords(submit.overlayCoords());
        int[] tints = submit.tintLayers();
        for (BakedQuad quad : submit.quads()) {
            var material = quad.materialInfo();
            int index = material.tintIndex();
            QUAD.setColor(material.isTinted() && index >= 0 && index < tints.length ? tints[index] : -1);
            VertexConsumer consumer = buffers.getBuffer(material.itemRenderType());
            if (submit.foilType() != ItemStackRenderState.FoilType.NONE) {
                consumer = VertexMultiConsumer.create(buffers.getBuffer(RenderTypes.glint()), consumer);
            }
            consumer.putBakedQuad(submit.pose(), quad, QUAD);
        }
    }

    private static ItemModelResolver itemModels() {
        if (itemModels == null) {
            itemModels = new ItemModelResolver(Minecraft.getInstance().getModelManager());
        }
        return itemModels;
    }

    /** Whether the stack is drawn as ordinary quads, and not by a model the game draws in code (a chest, a banner). */
    private static boolean drawsQuads(ItemStack stack, ClientLevel level, PoseStack poseStack) {
        ITEM_STATE.clear();
        itemModels().updateForTopItem(ITEM_STATE, stack, ItemDisplayContext.FIXED, level, null, 0);
        if (ITEM_STATE.isEmpty()) {
            return false;
        }
        SUBMIT_NODES.clear();
        try {
            ITEM_STATE.submit(poseStack, SUBMIT_NODES, LightCoordsUtil.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            for (ItemSubmit submit : SUBMIT_NODES.order(0).getItemSubmits()) {
                if (!submit.quads().isEmpty()) {
                    return true;
                }
            }
            return false;
        } finally {
            SUBMIT_NODES.clear();
        }
    }

    /** A shape made by code: like a mesh, its texture from the node. */
    /** The liquid in a node's tank, and what floats on it; the pose stack is at the node. */
    private static void renderTank(NodeInstance node, TankBinding binding, PoseStack poseStack, MultiBufferSource buffers, int light) {
        // The world's up (tipped by sloshing) as the node sees it: the liquid's surface stays level.
        Vector3fc slosh = node.liquid().slosh();
        Matrix4f toNode = node.matrix().invert(new Matrix4f());
        Vector3f up = toNode.transformDirection(new Vector3f(slosh.x(), 1.0F, slosh.z()).normalize()).normalize();
        Vector3f sun = toNode.transformDirection(new Vector3f(LIGHT_DIRECTION)).normalize();
        LiquidRenderer.render(binding, node.liquid(), up, sun, poseStack, buffers, light);
        if (node.floating() != null) {
            node.floating().render(binding.inner(), node.liquid(), poseStack, buffers, light);
        }
    }

    /**
     * A node's own look drawn as a smooth painter: over the finished frame, where nothing of the scene or the world covers it. Its
     * tank's liquid comes along, inside its walls: a mug held low before a keg's tap is not cut by the floor.
     */
    private static NodePainter overEverything(NodeInstance node, ClientLevel level) {
        return new NodePainter() {
            @Override
            public void paint(PoseStack poseStack, MultiBufferSource buffers, int light) {
                node.visual().ifPresent(visual -> renderVisual(visual, node.tint(), poseStack, buffers, light, level));
                if (node.shape() != null) {
                    renderShape(node, poseStack, buffers, light);
                }
                node.definition().tank().ifPresent(binding -> renderTank(node, binding, poseStack, buffers, light));
            }

            @Override
            public boolean smooth() {
                return true;
            }
        };
    }

    private static void renderShape(NodeInstance node, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Sprite sprite = sprite(node.shapeTexture());
        if (DIAG_SHAPE && SHAPES_LOGGED.add(node.id())) {
            DiegesisMod.LOGGER.warn("DIAG shape {} texture {} sprite {} in the {} atlas", node.id(), node.shapeTexture(),
                    sprite.sprite().contents().name(), sprite.items() ? "items" : "blocks");
        }
        boolean seeThrough = (node.tint() >>> 24) < 0xFF;
        // The texture comes from the blocks' atlas or the items' one, and the batch must read the same one.
        VertexConsumer consumer = buffers.getBuffer(sprite.items()
                ? (seeThrough ? Sheets.translucentItemSheet() : Sheets.cutoutItemSheet())
                : (seeThrough ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet()));
        if (node.tint() != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, node.tint());
        }
        node.shape().mesh().draw(poseStack.last(), consumer, sprite.sprite(), node.shapeGlows() ? LightCoordsUtil.FULL_BRIGHT : light);
    }

    /** A polygon model: the same render types and wrappers as a block model, its corners written directly. */
    private static void renderMesh(Visual visual, int tint, PoseStack poseStack, MultiBufferSource buffers, int light) {
        Identifier id = visual.mesh().orElseThrow();
        com.pockyl.diegesis.impl.client.mesh.Mesh mesh = com.pockyl.diegesis.impl.client.mesh.MeshLibrary.INSTANCE.mesh(id);
        if (mesh == null) {
            if (WARNED_MODELS.add(id)) {
                DiegesisMod.LOGGER.warn("Scene mesh {} is not loaded: put it at assets/{}/diegesis/meshes/{}.obj", id, id.getNamespace(), id.getPath());
            }
            return;
        }
        Vector3fc anchor = visual.anchor();
        poseStack.translate(-anchor.x(), -anchor.y(), -anchor.z());
        boolean translucent = visual.translucent() || (tint >>> 24) < 0xFF;
        VertexConsumer consumer = buffers.getBuffer(translucent ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet());
        if (tint != Visual.WHITE) {
            consumer = new TintedConsumer(consumer, tint);
        }
        if (visual.glass() > 0.0F) {
            consumer = new GlassConsumer(consumer, visual.glass());
        }
        var sprite = blockSprite(visual.texture().orElseThrow());
        mesh.draw(poseStack.last(), consumer, sprite, light);
        if (visual.doubleSided()) {
            mesh.draw(poseStack.last(), new BackFaceConsumer(consumer), sprite, light);
        }
    }

    /**
     * While a vessel with liquid is in the hand, a soft mark on the opening of every tank that would take it, breathing
     * slowly; the one it points at is marked solidly.
     */
    private static void renderPourHints(SceneInstance scene, FocusInput input, PoseStack poseStack, MultiBufferSource buffers) {
        PourOptions options = scene.definition().pouring();
        List<NodeInstance> candidates = input.pourCandidates();
        if (!options.hints() || candidates.isEmpty()) {
            return;
        }
        float breath = 0.75F + 0.25F * (float) Math.sin(Util.getMillis() / 1000.0 * 3.0);
        int alpha = options.hintColor() >>> 24;
        for (NodeInstance node : candidates) {
            TankBinding tank = node.definition().tank().get();
            float scale = node.matrix().getScale(new Vector3f()).x;
            float size = Math.min(tank.inner().to().x() - tank.inner().from().x(), tank.inner().to().z() - tank.inner().from().z()) * scale;
            Vector3f mouth = node.matrix().transformPosition(tank.mouth());
            float strength = node == input.pourTarget() ? 1.6F : breath;
            poseStack.pushPose();
            poseStack.translate(mouth.x, mouth.y, mouth.z);
            renderSquare(poseStack, buffers, size, Math.min(255, Math.round(alpha * strength)) << 24 | options.hintColor() & 0xFFFFFF);
            poseStack.popPose();
        }
    }

    /**
     * The player's inventory laid out in the scene: a board, a sunken well per slot, the items lying in them with their
     * counts, and the vanilla highlight on the slot under the cursor.
     */
    private static void renderInventory(SceneInstance scene, FocusInput input, PoseStack poseStack, MultiBufferSource buffers,
            int light, ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        InventoryLayout layout = scene.inventoryLayout();
        layout.surface().ifPresent(surface -> {
            poseStack.pushPose();
            multiply(poseStack, layout.matrix());
            float width = layout.boardWidth();
            float depth = layout.boardDepth();
            poseStack.translate(-width / 2.0F, -BOARD_THICKNESS, -depth / 2.0F);
            poseStack.scale(width, BOARD_THICKNESS, depth);
            drawModel(minecraft.getModelManager().getBlockStateModelSet().get(surface), null, poseStack.last(),
                    buffers.getBuffer(Sheets.cutoutBlockSheet()), light);
            poseStack.popPose();
        });
        float well = layout.spacing() * 0.86F;
        float reveal = input.inventoryReveal();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            // Coming up row by row from the hotbar, each slot growing out of its centre.
            int row = slot < 9 ? 0 : 4 - slot / 9;
            float grow = Mth.clamp(reveal * 1.6F - row * 0.2F, 0.0F, 1.0F);
            if (grow <= 0.0F) {
                continue;
            }
            grow = 1.0F - (1.0F - grow) * (1.0F - grow);
            poseStack.pushPose();
            multiply(poseStack, scene.inventorySlot(slot));
            if (grow < 1.0F) {
                poseStack.scale(grow, grow, grow);
            }
            if ((layout.slotColor() >>> 24) > 0) {
                renderSquare(poseStack, buffers, well, layout.slotColor());
            }
            if (slot == input.hoveredInventorySlot() || input.isSpreadTarget(new Target.Inventory(slot))) {
                renderHighlight(poseStack, buffers, well, slot == input.hoveredInventorySlot() ? 1.0F : 0.66F);
            }
            ItemStack stack = minecraft.player.getInventory().getItem(slot);
            renderSlotItem(stack, SlotBinding.ItemDisplay.FLAT, layout.size(), slot, 0.0F, poseStack, buffers, light, level);
            renderCount(poseStack, buffers, stack, layout.spacing());
            poseStack.popPose();
        }
    }

    /**
     * A stack's count lying on the surface in the slot's near right corner (as the player sees it), like the number in
     * a vanilla slot. Nothing for single items.
     */
    private static void renderCount(PoseStack poseStack, MultiBufferSource buffers, ItemStack stack, float slotSize) {
        if (!ui.showCounts() || stack.isEmpty() || stack.getCount() <= 1) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        String text = String.valueOf(stack.getCount());
        float scale = slotSize * 0.42F / font.lineHeight;
        poseStack.pushPose();
        // The player's right is -x and the near side -z: the font's x runs to -x, its y (down the page) to -z.
        poseStack.translate(-slotSize / 2.0F, 0.12F, -slotSize / 2.0F);
        Matrix4f flat = new Matrix4f(-scale, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, -scale, 0.0F, 0.0F, scale, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
        Matrix4f matrix = new Matrix4f(poseStack.last().pose()).mul(flat);
        float x = -font.width(text) + 0.5F;
        float y = -font.lineHeight + 1.5F;
        Matrix4f shadow = new Matrix4f(matrix).translate(1.0F, 1.0F, 0.0F);
        // Lifted a hair above its shadow (the font's z points up here).
        Matrix4f front = new Matrix4f(matrix).translate(0.0F, 0.0F, 0.4F);
        poseStack.popPose();
        if (ui.countsOnTop()) {
            // Drawn after everything else, without the depth test: an item's model can never cover its number.
            PENDING_COUNTS.add(new Count(text, x, y, shadow, front));
            return;
        }
        font.drawInBatch(text, x, y, 0xFF3F3F3F, false, shadow, buffers, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
        font.drawInBatch(text, x, y, 0xFFFFFFFF, false, front, buffers, Font.DisplayMode.NORMAL, 0, LightCoordsUtil.FULL_BRIGHT);
    }

    /** Draws the counts collected this frame on top of the finished scene. */
    private static void flushCounts(SubmitNodeCollector collector) {
        if (PENDING_COUNTS.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        for (Count count : PENDING_COUNTS) {
            font.drawInBatch(count.text(), count.x(), count.y(), 0xFF3F3F3F, false, count.shadow(), COUNTS,
                    Font.DisplayMode.SEE_THROUGH, 0, LightCoordsUtil.FULL_BRIGHT);
        }
        for (Count count : PENDING_COUNTS) {
            font.drawInBatch(count.text(), count.x(), count.y(), 0xFFFFFFFF, false, count.front(), COUNTS,
                    Font.DisplayMode.SEE_THROUGH, 0, LightCoordsUtil.FULL_BRIGHT);
        }
        PENDING_COUNTS.clear();
        COUNTS.submit(collector, COUNTS_ORDER);
    }

    /** A count waiting to be drawn on top: its text, position in font units and matrices. */
    private record Count(String text, float x, float y, Matrix4f shadow, Matrix4f front) {
    }

    /** An item in a slot: lying, standing or floating, with a small per-slot turn so a full grid looks placed by hand. */
    private static void renderSlotItem(ItemStack stack, SlotBinding.ItemDisplay display, float size, int index, float age,
            PoseStack poseStack, MultiBufferSource buffers, int light, ClientLevel level) {
        if (stack.isEmpty()) {
            return;
        }
        boolean block = isBlockLike(stack, level);
        float jitter = ((index * 7919) % 25 - 12) * 0.7F;
        poseStack.pushPose();
        switch (display) {
            case FLAT -> {
                if (block) {
                    poseStack.translate(0.0F, size * 0.25F, 0.0F);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F + jitter));
                } else {
                    poseStack.translate(0.0F, 0.07F, 0.0F);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F + jitter));
                    poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
                }
            }
            case UPRIGHT -> {
                poseStack.translate(0.0F, block ? size * 0.25F : size * 0.5F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            }
            case FLOATING -> {
                poseStack.translate(0.0F, size * 0.55F + (float) Math.sin(age * 2.2F) * 0.35F, 0.0F);
                poseStack.mulPose(Axis.YP.rotationDegrees(age * 40.0F));
            }
            case HIDDEN -> {
                poseStack.popPose();
                return;
            }
        }
        poseStack.scale(size, size, size);
        drawItem(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, index, poseStack, buffers, level);
        poseStack.popPose();
    }

    /** A stack on the cursor (or another player's), leaning as it moves, with its silhouette as a shadow below. */
    private static void renderCarried(ItemStack stack, Vector3fc at, Vector3fc lean, float size, PoseStack poseStack,
            MultiBufferSource buffers, MultiBufferSource shadows, float workPlane, int light, ClientLevel level) {
        boolean block = isBlockLike(stack, level);
        Runnable place = () -> {
            poseStack.translate(at.x(), at.y(), at.z());
            poseStack.mulPose(Axis.XP.rotationDegrees(lean.x()));
            poseStack.mulPose(Axis.ZP.rotationDegrees(lean.z()));
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            if (!block) {
                // Tipped towards the camera, so it reads as held above the table rather than lying on it.
                poseStack.mulPose(Axis.XP.rotationDegrees(-62.0F));
            }
            float scale = size * 1.08F;
            poseStack.scale(scale, scale, scale);
        };
        poseStack.pushPose();
        place.run();
        drawItem(stack, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, 0, poseStack, buffers, level);
        poseStack.popPose();
        ItemStack plain = new ItemStack(stack.getItem());
        if (drawsQuads(plain, level, poseStack)) {
            projectShadow(poseStack, workPlane, at.y() - workPlane, () -> {
                place.run();
                drawItem(plain, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, 0, poseStack, shadows, level);
            });
        }
    }

    /**
     * Draws {@code draw} flattened onto the work plane along the light, as a shadow: geometry at height h above the
     * plane lands h * shear to the side. {@code height} is how high the thing is held; the shadow fades with it.
     */
    private static void projectShadow(PoseStack poseStack, float planeY, float height, Runnable draw) {
        if (!ui.shadows()) {
            return;
        }
        float alpha = Math.max(0.0F, ui.shadowOpacity() * (1.0F - Math.max(height, 0.0F) * 0.05F));
        if (alpha < 0.02F) {
            return;
        }
        ShadowBuffers.alpha = alpha;
        poseStack.pushPose();
        Matrix4f flatten = new Matrix4f().translate(0.0F, planeY + 0.06F, 0.0F);
        Matrix4f shear = new Matrix4f().m10(SHADOW_SHEAR_X).m11(0.0004F).m12(SHADOW_SHEAR_Z);
        flatten.mul(shear).translate(0.0F, -planeY, 0.0F);
        poseStack.last().pose().mul(flatten);
        draw.run();
        poseStack.popPose();
    }

    /** The highlight on a slot the cursor is on (strength 1) or a drag spreads over (weaker), like vanilla's. */
    private static void renderHighlight(PoseStack poseStack, MultiBufferSource buffers, float size, float strength) {
        int color = ui.highlightColor();
        int alpha = Math.round((color >>> 24) * strength);
        renderSquare(poseStack, buffers, size, alpha << 24 | color & 0xFFFFFF);
    }

    /** A flat square of colour on the surface at the slot (wells, highlights). */
    private static void renderSquare(PoseStack poseStack, MultiBufferSource buffers, float size, int argb) {
        TextureAtlasSprite sprite = blockSprite(HIGHLIGHT_SPRITE);
        VertexConsumer consumer = buffers.getBuffer(OVERLAY);
        PoseStack.Pose pose = poseStack.last();
        float h = size / 2.0F;
        float y = 0.04F;
        // Counter-clockwise seen from above, so the culled type keeps the top face.
        vertex(consumer, pose, -h, y, -h, sprite.getU0(), sprite.getV0(), argb);
        vertex(consumer, pose, -h, y, h, sprite.getU0(), sprite.getV1(), argb);
        vertex(consumer, pose, h, y, h, sprite.getU1(), sprite.getV1(), argb);
        vertex(consumer, pose, h, y, -h, sprite.getU1(), sprite.getV0(), argb);
    }

    private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z, float u, float v, int argb) {
        Vector3f point = pose.pose().transformPosition(new Vector3f(x, y, z));
        Vector3f normal = pose.normal().transform(new Vector3f(0.0F, 1.0F, 0.0F));
        consumer.addVertex(point.x, point.y, point.z, argb, u, v, OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, normal.x, normal.y,
                normal.z);
    }

    /**
     * Whether the stack stands as a block does (a block item, drawn from the side with the block light), and not as a flat
     * sprite: the model's own GUI light says so, as it did when the question was {@code isGui3d}.
     */
    private static boolean isBlockLike(ItemStack stack, ClientLevel level) {
        ITEM_STATE.clear();
        itemModels().updateForTopItem(ITEM_STATE, stack, ItemDisplayContext.FIXED, level, null, 0);
        return !ITEM_STATE.isEmpty() && ITEM_STATE.usesBlockLight();
    }

    /** A sprite of the block atlas, by its texture. */
    /**
     * A sprite by its texture, from the atlas that has it: 26.1 keeps item icons in an atlas of their own (they shared the blocks'
     * one before), and a shape built from an icon asks for a sprite by the icon's texture. Atlases are asked for by the name of
     * their configuration ("blocks"), not by the texture they end up in.
     */
    private static Sprite sprite(Identifier texture) {
        AtlasManager atlases = Minecraft.getInstance().getAtlasManager();
        TextureAtlasSprite sprite = atlases.getAtlasOrThrow(Identifier.withDefaultNamespace("blocks")).getSprite(texture);
        if (!sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            return new Sprite(sprite, false);
        }
        TextureAtlasSprite item = atlases.getAtlasOrThrow(Identifier.withDefaultNamespace("items")).getSprite(texture);
        return item.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? new Sprite(sprite, false) : new Sprite(item, true);
    }

    private static TextureAtlasSprite blockSprite(Identifier texture) {
        return sprite(texture).sprite();
    }

    /** A sprite and which atlas it was found in (the batch drawn with it must read that atlas). */
    private record Sprite(TextureAtlasSprite sprite, boolean items) {
    }

    /**
     * Light falling on the work surface under every light source: brighter and tighter the nearer the source is, wavering
     * with its flicker. Drawn so that it brightens the surface's own colours instead of laying a haze over them.
     */
    private static void renderLightPools(SceneInstance scene, PoseStack poseStack, MultiBufferSource buffers, float workPlane) {
        for (NodeInstance node : scene.nodes()) {
            if (node.lightLevel() <= 0 || !node.isVisible() || node.definition().light().isEmpty()) {
                continue;
            }
            LightDefinition light = node.definition().light().get();
            if ((light.color() >>> 24) == 0) {
                continue;
            }
            Vector3f at = node.matrix().transformPosition(new Vector3f(light.offset()));
            PlacingOptions placing = scene.definition().placing().orElse(null);
            if (placing == null) {
                renderPool(node, light, at, workPlane, null, poseStack, buffers);
                continue;
            }
            // The light stays in the room it is in: on the surface under it, not through the board to the one below.
            for (PlaceSurface surface : placing.surfaces()) {
                if (at.y >= surface.height() && at.y <= surface.height() + surface.clearance()) {
                    renderPool(node, light, at, surface.height(), surface, poseStack, buffers);
                    surface.wall().ifPresent(wall -> renderWallPool(node, light, at, wall, surface, poseStack, buffers));
                }
            }
        }
    }

    /** The same on the wall behind a surface: a warm patch round the source, within the room. */
    private static void renderWallPool(NodeInstance node, LightDefinition light, Vector3f at, float wall, PlaceSurface surface,
            PoseStack poseStack, MultiBufferSource buffers) {
        float distance = Math.max(0.0F, wall - at.z) / light.radius();
        if (distance >= 1.0F) {
            return;
        }
        float strength = node.lightLevel() / 15.0F * flicker(node, light) / (1.0F + 6.0F * distance * distance) * (1.0F - distance);
        float size = light.radius() * (0.45F + 0.75F * distance);
        float alpha = (light.color() >>> 24) / 255.0F * strength;
        if (alpha <= 0.01F) {
            return;
        }
        int rgb = light.color();
        int color = 0xFF000000 | Math.round((rgb >> 16 & 0xFF) * alpha) << 16 | Math.round((rgb >> 8 & 0xFF) * alpha) << 8
                | Math.round((rgb & 0xFF) * alpha);
        float x0 = Math.max(at.x - size, surface.from().x());
        float x1 = Math.min(at.x + size, surface.to().x());
        float y0 = Math.max(at.y - size, surface.height());
        float y1 = Math.min(at.y + size, surface.height() + surface.clearance());
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        float u0 = (x0 - at.x + size) / (size * 2.0F);
        float u1 = (x1 - at.x + size) / (size * 2.0F);
        float v0 = (y0 - at.y + size) / (size * 2.0F);
        float v1 = (y1 - at.y + size) / (size * 2.0F);
        VertexConsumer consumer = buffers.getBuffer(poolType());
        PoseStack.Pose pose = poseStack.last();
        float z = wall - 0.05F;
        vertex(consumer, pose, x0, y0, z, u0, v0, color);
        vertex(consumer, pose, x0, y1, z, u0, v1, color);
        vertex(consumer, pose, x1, y1, z, u1, v1, color);
        vertex(consumer, pose, x1, y0, z, u1, v0, color);
    }

    /** One pool of light at the height {@code plane}, cut off at the edges of {@code surface} (null: none). */
    private static void renderPool(NodeInstance node, LightDefinition light, Vector3f at, float plane, PlaceSurface surface,
            PoseStack poseStack, MultiBufferSource buffers) {
        float height = Math.max(0.0F, at.y - plane) / light.radius();
        if (height >= 1.0F) {
            return;
        }
        // Inverse-square-like: a source close to the surface lights a small bright spot, a high one a wide faint one.
        float strength = node.lightLevel() / 15.0F * flicker(node, light) / (1.0F + 6.0F * height * height) * (1.0F - height);
        float size = light.radius() * (0.45F + 0.75F * height);
        float alpha = (light.color() >>> 24) / 255.0F * strength;
        if (alpha <= 0.01F) {
            return;
        }
        int rgb = light.color();
        int color = 0xFF000000 | Math.round((rgb >> 16 & 0xFF) * alpha) << 16 | Math.round((rgb >> 8 & 0xFF) * alpha) << 8
                | Math.round((rgb & 0xFF) * alpha);
        float x0 = at.x - size;
        float x1 = at.x + size;
        float z0 = at.z - size;
        float z1 = at.z + size;
        if (surface != null) {
            x0 = Math.max(x0, surface.from().x());
            x1 = Math.min(x1, surface.to().x());
            z0 = Math.max(z0, surface.from().y());
            z1 = Math.min(z1, surface.to().y());
            if (x0 >= x1 || z0 >= z1) {
                return;
            }
        }
        float u0 = (x0 - at.x + size) / (size * 2.0F);
        float u1 = (x1 - at.x + size) / (size * 2.0F);
        float v0 = (z0 - at.z + size) / (size * 2.0F);
        float v1 = (z1 - at.z + size) / (size * 2.0F);
        VertexConsumer consumer = buffers.getBuffer(poolType());
        PoseStack.Pose pose = poseStack.last();
        float y = plane + 0.05F;
        vertex(consumer, pose, x0, y, z0, u0, v0, color);
        vertex(consumer, pose, x0, y, z1, u0, v1, color);
        vertex(consumer, pose, x1, y, z1, u1, v1, color);
        vertex(consumer, pose, x1, y, z0, u1, v0, color);
    }

    /** How strong a light is this frame (1 = steady): a flame's waver, smooth and different for every light. */
    private static float flicker(NodeInstance node, LightDefinition light) {
        if (light.flicker() <= 0.0F) {
            return 1.0F;
        }
        double t = Util.getMillis() / 1000.0;
        double phase = (node.id().hashCode() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double wave = 0.5 * Math.sin(t * 7.3 + phase) + 0.3 * Math.sin(t * 13.7 + phase * 2.0) + 0.2 * Math.sin(t * 29.1 + phase * 3.0);
        return 1.0F - light.flicker() * (float) (0.5 + 0.5 * wave);
    }

    private static RenderType poolType() {
        if (pool == null) {
            int size = 64;
            NativeImage image = new NativeImage(size, size, false);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    float distance = (float) Math.hypot(x + 0.5 - size / 2.0, y + 0.5 - size / 2.0) / (size / 2.0F);
                    float falloff = Mth.clamp(1.0F - distance, 0.0F, 1.0F);
                    falloff = falloff * falloff * falloff;
                    int value = Math.round(falloff * 255.0F);
                    // The falloff goes into the colour (it multiplies the surface); opaque, so nothing is discarded.
                    image.setPixel(x, y, 0xFF000000 | value << 16 | value << 8 | value);
                }
            }
            Identifier id = Identifier.withDefaultNamespace("diegesis_light_pool");
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "diegesis_light_pool", image));
            pool = DiegesisRenderTypes.lightPool(id);
        }
        return pool;
    }

    /** The scene's light sources this frame: x, y, z (station pixels), level (flicker included), radius. */
    private static List<float[]> lightsOf(SceneInstance scene) {
        List<float[]> lights = new ArrayList<>();
        for (NodeInstance node : scene.nodes()) {
            if (node.lightLevel() > 0 && node.isVisible() && node.definition().light().isPresent()) {
                LightDefinition light = node.definition().light().get();
                Vector3f at = node.matrix().transformPosition(new Vector3f(light.offset()));
                lights.add(new float[] {at.x, at.y, at.z, node.lightLevel() * flicker(node, light), light.radius()});
            }
        }
        return lights;
    }

    /** The station's light, brightened by the scene's light sources near {@code at} (they fade out over their radius). */
    private static int lit(int light, Vector3f at, List<float[]> lights) {
        if (lights.isEmpty()) {
            return light;
        }
        float block = LightCoordsUtil.block(light);
        for (float[] source : lights) {
            // Soft falloff (bright near the source, a long gentle tail) instead of a straight ramp.
            float near = Math.max(0.0F, 1.0F - at.distance(source[0], source[1], source[2]) / source[4]);
            block = Math.max(block, source[3] * near * (2.0F - near));
        }
        return LightCoordsUtil.pack(Math.min(15, Math.round(block)), LightCoordsUtil.sky(light));
    }

    /** The light at the station: the brighter of the block itself and the space above it (a table top is lit from above). */
    private static int lightAt(ClientLevel level, BlockPos pos) {
        int inside = LevelRenderer.getLightCoords(level, pos);
        int above = LevelRenderer.getLightCoords(level, pos.above());
        int block = Math.max(LightCoordsUtil.block(inside), LightCoordsUtil.block(above));
        int sky = Math.max(LightCoordsUtil.sky(inside), LightCoordsUtil.sky(above));
        return LightCoordsUtil.pack(block, sky);
    }

    /** Multiplies a matrix into the pose stack, normals included (PoseStack.mulPoseMatrix leaves them alone). */
    private static void multiply(PoseStack poseStack, Matrix4f matrix) {
        PoseStack.Pose last = poseStack.last();
        last.pose().mul(matrix);
        last.normal().mul(matrix.normalize3x3(new Matrix3f()));
    }

    /** Swallows what is drawn into it. */
    private static final class NoVertices implements VertexConsumer {
        static final NoVertices INSTANCE = new NoVertices();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
                float normalZ) {
        }
    }

    /** Sends everything drawn through it into one see-through black layer: the shadow of whatever is drawn. */
    private static final class ShadowBuffers implements MultiBufferSource {
        private static float alpha = 0.5F;
        private final MultiBufferSource buffers;

        ShadowBuffers(MultiBufferSource buffers) {
            this.buffers = buffers;
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (type != OVERLAY) {
                KeptTypes.keep(buffers, type);
            }
            int a = Math.round(alpha * 255.0F);
            // A shadow is the thing's own shape, laid flat: the texture it is taken from must be the one the batch reads. Since
            // 26.1 item icons live in an atlas of their own, an item's shadow goes into the items' see-through sheet.
            return new TintedConsumer(buffers.getBuffer(isItemsSheet(type) ? Sheets.translucentItemSheet() : OVERLAY), a << 24);
        }

        /** Whether {@code type} draws from the items' atlas rather than the blocks' one. */
        private static boolean isItemsSheet(RenderType type) {
            return type == Sheets.cutoutItemSheet() || type == Sheets.translucentItemSheet()
                    || type == Sheets.cutoutBlockItemSheet() || type == Sheets.translucentBlockItemSheet();
        }
    }
}
