package com.pockyl.diegesis.impl.client.render;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * The library's own render types.
 *
 * <p>Minecraft 26.1 replaced render state shards with render pipelines: a render type is now a
 * pipeline plus its texture bindings, and blending/depth rules live inside the pipeline. The
 * three types this library needs are built from the closest stock pipelines.
 */
final class DiegesisRenderTypes {

    private DiegesisRenderTypes() {
    }

    /** A pool of light on a surface; the texture's colour is the light's falloff (black outside). */
    static RenderType lightPool(Identifier texture) {
        return RenderTypes.entityTranslucentEmissive(texture);
    }

    /**
     * An entity fading out of the way of the camera: like the translucent entity type, but it writes no depth, so that what
     * is drawn after it behind it (a scene) is not cut away by something nobody should see any more.
     */
    static RenderType ghostEntity(Identifier texture) {
        return RenderTypes.entityTranslucent(texture);
    }

    /**
     * See-through things that stand in each other: glass, the liquid in it, a stream running through.
     */
    static RenderType clearBlockSheet() {
        return RenderTypes.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    }
}
