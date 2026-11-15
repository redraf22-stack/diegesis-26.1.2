package com.pockyl.diegesis.impl.client.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an item placed freely looks like and how big it really is. Any item goes: a block item with a flat icon stands there as
 * its block, everything else is drawn as the game draws it in an item frame. The box is measured on the model, so that an item
 * stands on its lowest point whichever way it is turned, and an apple that fills half its icon is as big as a sword.
 */
public final class PlacedShape {
    private static final Direction[] SIDES = Direction.values();
    private static final float THINNEST = 0.012F;
    /** A block's longest side counts for less than a sword's: it fills all of its box. */
    private static final float SOLID_SIZE = 0.8F;
    /** Models are new after a resource reload, and the sprites with them. */
    private static final Map<Key, PlacedShape> CACHE = new ConcurrentHashMap<>();

    private final BlockState block;
    private final List<BlockStateModelPart> parts;
    private final Vector3f center;
    private final Vector3f half;
    private final float unit;
    private final int light;
    private final boolean flame;
    private final boolean custom;

    private PlacedShape(BlockState block, List<BlockStateModelPart> parts, Box3 box, int light, boolean flame, boolean custom) {
        this.block = block;
        this.parts = parts;
        this.center = new Vector3f(box.min).add(box.max).mul(0.5F);
        this.half = new Vector3f(box.max).sub(box.min).mul(0.5F).max(new Vector3f(THINNEST));
        float longest = Math.max(half.x, Math.max(half.y, half.z));
        boolean solid = Math.min(half.x, Math.min(half.y, half.z)) > longest * 0.6F;
        this.unit = (solid ? SOLID_SIZE : 1.0F) * 0.5F / longest;
        this.light = light;
        this.flame = flame;
        this.custom = custom;
    }

    /** Models are new after a resource reload. */
    static void clearCache() {
        CACHE.clear();
    }

    public static PlacedShape of(ItemStack stack, ClientLevel level, boolean blockModels) {
        BlockState state = stack.getItem() instanceof BlockItem item ? item.getBlock().defaultBlockState() : null;
        if (state != null && state.hasProperty(BlockStateProperties.LIT)) {
            state = state.setValue(BlockStateProperties.LIT, true);
        }
        int light = state == null ? 0 : state.getLightEmission();
        boolean flame = state != null && (state.getBlock() instanceof TorchBlock || state.getBlock() instanceof AbstractCandleBlock);
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null) {
            return make(stack, level, blockModels, state, light, flame);
        }
        Key key = new Key(model, stack.getComponentsPatch(), blockModels, light, flame);
        BlockState shown = state;
        return CACHE.computeIfAbsent(key, ignored -> make(stack, level, blockModels, shown, light, flame));
    }

    private static PlacedShape make(ItemStack stack, ClientLevel level, boolean blockModels, BlockState state, int light, boolean flame) {
        ItemStackRenderState icon = filled(stack, level);
        if (blockModels && standsAsBlock(icon, state, stack)) {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = parts(model);
            return new PlacedShape(state, parts, blockBox(parts), light, flame, false);
        }
        Box3 box = new Box3();
        // A flat item's two big quads span the whole icon; the strips along its pixels' edges say what is really there.
        measure(icon, !icon.usesBlockLight(), box);
        boolean custom = box.isEmpty();
        return new PlacedShape(null, null, box.orElse(0.0F, 1.0F), light, flame, custom);
    }

    /** Seeds, string and the like are items of a block that looks like nothing the player holds; doors are half a door. */
    private static boolean standsAsBlock(ItemStackRenderState icon, BlockState state, ItemStack stack) {
        // Food that is a block item is still an item: since 26.1 a potato, a carrot and a beetroot are BlockItems (they are
        // planted), and standing them up as their crop would show a sprouting plant instead of the potato lying there.
        return state != null && stack.get(DataComponents.FOOD) == null && !icon.usesBlockLight()
                && state.getRenderShape() == RenderShape.MODEL && !state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF);
    }

    private static ItemStackRenderState filled(ItemStack stack, ClientLevel level) {
        ItemStackRenderState state = new ItemStackRenderState();
        resolver().updateForTopItem(state, stack, ItemDisplayContext.FIXED, level, null, 0);
        return state;
    }

    private static ItemModelResolver resolver() {
        return new ItemModelResolver(Minecraft.getInstance().getModelManager());
    }

    private static List<BlockStateModelPart> parts(BlockStateModel model) {
        List<BlockStateModelPart> parts = new ArrayList<>(4);
        if (model != null) {
            model.collectParts(RandomSource.create(42L), parts);
        }
        return parts;
    }

    private static Box3 blockBox(List<BlockStateModelPart> parts) {
        Box3 box = new Box3();
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                points(part.getQuads(side), box);
            }
            points(part.getQuads(null), box);
        }
        return box.orElse(0.0F, 1.0F);
    }

    private static void points(List<BakedQuad> quads, Box3 box) {
        for (BakedQuad quad : quads) {
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                box.add(quad.position(vertex));
            }
        }
    }

    /**
     * The model's box after the game's own display transform, in the space {@link #draw} starts from. The transform is read off
     * the item's render state, so it is the game's, not a copy of it.
     */
    private static void measure(ItemStackRenderState state, boolean edgesOnly, Box3 into) {
        SubmitNodeStorage submits = new SubmitNodeStorage();
        state.submit(new PoseStack(), submits, 0, 0, 0);
        Box3 all = new Box3();
        Box3 edges = new Box3();
        Vector3f point = new Vector3f();
        for (SubmitNodeCollection collection : submits.getSubmitsPerOrder().values()) {
            for (SubmitNodeStorage.ItemSubmit submit : collection.getItemSubmits()) {
                Matrix4f transform = submit.pose().pose();
                for (BakedQuad quad : submit.quads()) {
                    Direction direction = quad.direction();
                    boolean flat = direction != null && direction.getAxis() == Direction.Axis.Z;
                    for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                        point.set(quad.position(vertex));
                        transform.transformPosition(point);
                        all.add(point);
                        if (!flat) {
                            edges.add(point);
                        }
                    }
                }
            }
        }
        Box3 used = edgesOnly && !edges.isEmpty() ? edges : all;
        if (!used.isEmpty()) {
            into.add(used.min);
            into.add(used.max);
        }
    }

    /** Light the item gives, like a block's. */
    public int light() {
        return light;
    }

    /** Whether the item burns at its top: a torch, a candle. */
    public boolean hasFlame() {
        return flame;
    }

    /** Whether the item can be flattened into a shadow: what an entity renderer draws is on textures of its own. */
    public boolean castsShadow() {
        return !custom;
    }

    /** The factor from the model to pixels for an item of {@code size} pixels. */
    public float modelScale(float size) {
        return size * unit;
    }

    /** Half the item's box in the model, for its hit box. */
    public Vector3fc half() {
        return half;
    }

    /** Half the box round the item in scene pixels, turned as given. */
    public Vector3f extents(Quaternionfc rotation, float size) {
        Matrix4f turn = new Matrix4f().rotation(new Quaternionf(rotation));
        float scale = modelScale(size);
        return new Vector3f(
                Math.abs(turn.m00()) * half.x + Math.abs(turn.m10()) * half.y + Math.abs(turn.m20()) * half.z,
                Math.abs(turn.m01()) * half.x + Math.abs(turn.m11()) * half.y + Math.abs(turn.m21()) * half.z,
                Math.abs(turn.m02()) * half.x + Math.abs(turn.m12()) * half.y + Math.abs(turn.m22()) * half.z).mul(scale);
    }

    /** Draws the item with its middle at the pose stack's origin; the stack is in model units. */
    public void draw(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int packedLight, ClientLevel level, int seed) {
        poseStack.pushPose();
        poseStack.translate(-center.x, -center.y, -center.z);
        if (block != null) {
            drawBlock(poseStack, buffers, packedLight);
        } else {
            ItemStackRenderState state = new ItemStackRenderState();
            resolver().updateForTopItem(state, stack, ItemDisplayContext.FIXED, level, null, seed);
            state.submit(poseStack, new Drawer(buffers), packedLight, OverlayTexture.NO_OVERLAY, 0);
        }
        poseStack.popPose();
    }

    /** A block's model as the game draws it, in the block's own colours. */
    private void drawBlock(PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        QuadInstance instance = new QuadInstance();
        instance.setLightCoords(packedLight);
        instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        int color = tint(block);
        if (color != -1) {
            instance.setColor(color);
        }
        PoseStack.Pose pose = poseStack.last();
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                putBlock(buffers, pose, part.getQuads(side), instance);
            }
            putBlock(buffers, pose, part.getQuads(null), instance);
        }
    }

    private static void putBlock(MultiBufferSource buffers, PoseStack.Pose pose, List<BakedQuad> quads, QuadInstance instance) {
        for (BakedQuad quad : quads) {
            RenderType type = quad.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet();
            buffers.getBuffer(type).putBakedQuad(pose, quad, instance);
        }
    }

    /** The colour a block's model is drawn in, or -1 for a model of its own textures. */
    private static int tint(BlockState state) {
        List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
        return sources == null || sources.isEmpty() ? -1 : sources.get(0).color(state);
    }

    /** A key of what an item's model is: the model file and the data that shapes it. */
    private record Key(Identifier model, DataComponentPatch data, boolean blockModels, int light, boolean flame) {
    }

    /** A growing box. */
    private static final class Box3 {
        final Vector3f min = new Vector3f(Float.MAX_VALUE);
        final Vector3f max = new Vector3f(-Float.MAX_VALUE);

        void add(Vector3fc point) {
            min.min(point);
            max.max(point);
        }

        boolean isEmpty() {
            return min.x > max.x;
        }

        Box3 orElse(float low, float high) {
            if (isEmpty()) {
                add(new Vector3f(low));
                add(new Vector3f(high));
            }
            return this;
        }
    }

    /** Draws what the game submits for an item straight into the buffers of a scene, instead of a pass of its own. */
    private static final class Drawer implements SubmitNodeCollector {
        private final MultiBufferSource buffers;

        Drawer(MultiBufferSource buffers) {
            this.buffers = buffers;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order) {
            return this;
        }

        @Override
        public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int light, int overlay, int outlineColor,
                int[] tintLayers, List<BakedQuad> quads, ItemStackRenderState.FoilType foilType) {
            QuadInstance instance = new QuadInstance();
            instance.setLightCoords(light);
            instance.setOverlayCoords(overlay);
            PoseStack.Pose pose = poseStack.last();
            for (BakedQuad quad : quads) {
                BakedQuad.MaterialInfo material = quad.materialInfo();
                RenderType type = material.itemRenderType();
                if (type == null) {
                    type = Sheets.cutoutBlockItemSheet();
                }
                instance.setColor(tintOf(tintLayers, material));
                VertexConsumer consumer = foilType == ItemStackRenderState.FoilType.NONE
                        ? buffers.getBuffer(type)
                        : ItemFeatureRenderer.getFoilBuffer(buffers, type, true, true);
                consumer.putBakedQuad(pose, quad, instance);
            }
        }

        @Override
        public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> modelParts, int[] tintLayers,
                int light, int overlay, int outlineColor) {
            QuadInstance instance = new QuadInstance();
            instance.setLightCoords(light);
            instance.setOverlayCoords(overlay);
            VertexConsumer consumer = buffers.getBuffer(renderType);
            PoseStack.Pose pose = poseStack.last();
            for (BlockStateModelPart part : modelParts) {
                for (Direction side : SIDES) {
                    put(consumer, pose, part.getQuads(side), instance, tintLayers);
                }
                put(consumer, pose, part.getQuads(null), instance, tintLayers);
            }
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int light, int overlay,
                int color, TextureAtlasSprite sprite, int outlineColor, ModelFeatureRenderer.CrumblingOverlay crumbling) {
            VertexConsumer consumer = buffers.getBuffer(renderType);
            if (sprite != null) {
                consumer = sprite.wrap(consumer);
            }
            model.setupAnim(state);
            model.renderToBuffer(poseStack, consumer, light, overlay, color);
        }

        @Override
        public void submitModelPart(ModelPart part, PoseStack poseStack, RenderType renderType, int light, int overlay, TextureAtlasSprite sprite,
                boolean sheeted, boolean hasFoil, int color, ModelFeatureRenderer.CrumblingOverlay crumbling, int outlineColor) {
            VertexConsumer consumer = buffers.getBuffer(renderType);
            if (sprite != null) {
                consumer = sprite.wrap(consumer);
            }
            part.render(poseStack, consumer, light, overlay, color);
        }

        @Override
        public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            renderer.render(poseStack.last(), buffers.getBuffer(renderType));
        }

        @Override
        public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        }

        @Override
        public void submitNameTag(PoseStack poseStack, Vec3 attachment, int offset, Component name, boolean seeThrough, int light, double distance,
                CameraRenderState camera) {
        }

        @Override
        public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence text, boolean dropShadow, Font.DisplayMode mode,
                int light, int color, int backgroundColor, int outlineColor) {
        }

        @Override
        public void submitFlame(PoseStack poseStack, EntityRenderState state, Quaternionf rotation) {
        }

        @Override
        public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leash) {
        }

        @Override
        public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state) {
        }

        @Override
        public void submitBreakingBlockModel(PoseStack poseStack, BlockStateModel model, long seed, int progress) {
        }

        @Override
        public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) {
        }

        private static void put(VertexConsumer consumer, PoseStack.Pose pose, List<BakedQuad> quads, QuadInstance instance, int[] tintLayers) {
            for (BakedQuad quad : quads) {
                instance.setColor(tintOf(tintLayers, quad.materialInfo()));
                consumer.putBakedQuad(pose, quad, instance);
            }
        }

        private static int tintOf(int[] tintLayers, BakedQuad.MaterialInfo material) {
            int index = material.tintIndex();
            return material.isTinted() && index >= 0 && index < tintLayers.length ? tintLayers[index] : -1;
        }
    }
}
