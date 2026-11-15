package com.pockyl.diegesis.impl.client.mesh;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import com.pockyl.diegesis.api.client.MeshShape;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Items whose look is a {@link MeshShape} made from their data. The item keeps the model of its JSON file for its display
 * transforms; {@code ItemRendererMixin} fills the render state of a stack with the shape where the game asks for it, so
 * inventories, frames, hands and shelves all show the shape. Baked shapes are kept per item data, until the textures are
 * stitched anew.
 */
public final class ItemShapes {
    private static final int KEPT_SHAPES = 256;
    private static final Map<Item, Entry> ITEMS = new ConcurrentHashMap<>();
    /** Set while the game is asked for the model of a shaped item only to read the display transforms of that model. */
    private static boolean readingTransforms;

    private ItemShapes() {
    }

    public static void register(Item item, Function<ItemStack, MeshShape> shape, Identifier texture) {
        ITEMS.put(item, new Entry(shape, texture));
    }

    /**
     * Fills the layers of {@code state} with the shape of {@code stack}, as the game fills them for a model of its own. False
     * for every other item, which is drawn with its own model then.
     */
    public static boolean fill(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context,
            ClientLevel level) {
        Entry entry = ITEMS.isEmpty() ? null : ITEMS.get(stack.getItem());
        if (entry == null || readingTransforms) {
            return false;
        }
        Material.Baked material = entry.material();
        List<BakedQuad> quads = entry.quads(stack, material);
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        // The layer always shifts the model to its middle before the transform of its own: the game's transform already
        // carries that shift, so it is taken back out here rather than applied twice.
        layer.setItemTransform(ItemTransform.NO_TRANSFORM);
        layer.setLocalTransform(new Matrix4f().translation(0.5F, 0.5F, 0.5F).mul(entry.transform(stack, resolver, context, level)));
        layer.setUsesBlockLight(true);
        layer.setParticleMaterial(material);
        layer.setExtents(() -> CuboidItemModelWrapper.computeExtents(quads));
        layer.prepareQuadList().addAll(quads);
        return true;
    }

    /** Models are new after a resource reload, and the sprites with them. */
    public static void clearCache() {
        ITEMS.values().forEach(Entry::clear);
    }

    /**
     * The game's own transform for {@code context}: the display transform of the item's JSON model, read off the render state
     * the game builds for the stack.
     */
    private static Matrix4f displayTransform(ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context, ClientLevel level) {
        ItemStackRenderState state = new ItemStackRenderState();
        readingTransforms = true;
        try {
            resolver.updateForTopItem(state, stack, context, level, null, 0);
        } finally {
            readingTransforms = false;
        }
        SubmitNodeStorage submits = new SubmitNodeStorage();
        state.submit(new PoseStack(), submits, 0, 0, 0);
        for (SubmitNodeCollection collection : submits.getSubmitsPerOrder().values()) {
            for (SubmitNodeStorage.ItemSubmit submit : collection.getItemSubmits()) {
                return new Matrix4f(submit.pose().pose());
            }
        }
        return new Matrix4f();
    }

    private static final class Entry {
        private final Function<ItemStack, MeshShape> shape;
        private final Identifier texture;
        private final Map<DataComponentPatch, List<BakedQuad>> baked = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<DataComponentPatch, List<BakedQuad>> eldest) {
                return size() > KEPT_SHAPES;
            }
        };
        private final Map<Transform, Matrix4f> transforms = new ConcurrentHashMap<>();
        private TextureAtlasSprite sprite;
        private Material.Baked material;

        Entry(Function<ItemStack, MeshShape> shape, Identifier texture) {
            this.shape = shape;
            this.texture = texture;
        }

        synchronized Material.Baked material() {
            if (material == null) {
                material = new Material.Baked(sprite(), false);
            }
            return material;
        }

        private TextureAtlasSprite sprite() {
            if (sprite == null) {
                sprite = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(net.minecraft.resources.Identifier.withDefaultNamespace("blocks")).getSprite(texture);
            }
            return sprite;
        }

        synchronized List<BakedQuad> quads(ItemStack stack, Material.Baked material) {
            return baked.computeIfAbsent(stack.getComponentsPatch(), ignored -> List.copyOf(shape.apply(stack).mesh().quads(material.sprite())));
        }

        Matrix4f transform(ItemStack stack, ItemModelResolver resolver, ItemDisplayContext context, ClientLevel level) {
            return transforms.computeIfAbsent(new Transform(stack.get(DataComponents.ITEM_MODEL), context),
                    ignored -> displayTransform(stack, resolver, context, level));
        }

        synchronized void clear() {
            baked.clear();
            transforms.clear();
            sprite = null;
            material = null;
        }
    }

    /** What the display transform of a model depends on. */
    private record Transform(Identifier model, ItemDisplayContext context) {
    }
}
