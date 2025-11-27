package io.wispforest.worldmesher;

import com.google.common.collect.HashMultimap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.wispforest.worldmesher.renderers.WorldMesherBlockModelRenderer;
import io.wispforest.worldmesher.renderers.WorldMesherFluidRenderer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.commons.lang3.function.TriFunction;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

public class WorldMesh {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldMesh.class);

    private final BlockAndTintGetter world;
    private final BlockPos origin;
    private final BlockPos end;
    private final AABB dimensions;
    private final boolean cull;
    private final boolean useGlobalNeighbors;
    private final boolean freezeEntities;

    private final Runnable renderStartAction;
    private final Runnable renderEndAction;
    private final TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier;

    private DynamicRenderInfo renderInfo = DynamicRenderInfo.EMPTY;

    private boolean entitiesFrozen;
    public MeshState state = MeshState.NEW;
    private float buildProgress = 0;

    private CompletableFuture<Void> buildFuture = null;

    private final Map<RenderType, VertexBuffer> bufferStorage = new HashMap<>();

    public WorldMesh(BlockAndTintGetter world,
                     BlockPos origin,
                     BlockPos end,
                     boolean cull,
                     boolean useGlobalNeighbors,
                     boolean freezeEntities,
                     Runnable renderStartAction,
                     Runnable renderEndAction,
                     TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier) {

        this.world = world;
        this.origin = origin;
        this.end = end;
        this.cull = cull;
        this.useGlobalNeighbors = useGlobalNeighbors;
        this.freezeEntities = freezeEntities;

        this.dimensions = new AABB(new Vec3(origin.getX(), origin.getY(), origin.getZ()), new Vec3(end.getX(), end.getY(), end.getZ()));

        this.entitySupplier = entitySupplier;
        this.renderStartAction = renderStartAction;
        this.renderEndAction = renderEndAction;

        scheduleRebuild();
    }

    public void render(PoseStack matrices) {
        if (!this.state.canRender) {
            throw new IllegalStateException("World mesh not prepared!");
        }

        Matrix4f matrix = new Matrix4f(RenderSystem.getModelViewMatrix());
        matrix.mul(matrices.last().pose());

        RenderType translucent = RenderType.translucent();

        bufferStorage.forEach((layer, buffer) -> {
            if (layer == translucent) return;
            drawBuffer(buffer, layer, matrix);
        });

        if (bufferStorage.containsKey(translucent)) {
            drawBuffer(bufferStorage.get(translucent), translucent, matrix);
        }

        VertexBuffer.unbind();
    }

    private void drawBuffer(VertexBuffer vertexBuffer, RenderType renderLayer, Matrix4f matrix) {
        renderLayer.setupRenderState();
        renderStartAction.run();

        vertexBuffer.bind();
        vertexBuffer.drawWithShader(matrix, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());

        renderEndAction.run();
        renderLayer.clearRenderState();
    }


    public synchronized void scheduleRebuild() {
        scheduleRebuild(Util.backgroundExecutor());
    }

    public synchronized void scheduleRebuild(Executor executor) {
        if (buildFuture != null) return;

        buildProgress = 0;
        state = (state != MeshState.NEW ? MeshState.REBUILDING : MeshState.BUILDING);

        buildFuture =
                CompletableFuture.supplyAsync(this::buildAsync, executor)
                        .thenCompose(this::uploadOnRenderThread)
                        .whenComplete((v, t) -> {
                            buildFuture = null;
                            if (t == null) {
                                state = MeshState.READY;
                            } else {
                                LOGGER.warn("World mesh build failed", t);
                                state = MeshState.CORRUPT;
                            }
                        });

    }

    private CompletableFuture<Void> uploadOnRenderThread(Map<RenderType, BufferBuilder> builders) {
        return Minecraft.getInstance().submitAsync(() -> {

            bufferStorage.forEach((rt, vb) -> vb.close());
            bufferStorage.clear();

            builders.forEach((layer, bb) -> {
                MeshData md = bb.build();
                if (md == null) return;

                VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vb.bind();
                vb.upload(md);
                VertexBuffer.unbind();

                bufferStorage.put(layer, vb);
            });
        });
    }

    private Map<RenderType, BufferBuilder> buildAsync() {
        var client = Minecraft.getInstance();
        var blockRenderManager = client.getBlockRenderer();

        var blockRenderer = new WorldMesherBlockModelRenderer();

        var matrices = new PoseStack();
        Map<RenderType, BufferBuilder> builders = new HashMap<>();

        this.entitiesFrozen = this.freezeEntities;
        var entitiesFuture = new CompletableFuture<List<DynamicRenderInfo.EntityEntry>>();
        client.submit(() -> {
            entitiesFuture.complete(this.entitySupplier.apply(client.player, this.origin, this.end.offset(1, 1, 1))
                    .stream()
                    .map(entity -> {
                        if (this.freezeEntities) {
                            var originalEntity = entity;
                            entity = entity.getType().create(client.level);

                            entity.restoreFrom(originalEntity);
                            entity.copyPosition(originalEntity);
                            entity.tick();
                        }

                        return new DynamicRenderInfo.EntityEntry(
                                entity,
                                client.getEntityRenderDispatcher().getPackedLightCoords(entity, 0)
                        );
                    }).toList());
        });

        var blockEntities = new HashMap<BlockPos, BlockEntity>();

        int currentBlockIndex = 0;
        int blocksToBuild = (this.end.getX() - this.origin.getX() + 1)
                * (this.end.getY() - this.origin.getY() + 1)
                * (this.end.getZ() - this.origin.getZ() + 1);

        for (var pos : BlockPos.betweenClosed(this.origin, this.end)) {
            currentBlockIndex++;
            this.buildProgress = currentBlockIndex / (float) blocksToBuild;

            var state = world.getBlockState(pos);
            if (state.isAir()) continue;

            var renderPos = pos.subtract(origin);
            if (world.getBlockEntity(pos) != null) {
                blockEntities.put(renderPos, world.getBlockEntity(pos));
            }

            var fluidState = world.getFluidState(pos);
            if (!fluidState.isEmpty()) {

                RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluidState);

                matrices.pushPose();
                matrices.translate(-(pos.getX() & 15), -((pos.getY() & 15)), -((pos.getZ() & 15)));
                matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

                WorldMesherFluidRenderer.setMatrix(matrices.last().pose());
                WorldMesherFluidRenderer.render(
                        world, pos,
                        getOrCreateBuilder(builders, layer),
                        state, fluidState
                );
                matrices.popPose();
            }

            matrices.pushPose();
            matrices.translate(renderPos.getX(), renderPos.getY(), renderPos.getZ());

            blockRenderer.clearCullingOverrides();

            boolean alwaysDrawVolumeEdges = !this.useGlobalNeighbors;

            blockRenderer.setCullDirection(Direction.EAST, alwaysDrawVolumeEdges && pos.getX() == this.end.getX());
            blockRenderer.setCullDirection(Direction.WEST, alwaysDrawVolumeEdges && pos.getX() == this.origin.getX());
            blockRenderer.setCullDirection(Direction.SOUTH, alwaysDrawVolumeEdges && pos.getZ() == this.end.getZ());
            blockRenderer.setCullDirection(Direction.NORTH, alwaysDrawVolumeEdges && pos.getZ() == this.origin.getZ());
            blockRenderer.setCullDirection(Direction.UP, alwaysDrawVolumeEdges && pos.getY() == this.end.getY());
            blockRenderer.setCullDirection(Direction.DOWN, alwaysDrawVolumeEdges && pos.getY() == this.origin.getY());
            final var model = blockRenderManager.getBlockModel(state);

            var blockLayerTypes = model.getRenderTypes(state, RandomSource.create(), ModelData.EMPTY);

            for (RenderType layer : blockLayerTypes) {

                BufferBuilder bb = this.getOrCreateBuilder(builders, layer);

                long seed = state.getSeed(renderPos);
                RandomSource rs = RandomSource.create(seed);
                blockRenderer.tesselateBlock(
                        world,
                        model,
                        state,
                        pos,
                        matrices,
                        bb,
                        true,
                        rs,
                        seed,
                        OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY,
                        layer
                );
            }

            matrices.popPose();
        }

        var entities = HashMultimap.<Vector3d, DynamicRenderInfo.EntityEntry>create();
        for (var entityEntry : entitiesFuture.join()) {
            entities.put(
                    new Vector3d(
                            entityEntry.entity().position().subtract(this.origin.getX(), this.origin.getY(), this.origin.getZ()).x,
                            entityEntry.entity().position().subtract(this.origin.getX(), this.origin.getY(), this.origin.getZ()).y,
                            entityEntry.entity().position().subtract(this.origin.getX(), this.origin.getY(), this.origin.getZ()).z
                    ),
                    entityEntry
            );
        }

        this.renderInfo = new DynamicRenderInfo(
                blockEntities, entities
        );

        return builders;
    }


    private BufferBuilder getOrCreateBuilder(Map<RenderType, BufferBuilder> builders, RenderType layer) {
        return builders.computeIfAbsent(
                layer,
                rt -> new BufferBuilder(new ByteBufferBuilder(rt.bufferSize()), VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK)
        );
    }

    public float buildProgress() {
        return buildProgress;
    }

    public DynamicRenderInfo renderInfo() {
        return renderInfo;
    }

    public BlockPos startPos() {
        return origin;
    }

    public BlockPos endPos() {
        return end;
    }

    public AABB dimensions() {
        return dimensions;
    }

    public static class Builder {
        private final BlockAndTintGetter world;
        private final TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier;

        private final BlockPos origin;
        private final BlockPos end;
        private boolean cull = true;
        private boolean useGlobalNeighbors = false;
        private boolean freezeEntities = false;

        private Runnable startAction = () -> {
        };
        private Runnable endAction = () -> {
        };

        public Builder(BlockAndTintGetter world, BlockPos origin, BlockPos end, Function<Player, List<Entity>> entitySupplier) {
            this.world = world;
            this.origin = origin;
            this.end = end;
            this.entitySupplier = (player, $, $$) -> entitySupplier.apply(player);
        }

        public Builder(BlockAndTintGetter world, BlockPos origin, BlockPos end, TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier) {
            this.world = world;
            this.origin = origin;
            this.end = end;
            this.entitySupplier = entitySupplier;
        }

        public Builder(Level world, BlockPos origin, BlockPos end) {
            this(world, origin, end, (except, min, max) -> world.getEntities(except, new AABB(new Vec3(min.getX(), min.getY(), min.getZ()), new Vec3(max.getX(), max.getY(), max.getZ()))));
        }

        public Builder(BlockAndTintGetter world, BlockPos origin, BlockPos end) {
            this(world, origin, end, (except) -> List.of());
        }

        public Builder disableCulling() {
            this.cull = false;
            return this;
        }

        public Builder useGlobalNeighbors() {
            this.useGlobalNeighbors = true;
            return this;
        }

        public Builder freezeEntities() {
            this.freezeEntities = true;
            return this;
        }

        public Builder renderActions(Runnable startAction, Runnable endAction) {
            this.startAction = startAction;
            this.endAction = endAction;
            return this;
        }

        public WorldMesh build() {
            BlockPos start = new BlockPos(Math.min(origin.getX(), end.getX()), Math.min(origin.getY(), end.getY()), Math.min(origin.getZ(), end.getZ()));
            BlockPos target = new BlockPos(Math.max(origin.getX(), end.getX()), Math.max(origin.getY(), end.getY()), Math.max(origin.getZ(), end.getZ()));

            return new WorldMesh(world, start, target, cull, useGlobalNeighbors, freezeEntities, startAction, endAction, entitySupplier);
        }
    }

    public enum MeshState {
        NEW(false, false),
        BUILDING(true, false),
        REBUILDING(true, true),
        READY(false, true),
        CORRUPT(false, false);

        public final boolean isBuildStage;
        public final boolean canRender;

        MeshState(boolean bs, boolean cr) {
            this.isBuildStage = bs;
            this.canRender = cr;
        }
    }
}
