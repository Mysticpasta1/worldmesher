package io.wispforest.worldmesher.renderers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;

import java.util.BitSet;
import java.util.List;

public class WorldMesherBlockModelRenderer extends ModelBlockRenderer {

    private static final Direction[] DIRS = Direction.values();
    private byte overrides = 0;

    public WorldMesherBlockModelRenderer() {
        super(Minecraft.getInstance().getBlockColors());
    }

    public static RenderType getLayer(BlockState state, BakedModel model, RandomSource random, ModelData data) {
        var list = model.getRenderTypes(state, random, data);
        return list.isEmpty() ? RenderType.solid() : list.asList().getFirst();
    }

    public void setCullDirection(Direction d, boolean draw) {
        if (draw) overrides |= (byte)(1 << d.ordinal());
    }

    public void clearCullingOverrides() {
        overrides = 0;
    }

    private boolean always(Direction d) {
        return (overrides & (1 << d.ordinal())) != 0;
    }

    @Override
    public void tesselateWithAO(
            @NotNull BlockAndTintGetter level,
            @NotNull BakedModel model,
            @NotNull BlockState state,
            @NotNull BlockPos pos,
            @NotNull PoseStack pose,
            @NotNull VertexConsumer consumer,
            boolean checkSides,
            @NotNull RandomSource random,
            long seed,
            int packedOverlay,
            @NotNull ModelData modelData,
            @NotNull RenderType renderType
    ) {
        float[] shape = new float[DIRECTIONS.length * 2];
        BitSet shapeFlags = new BitSet(3);
        ModelBlockRenderer.AmbientOcclusionFace aoFace = new ModelBlockRenderer.AmbientOcclusionFace();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (Direction d : DIRS) {
            random.setSeed(seed);
            List<BakedQuad> quads = model.getQuads(state, d, random, modelData, renderType);
            if (!quads.isEmpty()) {
                m.setWithOffset(pos, d);
                if (!checkSides || always(d) || Block.shouldRenderFace(state, level, pos, d, m)) {
                    this.renderModelFaceAO(level, state,
                            always(d) ? pos.offset(0, 500, 0) : pos,
                            pose, consumer, quads, shape, shapeFlags, aoFace, packedOverlay);
                }
            }
        }

        random.setSeed(seed);
        List<BakedQuad> general = model.getQuads(state, null, random, modelData, renderType);
        if (!general.isEmpty()) {
            this.renderModelFaceAO(level, state, pos, pose, consumer, general, shape, shapeFlags, aoFace, packedOverlay);
        }
    }

    @Override
    public void tesselateWithoutAO(
            @NotNull BlockAndTintGetter level,
            @NotNull BakedModel model,
            @NotNull BlockState state,
            @NotNull BlockPos pos,
            @NotNull PoseStack poseStack,
            @NotNull VertexConsumer consumer,
            boolean checkSides,
            @NotNull RandomSource random,
            long seed,
            int packedOverlay,
            @NotNull ModelData modelData,
            @NotNull RenderType renderType
    ) {
        BitSet shapeFlags = new BitSet(3);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (Direction d : DIRS) {
            random.setSeed(seed);
            List<BakedQuad> quads = model.getQuads(state, d, random, modelData, renderType);
            if (!quads.isEmpty()) {
                m.setWithOffset(pos, d);
                if (!checkSides || always(d) || Block.shouldRenderFace(state, level, pos, d, m)) {
                    int light = LevelRenderer.getLightColor(level, state, m);
                    this.renderModelFaceFlat(level, state,
                            always(d) ? pos.offset(0, 500, 0) : pos,
                            light, packedOverlay, false,
                            poseStack, consumer, quads, shapeFlags);
                }
            }
        }

        random.setSeed(seed);
        List<BakedQuad> general = model.getQuads(state, null, random, modelData, renderType);
        if (!general.isEmpty()) {
            this.renderModelFaceFlat(level, state, pos,
                    -1, packedOverlay, true,
                    poseStack, consumer, general, shapeFlags);
        }
    }
}
