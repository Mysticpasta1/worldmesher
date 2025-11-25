package io.wispforest.worldmesher;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;
import java.util.function.Function;

public record WorldMesherRenderContext(BlockAndTintGetter level, Function<RenderType, VertexConsumer> buffers) {

    private static final float[] BRIGHTNESS = {1f, 1f, 1f, 1f};

    public void renderBlock(
            BlockPos pos,
            BlockState state,
            BakedModel model,
            PoseStack pose,
            RandomSource random,
            ModelData modelData
    ) {
        var types = model.getRenderTypes(state, random, modelData);

        for (RenderType type : types) {
            VertexConsumer consumer = buffers.apply(type);

            for (var dir : Direction.values()) {
                random.setSeed(state.getSeed(pos));
                List<BakedQuad> quads = model.getQuads(state, dir, random, modelData, type);

                for (BakedQuad quad : quads) {
                    writeQuad(consumer, pose.last(), quad);
                }
            }

            random.setSeed(state.getSeed(pos));
            List<BakedQuad> general = model.getQuads(state, null, random, modelData, type);

            for (BakedQuad quad : general) {
                writeQuad(consumer, pose.last(), quad);
            }
        }
    }

    private void writeQuad(VertexConsumer vc, PoseStack.Pose pose, BakedQuad quad) {
        int light = 0x00F000F0; // full brightness; you can replace with real lighting

        int[] lights = {light, light, light, light};

        vc.putBulkData(
                pose,
                quad,
                BRIGHTNESS,
                1f, 1f, 1f, 1f,
                lights,
                0,
                false
        );
    }
}
