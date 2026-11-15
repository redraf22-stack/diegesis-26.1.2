package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A block model drawn lit as the world lights the station's block ({@code Visual.litAsBlock}): the faces shaded as a block's
 * are, the light of the blocks around, and smooth lighting's shadows in the corners. A scene's copy of a block it hides then
 * looks like the block itself, and nothing changes as the two swap.
 *
 * <p>A model standing square to the world, as such a copy does, is drawn the way the chunks draw blocks, its faces turned to
 * the world's sides first so that each is lit from its own side. One turned some other way (a lid set aside, leaning) has no
 * corner shadows: each face takes the shade of the way it faces and the light in front of it.
 */
final class BlockLit {
    private static final float SQUARE = 0.9999F;
    /** The model turned by a quarter, a half and three quarters, made when first needed; dropped with the models. */
    private static final Map<BlockStateModel, BlockStateModel[]> TURNED = new WeakHashMap<>();
    /** The chunks' own lighting, but keeping every face: a copy shows all of its sides. */
    private static ModelBlockRenderer renderer;
    /** Handed to the renderer for one quad at a time, written out before the next. */
    private static final QuadInstance QUAD = new QuadInstance();

    private BlockLit() {
    }

    /**
     * Draws {@code model} at the pose (one unit a block, the model's corner at the origin) into {@code consumer}, of a chunk render
     * type, lit as {@code state} at {@code pos} would be.
     *
     * @param toWorld the pose's turn into the world: the level's view turned back
     */
    static void draw(BlockStateModel model, BlockState state, BlockPos pos, PoseStack poseStack, VertexConsumer consumer, Matrix4f toWorld) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        Matrix4f world = new Matrix4f(toWorld).mul(poseStack.last().pose());
        Vector3f x = world.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F)).normalize();
        Vector3f y = world.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
        boolean upright = y.y > SQUARE;
        boolean square = upright && (Math.abs(x.x) > SQUARE || Math.abs(x.z) > SQUARE);
        if (square) {
            // The turn the pose gives the model, by quarters about the world's up; the model is turned so instead, so that its
            // faces lie towards the world's sides they face and each is lit from its own.
            int quarters = Math.floorMod(Math.round((float) Math.atan2(-x.z, x.x) / Mth.HALF_PI), 4);
            renderer(minecraft).tesselateBlock((x1, y1, z1, quad, instance) -> put(poseStack, consumer, x1, y1, z1, quad, instance), 0.0F,
                    0.0F, 0.0F, level, pos, state, turned(model, quarters), 42L);
            return;
        }
        RandomSource random = RandomSource.create(42L);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts);
        for (BlockStateModelPart part : parts) {
            for (Direction side : SIDES) {
                for (BakedQuad quad : part.getQuads(side)) {
                    Vector3f normal = world.transformDirection(new Vector3f(quad.direction().step())).normalize();
                    float shade = quad.materialInfo().shade() ? diffuse(normal) : 1.0F;
                    // The light of the block the face looks into, or of the station's own where that one is solid.
                    BlockPos lit = pos.relative(Direction.getApproximateNearest(normal.x, normal.y, normal.z));
                    int light = LevelRenderer.getLightCoords(level, level.getBlockState(lit).isSolidRender() ? pos : lit);
                    QUAD.setColor(ARGB.colorFromFloat(1.0F, shade, shade, shade));
                    QUAD.setLightCoords(light);
                    consumer.putBakedQuad(poseStack.last(), quad, QUAD);
                }
            }
        }
    }

    /** Writes a quad the renderer lit, where the block's model sits: at the pose, moved by the block's own offset. */
    private static void put(PoseStack poseStack, VertexConsumer consumer, float x, float y, float z, BakedQuad quad, QuadInstance lit) {
        if (x == 0.0F && y == 0.0F && z == 0.0F) {
            consumer.putBakedQuad(poseStack.last(), quad, lit);
            return;
        }
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        consumer.putBakedQuad(poseStack.last(), quad, lit);
        poseStack.popPose();
    }

    private static ModelBlockRenderer renderer(Minecraft minecraft) {
        if (renderer == null) {
            // Smooth lighting on, culling off: every face of the copy is drawn, whether or not the world hides it.
            renderer = new ModelBlockRenderer(true, false, minecraft.getBlockColors());
        }
        return renderer;
    }

    private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
            null};

    /** The shade the world gives a face by the way it faces: 1 up, 0.5 down, 0.8 north and south, 0.6 east and west, between. */
    private static float diffuse(Vector3f normal) {
        float up = normal.y * normal.y * (3.0F + normal.y) / 4.0F;
        return Math.min(1.0F, normal.x * normal.x * 0.6F + up + normal.z * normal.z * 0.8F);
    }

    private static BlockStateModel turned(BlockStateModel model, int quarters) {
        if (quarters == 0) {
            return model;
        }
        BlockStateModel[] turns = TURNED.computeIfAbsent(model, key -> new BlockStateModel[4]);
        if (turns[quarters] == null) {
            turns[quarters] = new Turned(model, quarters);
        }
        return turns[quarters];
    }

    /** A model turned by quarters about the up through the middle of its block, its quads' sides with it. */
    private static final class Turned implements BlockStateModel {
        private final BlockStateModel model;
        private final int quarters;

        private Turned(BlockStateModel model, int quarters) {
            this.model = model;
            this.quarters = quarters;
        }

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
            int first = parts.size();
            model.collectParts(random, parts);
            for (int i = first; i < parts.size(); i++) {
                parts.set(i, new TurnedPart(parts.get(i), quarters));
            }
        }

        @Override
        public Material.Baked particleMaterial() {
            return model.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return model.materialFlags();
        }
    }

    /** One part of it: the quads of each side, turned, kept for the next frame. */
    private static final class TurnedPart implements BlockStateModelPart {
        private final BlockStateModelPart part;
        private final int quarters;
        private final Map<Direction, List<BakedQuad>> sides = new EnumMap<>(Direction.class);
        private List<BakedQuad> unsided;

        private TurnedPart(BlockStateModelPart part, int quarters) {
            this.part = part;
            this.quarters = quarters;
        }

        @Override
        public List<BakedQuad> getQuads(Direction side) {
            if (side == null) {
                if (unsided == null) {
                    unsided = turn(part.getQuads(null), quarters);
                }
                return unsided;
            }
            return sides.computeIfAbsent(side, key -> turn(part.getQuads(turnBack(key, quarters)), quarters));
        }

        @Override
        public boolean useAmbientOcclusion() {
            return part.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return part.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return part.materialFlags();
        }
    }

    private static Direction turnBack(Direction side, int quarters) {
        Direction back = side;
        for (int i = 0; i < quarters; i++) {
            back = back.getAxis().isHorizontal() ? back.getClockWise() : back;
        }
        return back;
    }

    private static List<BakedQuad> turn(List<BakedQuad> quads, int quarters) {
        List<BakedQuad> turned = new ArrayList<>(quads.size());
        float cos = Mth.cos(quarters * Mth.HALF_PI);
        float sin = Mth.sin(quarters * Mth.HALF_PI);
        for (BakedQuad quad : quads) {
            Vector3f[] corners = new Vector3f[BakedQuad.VERTEX_COUNT];
            for (int vertex = 0; vertex < BakedQuad.VERTEX_COUNT; vertex++) {
                Vector3fc corner = quad.position(vertex);
                float x = corner.x() - 0.5F;
                float z = corner.z() - 0.5F;
                corners[vertex] = new Vector3f(cos * x + sin * z + 0.5F, corner.y(), -sin * x + cos * z + 0.5F);
            }
            Vector3f facing = quad.direction().step();
            Direction side = Direction.getApproximateNearest(cos * facing.x + sin * facing.z, facing.y, -sin * facing.x + cos * facing.z);
            turned.add(new BakedQuad(corners[0], corners[1], corners[2], corners[3], quad.packedUV0(), quad.packedUV1(), quad.packedUV2(),
                    quad.packedUV3(), side, quad.materialInfo()));
        }
        return turned;
    }
}
