package com.pockyl.diegesis.impl.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4f;

import com.pockyl.diegesis.DiegesisMod;
import com.pockyl.diegesis.api.client.NodePainter;
import com.pockyl.diegesis.impl.client.focus.FocusScreen;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a {@link NodePainter#smooth() smooth} painter draws: kept from the level render and drawn over the finished frame from
 * the focus screen, twice as fine each way into a target of its own and scaled down, so its edges have no steps (the game draws
 * without anti-aliasing). Only the part of the screen it covers is drawn that fine.
 */
public final class SmoothPass {
    /**
     * Each way: 2 is four samples a pixel, the most a linear scaling down of the target can average, and the target costs a
     * quarter of a four times as fine one the same screen.
     */
    private static final int FINE = 2;
    /**
     * The most pixels the fine target may have, about that of a full 1080p screen twice as fine each way; past it a painter is
     * drawn at the screen's own resolution only.
     */
    private static final int MAX_FINE_PIXELS = 8_400_000;
    /** Pixels round the measured outline: the last samples of a slanted edge. */
    private static final int PAD = 2;

    private record Pending(NodePainter painter, Matrix4f pose, Matrix3f normal, int light) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static TextureTarget fine;
    private static ProjectionMatrixBuffer projections;
    private static boolean failed;
    /**
     * Whether the fine pass runs at all ({@code -Ddiegesis.smooth}). Off by default: in this port its batch was never flushed, and what
     * it did draw (the flying recipe book, the badge over the pot - both drawn last, over everything) came out dimmer than it is.
     * Off, a painter is drawn with the scene: crisp, at its own colours, only without the extra smoothing of its edges.
     */
    private static final boolean ON = Boolean.getBoolean("diegesis.smooth");
    private static MultiBufferSource.BufferSource buffers;

    private SmoothPass() {
    }

    /** A new level frame: what was kept and not drawn is dropped. {@code view}: the level's model-view matrix, the camera's turn. */
    static void begin(Matrix4f projection, Matrix4f view) {
        PENDING.clear();
        PROJECTION.set(projection);
        VIEW.set(view);
        projection.mul(view, VIEW_PROJECTION);
    }

    /**
     * Keeps a smooth painter for the focus screen to draw, at the pose it has now; false when it cannot be drawn smooth this
     * frame and must be drawn with the scene.
     */
    static boolean defer(NodePainter painter, PoseStack poseStack, int light) {
        if (!ON || failed || !(Minecraft.getInstance().screen instanceof FocusScreen)) {
            return false;
        }
        PENDING.add(new Pending(painter, new Matrix4f(poseStack.last().pose()), new Matrix3f(poseStack.last().normal()), light));
        return true;
    }

    /** Draws what was kept over the frame; called first thing by the focus screen, {@code graphics} being where it draws. */
    public static void draw(GuiGraphicsExtractor graphics) {
        if (PENDING.isEmpty()) {
            return;
        }
        try {
            drawPending(graphics);
        } catch (RuntimeException e) {
            failed = true;
            DiegesisMod.LOGGER.error("Smooth drawing failed; smooth painters are drawn with the scene from now on", e);
        } finally {
            PENDING.clear();
        }
    }

    /**
     * Draws what was kept over the frame, at the screen's own resolution only: for a caller that has no graphics of the frame to
     * lay it over, and so no box to draw it fine in.
     */
    public static void draw() {
        if (PENDING.isEmpty()) {
            return;
        }
        try {
            withMatrices(PROJECTION, null, () -> {
                MultiBufferSource.BufferSource source = buffers();
                for (Pending pending : PENDING) {
                    pending.painter().paint(poseOf(pending), source, pending.light());
                }
                source.endBatch();
            });
        } catch (RuntimeException e) {
            failed = true;
            DiegesisMod.LOGGER.error("Smooth drawing failed; smooth painters are drawn with the scene from now on", e);
        } finally {
            PENDING.clear();
        }
    }

    /** Frees the targets when no station is open. */
    public static void release() {
        if (fine != null) {
            fine.destroyBuffers();
            fine = null;
        }
        if (projections != null) {
            projections.close();
            projections = null;
        }
    }

    private static void drawPending(GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.getMainRenderTarget();
        Set<RenderType> measured = new LinkedHashSet<>();
        int[] box = measure(main.width, main.height, measured);
        if (box == null) {
            if (!measured.isEmpty()) {
                // Nothing on the screen; what the painters asked for is still drawn, empty (see KeptTypes), where it would be.
                withMatrices(PROJECTION, null, () -> keep(measured));
            }
            return;
        }
        int width = box[2] - box[0];
        int height = box[3] - box[1];
        int scale = (long) width * height * FINE * FINE <= MAX_FINE_PIXELS ? FINE : 1;
        fine = sized(fine, width * scale, height * scale);
        clear(fine);

        // The projection, narrowed to the box: the box fills the fine target.
        float left = 2.0F * box[0] / main.width - 1.0F;
        float right = 2.0F * box[2] / main.width - 1.0F;
        float bottom = 2.0F * box[1] / main.height - 1.0F;
        float top = 2.0F * box[3] / main.height - 1.0F;
        Matrix4f narrowed = new Matrix4f().scale(2.0F / (right - left), 2.0F / (top - bottom), 1.0F)
                .translate(-(left + right) / 2.0F, -(bottom + top) / 2.0F, 0.0F).mul(PROJECTION);

        withMatrices(narrowed, fine, () -> {
            MultiBufferSource source = buffers();
            for (Pending pending : PENDING) {
                pending.painter().paint(poseOf(pending), source, pending.light());
            }
            keep(measured);
        });

        // Lays the fine target over the frame in the box, scaled down by the graphics: its colours are premultiplied (drawn over
        // clear black), which a see-through blit keeps; the target's rows run up the screen, hence the turn of its texture.
        float guiScale = minecraft.getWindow().getGuiScale();
        float guiHeight = minecraft.getWindow().getGuiScaledHeight();
        graphics.blit(fine.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                Math.round(box[0] / guiScale), Math.round(guiHeight - box[3] / guiScale), Math.round(box[2] / guiScale),
                Math.round(guiHeight - box[1] / guiScale), 0.0F, 1.0F, 1.0F, 0.0F);
    }

    /**
     * Draws with {@code projection}, the view's model-view and the level's light, as the painters were posed for; into
     * {@code target}, or over the frame itself when that is null.
     */
    private static void withMatrices(Matrix4f projection, RenderTarget target, Runnable draw) {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection(projection), ProjectionType.PERSPECTIVE);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(VIEW);
        if (target != null) {
            RenderSystem.outputColorTextureOverride = target.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = target.getDepthTextureView();
        }
        try {
            draw.run();
        } finally {
            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /** The projection as the shaders take it, kept in a buffer of its own. */
    private static GpuBufferSlice projection(Matrix4f matrix) {
        if (projections == null) {
            projections = new ProjectionMatrixBuffer("diegesis smooth");
        }
        return projections.getBuffer(matrix);
    }

    /** Empties a target: what the painters do not cover stays clear, and its colours are hence premultiplied. */
    private static void clear(TextureTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(),
                1.0);
    }

    /** An own source, drawn in the painters' order: shader packs swap the game's for one that holds draws back. */
    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
        }
        return buffers;
    }

    /** Draws the types measuring took from the painters, then everything: see KeptTypes. */
    private static void keep(Set<RenderType> measured) {
        MultiBufferSource.BufferSource source = buffers();
        for (RenderType type : measured) {
            KeptTypes.keep(source, type);
        }
        source.endBatch();
    }

    private static PoseStack poseOf(Pending pending) {
        PoseStack poseStack = new PoseStack();
        poseStack.last().pose().set(pending.pose());
        poseStack.last().normal().set(pending.normal());
        return poseStack;
    }

    /** A target of this size, the one given when it fits. */
    private static TextureTarget sized(TextureTarget target, int width, int height) {
        if (target != null && target.width == width && target.height == height) {
            return target;
        }
        if (target != null) {
            target.resize(width, height);
            return target;
        }
        return new TextureTarget("Diegesis smooth", width, height, true);
    }

    /**
     * The box on the screen the kept painters cover, in framebuffer pixels from the bottom left: {x0, y0, x1, y1}; null when
     * they draw nothing on it. They draw once into a consumer that only measures; the types they ask for go into
     * {@code measured}, to be drawn after all.
     */
    private static int[] measure(int screenWidth, int screenHeight, Set<RenderType> measured) {
        Bounds bounds = new Bounds();
        MultiBufferSource measuring = type -> {
            measured.add(type);
            return bounds;
        };
        for (Pending pending : PENDING) {
            pending.painter().paint(poseOf(pending), measuring, pending.light());
        }
        if (bounds.empty()) {
            return null;
        }
        int x0 = Math.max(0, (int) Math.floor((bounds.minX * 0.5F + 0.5F) * screenWidth) - PAD);
        int y0 = Math.max(0, (int) Math.floor((bounds.minY * 0.5F + 0.5F) * screenHeight) - PAD);
        int x1 = Math.min(screenWidth, (int) Math.ceil((bounds.maxX * 0.5F + 0.5F) * screenWidth) + PAD);
        int y1 = Math.min(screenHeight, (int) Math.ceil((bounds.maxY * 0.5F + 0.5F) * screenHeight) + PAD);
        return x1 > x0 && y1 > y0 ? new int[] {x0, y0, x1, y1} : null;
    }

    /** Takes the vertices of a draw only to find where on the screen they land; one behind the camera covers it all. */
    private static final class Bounds implements VertexConsumer {
        private final Vector4f clip = new Vector4f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        boolean empty() {
            return minX > maxX || minY > maxY || maxX < -1.0F || minX > 1.0F || maxY < -1.0F || minY > 1.0F;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float normalX, float normalY,
                float normalZ) {
            addVertex(x, y, z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            VIEW_PROJECTION.transform(clip.set(x, y, z, 1.0F));
            if (clip.w <= 1.0e-4F) {
                minX = -1.0F;
                minY = -1.0F;
                maxX = 1.0F;
                maxY = 1.0F;
                return this;
            }
            float ndcX = Math.max(-1.0F, Math.min(1.0F, clip.x / clip.w));
            float ndcY = Math.max(-1.0F, Math.min(1.0F, clip.y / clip.w));
            minX = Math.min(minX, ndcX);
            minY = Math.min(minY, ndcY);
            maxX = Math.max(maxX, ndcX);
            maxY = Math.max(maxY, ndcY);
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
    }
}
