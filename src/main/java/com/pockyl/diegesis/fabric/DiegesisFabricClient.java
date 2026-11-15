package com.pockyl.diegesis.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.UnbakedExtraModel;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.ModelState;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.SimpleModelWrapper;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import com.pockyl.diegesis.api.station.StationType;
import com.pockyl.diegesis.impl.StationRegistry;
import com.pockyl.diegesis.impl.client.DiegesisClientRuntime;
import com.pockyl.diegesis.impl.client.debug.DebugView;
import com.pockyl.diegesis.impl.client.hud.HudSlide;
import com.pockyl.diegesis.impl.client.render.SceneRenderer;
import com.pockyl.diegesis.impl.client.scene.SceneLibrary;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

public final class DiegesisFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        DiegesisClientRuntime.init();
        FabricNetwork.registerClient();
        ClientTickEvents.END_CLIENT_TICK.register(client -> DiegesisClientRuntime.tick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                ClientCommands.literal("diegesis").then(ClientCommands.literal("debug").executes(command -> {
                    DebugView.toggle();
                    return 1;
                }))));
        // What mods draw through the HUD hook slides away with the HUD as one part, around everybody's drawing.
        Identifier beforeHud = Identifier.fromNamespaceAndPath("diegesis", "hud_slide_begin");
        Identifier afterHud = Identifier.fromNamespaceAndPath("diegesis", "hud_slide_end");
        HudElementRegistry.addFirst(beforeHud, new HudElement() {
            @Override
            public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
                HudSlide.begin("fabric:hud", graphics);
            }
        });
        HudElementRegistry.addLast(afterHud, new HudElement() {
            @Override
            public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
                HudSlide.end(graphics);
            }
        });
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SceneReloadListener());
        PreparableModelLoadingPlugin.register(
                (net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state, Executor executor) -> CompletableFuture.supplyAsync(() -> SceneLibrary.modelsToLoad(state.resourceManager()), executor),
                (Set<Identifier> models, net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin.Context context) -> {
                    for (Identifier id : models) {
                        ExtraModelKey<QuadCollection> key = FabricClientPlatform.key(id);
                        context.addModel(key, new UnbakedExtraModel<QuadCollection>() {
                            @Override
                            public QuadCollection bake(ModelBaker baker) {
                                BlockStateModelPart part = SimpleModelWrapper.bake(baker, id, new ModelState() { });
                                QuadCollection.Builder builder = new QuadCollection.Builder();
                                for (Direction direction : Direction.values()) {
                                    for (var quad : part.getQuads(direction)) {
                                        builder.addCulledFace(direction, quad);
                                    }
                                }
                                for (var quad : part.getQuads(null)) {
                                    builder.addUnculledFace(quad);
                                }
                                return builder.build();
                            }

                            @Override
                            public void resolveDependencies(net.minecraft.client.resources.model.ResolvableModel.Resolver resolver) {
                                resolver.markDependency(id);
                            }
                        });
                    }
                    Set<net.minecraft.world.level.block.Block> hidden = new HashSet<>();
                    for (StationType type : StationRegistry.all()) {
                        if (type.hidesBlock()) {
                            Stream.concat(type.blocks().stream(), type.lateBlocks().stream()).forEach(hidden::add);
                        }
                    }
                    context.modifyBlockModelAfterBake().register((model, modify) -> model != null
                            && hidden.contains(modify.state().getBlock())
                            ? new HideableFabricModel(model) : model);
                });
    }

    /** The scene library under Fabric's reload listener id. */
    private static final class SceneReloadListener implements IdentifiableResourceReloadListener {
        @Override
        public Identifier getFabricId() {
            return SceneLibrary.ID;
        }

        @Override
        public CompletableFuture<Void> reload(net.minecraft.server.packs.resources.PreparableReloadListener.SharedState state,
                Executor backgroundExecutor, PreparationBarrier barrier, Executor gameExecutor) {
            return SceneLibrary.INSTANCE.reload(state, backgroundExecutor, barrier, gameExecutor);
        }

        @Override
        public String getName() {
            return SceneLibrary.ID.toString();
        }
    }
}
