package io.wispforest.worldmesher.renderers;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import org.joml.Matrix4f;

public class WorldMesherFluidRenderer {

    private static Matrix4f matrix;

    public static void setMatrix(Matrix4f m) {
        matrix = m;
    }

    public static net.minecraft.client.renderer.RenderType layer(FluidState state) {
        return net.minecraft.client.renderer.RenderType.translucent();
    }

    private static TextureAtlasSprite getSprite(FluidState fs, boolean flowing) {
        IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fs.getType());
        ResourceLocation loc = flowing ? ext.getFlowingTexture() : ext.getStillTexture();
        return Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(loc);
    }

    private static int getTint(BlockAndTintGetter world, BlockPos pos, FluidState state) {
        if (state.is(FluidTags.LAVA)) return 0xFFFFFF;
        return BiomeColors.getAverageWaterColor(world, pos);
    }

    private static int light(BlockAndTintGetter w, BlockPos p) {
        return w.getLightEmission(p);
    }

    private static float height(BlockAndTintGetter w, Fluid f, BlockPos p, BlockState bs, FluidState fs) {
        return fs.getOwnHeight();
    }

    private static float heightAvg(float a, float b, float c, float d) {
        return (a + b + c + d) * 0.25f;
    }

    private static boolean same(FluidState a, FluidState b) {
        return b.getType() == a.getType();
    }

    private static boolean side(BlockAndTintGetter w, BlockPos p, FluidState fs, Direction dir) {
        BlockPos q = p.relative(dir);
        FluidState n = w.getFluidState(q);
        return !same(fs, n);
    }

    public static void render(BlockAndTintGetter world, BlockPos pos, VertexConsumer vc, BlockState bs, FluidState fs) {
        Fluid fluid = fs.getType();
        BlockPos d = pos.below();
        BlockPos u = pos.above();
        BlockPos n = pos.north();
        BlockPos s = pos.south();
        BlockPos w = pos.west();
        BlockPos e = pos.east();

        FluidState fd = world.getFluidState(d);
        FluidState fu = world.getFluidState(u);
        FluidState fn = world.getFluidState(n);
        FluidState fsu = world.getFluidState(s);
        FluidState fw = world.getFluidState(w);
        FluidState fe = world.getFluidState(e);

        boolean top = !same(fs, fu);
        boolean bottom = side(world, pos, fs, Direction.DOWN);
        boolean north = side(world, pos, fs, Direction.NORTH);
        boolean south = side(world, pos, fs, Direction.SOUTH);
        boolean west = side(world, pos, fs, Direction.WEST);
        boolean east = side(world, pos, fs, Direction.EAST);

        if (!(top || bottom || north || south || west || east)) return;

        float h0 = height(world, fluid, pos, bs, fs);
        float hN = height(world, fluid, n, world.getBlockState(n), fn);
        float hS = height(world, fluid, s, world.getBlockState(s), fsu);
        float hE = height(world, fluid, e, world.getBlockState(e), fe);
        float hW = height(world, fluid, w, world.getBlockState(w), fw);

        float hNE = heightAvg(h0, hN, hE, height(world, fluid, n.east(), world.getBlockState(n.east()), world.getFluidState(n.east())));
        float hNW = heightAvg(h0, hN, hW, height(world, fluid, n.west(), world.getBlockState(n.west()), world.getFluidState(n.west())));
        float hSE = heightAvg(h0, hS, hE, height(world, fluid, s.east(), world.getBlockState(s.east()), world.getFluidState(s.east())));
        float hSW = heightAvg(h0, hS, hW, height(world, fluid, s.west(), world.getBlockState(s.west()), world.getFluidState(s.west())));

        double x = pos.getX() & 15;
        double y = pos.getY() & 15;
        double z = pos.getZ() & 15;

        int tint = getTint(world, pos, fs);
        float r = ((tint >> 16) & 255) / 255f;
        float g = ((tint >> 8) & 255) / 255f;
        float b = (tint & 255) / 255f;

        TextureAtlasSprite still = getSprite(fs, false);
        TextureAtlasSprite flow = getSprite(fs, true);

        if (top) {
            float u0 = still.getU0();
            float u1 = still.getU1();
            float v0 = still.getV0();
            float v1 = still.getV1();
            int li = light(world, pos);
            vertex(vc, x, y + hNW, z, r, g, b, u0, v0, li);
            vertex(vc, x, y + hSW, z + 1, r, g, b, u0, v1, li);
            vertex(vc, x + 1, y + hSE, z + 1, r, g, b, u1, v1, li);
            vertex(vc, x + 1, y + hNE, z, r, g, b, u1, v0, li);
        }

        if (bottom) {
            float u0 = still.getU0();
            float u1 = still.getU1();
            float v0 = still.getV0();
            float v1 = still.getV1();
            int li = light(world, d);
            vertex(vc, x, y, z + 1, r, g, b, u0, v1, li);
            vertex(vc, x, y, z, r, g, b, u0, v0, li);
            vertex(vc, x + 1, y, z, r, g, b, u1, v0, li);
            vertex(vc, x + 1, y, z + 1, r, g, b, u1, v1, li);
        }

        if (north) side(world, pos, vc, r, g, b, flow, Direction.NORTH, x, y, z, hNW, hNE);
        if (south) side(world, pos, vc, r, g, b, flow, Direction.SOUTH, x, y, z, hSW, hSE);
        if (west) side(world, pos, vc, r, g, b, flow, Direction.WEST, x, y, z, hSW, hNW);
        if (east) side(world, pos, vc, r, g, b, flow, Direction.EAST, x, y, z, hSE, hNE);
    }

    private static void side(BlockAndTintGetter w, BlockPos pos, VertexConsumer vc, float r, float g, float b, TextureAtlasSprite s, Direction d, double x, double y, double z, float h0, float h1) {
        double x0 = d == Direction.EAST ? x + 1 - 0.001 : x + 0.001;
        double z0 = d == Direction.SOUTH ? z + 1 - 0.001 : z + 0.001;
        double x1 = x0;
        double z1 = z0;
        if (d == Direction.NORTH) { z0 = z + 0.001; z1 = z + 0.001; x0 = x; x1 = x + 1; }
        if (d == Direction.SOUTH) { z0 = z + 1 - 0.001; z1 = z + 1 - 0.001; x0 = x + 1; x1 = x; }
        if (d == Direction.WEST) { x0 = x + 0.001; x1 = x + 0.001; z0 = z + 1; z1 = z; }
        if (d == Direction.EAST) { x0 = x + 1 - 0.001; x1 = x + 1 - 0.001; z0 = z; z1 = z + 1; }

        float u0 = s.getU0();
        float u1 = s.getU1();
        float v0 = s.getV0();
        float v1 = s.getV1();
        int li = light(w, pos);

        vertex(vc, x0, y + h0, z0, r, g, b, u0, v0, li);
        vertex(vc, x1, y + h1, z1, r, g, b, u1, v0, li);
        vertex(vc, x1, y, z1, r, g, b, u1, v1, li);
        vertex(vc, x0, y, z0, r, g, b, u0, v1, li);
    }

    private static void vertex(VertexConsumer vc, double x, double y, double z, float r, float g, float b, float u, float v, int li) {
        vc.addVertex(matrix, (float)x, (float)y, (float)z).setColor(r, g, b, 1f).setUv(u, v).setLight(li).setNormal(0, 1, 0);
    }
}
