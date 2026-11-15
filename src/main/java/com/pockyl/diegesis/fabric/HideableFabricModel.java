package com.pockyl.diegesis.fabric;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.diegesis.impl.client.HiddenBlocks;

import java.util.List;
import java.util.function.Predicate;

/** The model of a block a scene can stand in for: it draws nothing at the positions {@link HiddenBlocks} names. */
final class HideableFabricModel implements BlockStateModel, FabricBlockStateModel {
    private final BlockStateModel wrapped;

    HideableFabricModel(BlockStateModel wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random,
            Predicate<Direction> cullTest) {
        if (!HiddenBlocks.isHidden(pos)) {
            ((FabricBlockStateModel) wrapped).emitQuads(emitter, level, pos, state, random, cullTest);
        }
    }

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        wrapped.collectParts(random, parts);
    }

    @Override
    public Material.Baked particleMaterial() {
        return wrapped.particleMaterial();
    }

    @Override
    public int materialFlags() {
        return wrapped.materialFlags();
    }
}
