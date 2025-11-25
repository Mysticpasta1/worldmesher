package io.wispforest.worldmesher;

import com.google.common.collect.HashMultimap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import io.wispforest.worldmesher.renderers.WorldMesherBlockModelRenderer;
import io.wispforest.worldmesher.renderers.WorldMesherFluidRenderer;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.apache.commons.lang3.function.TriFunction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

public class WorldMesh {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorldMesh.class);

    // Render setup data
    private final BlockAndTintGetter world;
    private final BlockPos origin;
    private final BlockPos end;
    private final AABB dimensions;

    private final boolean cull;
    private final boolean useGlobalNeighbors;

    private final Runnable renderStartAction;
    private final Runnable renderEndAction;

    private final TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier;
    private DynamicRenderInfo renderInfo = DynamicRenderInfo.EMPTY;
    private boolean entitiesFrozen;
    private boolean freezeEntities;

    // Build process data
    private MeshState state = MeshState.NEW;

    private float buildProgress = 0;
    private @Nullable CompletableFuture<Void> buildFuture = null;

    // Vertex storage
    private final Map<RenderType, VertexBuffer> bufferStorage = new HashMap<>();

    private WorldMesh(BlockAndTintGetter world, BlockPos origin, BlockPos end, boolean cull, boolean useGlobalNeighbors, boolean freezeEntities, Runnable renderStartAction, Runnable renderEndAction, TriFunction<Player, BlockPos, BlockPos, List<Entity>> entitySupplier) {
        this.world = world;
        this.origin = origin;
        this.end = end;

        this.cull = cull;
        this.useGlobalNeighbors = useGlobalNeighbors;
        this.freezeEntities = freezeEntities;
        this.dimensions = new AABB(new Vec3(this.origin.getX(), this.origin.getY(), this.origin.getZ()), new Vec3(this.end.getX(), this.end.getY(), this.end.getZ()));
        this.entitySupplier = entitySupplier;

        this.renderStartAction = renderStartAction;
        this.renderEndAction = renderEndAction;

        this.scheduleRebuild();
    }

    /**
     * Renders this world mesh into the current framebuffer, translated using the given matrix
     *
     * @param matrices The translation matrices. This is applied to the entire mesh
     */
    public void render(PoseStack matrices) {
        if (!this.canRender()) {
            throw new IllegalStateException("World mesh not prepared!");
        }

        var matrix = matrices.last().pose();
        var translucent = RenderType.translucent();

        this.bufferStorage.forEach((renderLayer, vertexBuffer) -> {
            if (renderLayer == translucent) return;
            this.drawBuffer(vertexBuffer, renderLayer, matrix);
        });

        if (this.bufferStorage.containsKey(translucent)) {
            this.drawBuffer(bufferStorage.get(translucent), translucent, matrix);
        }

        VertexBuffer.unbind();
    }

    private void drawBuffer(VertexBuffer vertexBuffer, RenderStateShard renderLayer, Matrix4f modelMatrix) {
        renderLayer.setupRenderState();
        this.renderStartAction.run();

        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            shader.MODEL_VIEW_MATRIX.set(modelMatrix);
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            shader.apply();
        }

        vertexBuffer.bind();
        vertexBuffer.draw();

        this.renderEndAction.run();
        renderLayer.clearRenderState();
    }

    /**
     * Checks whether this mesh is ready for rendering
     */
    public boolean canRender() {
        return this.state.canRender;
    }

    /**
     * Returns the current state of this mesh, used to indicate building progress and rendering availability
     *
     * @return The current {@code MeshState} constant
     */
    public MeshState state() {
        return this.state;
    }

    /**
     * Renamed to {@link #state()}
     */
    @Deprecated(forRemoval = true)
    public MeshState getState() {
        return this.state();
    }

    /**
     * How much of this mesh is built
     *
     * @return The build progress of this mesh
     */
    public float buildProgress() {
        return this.buildProgress;
    }

    /**
     * Renamed to {@link #buildProgress()}
     */
    @Deprecated(forRemoval = true)
    public float getBuildProgress() {
        return this.buildProgress();
    }

    /**
     * @return An object describing the entities and block
     * entities in the area this mesh is covering, with positions
     * relative to the mesh
     */
    public DynamicRenderInfo renderInfo() {
        return this.renderInfo;
    }

    /**
     * Renamed to {@link #renderInfo()}
     */
    @Deprecated(forRemoval = true)
    public DynamicRenderInfo getRenderInfo() {
        return this.renderInfo();
    }

    /**
     * @return The origin position of this mesh's area
     */
    public BlockPos startPos() {
        return this.origin;
    }

    /**
     * @return The end position of this mesh's area
     */
    public BlockPos endPos() {
        return this.end;
    }

    public boolean entitiesFrozen() {
        return this.entitiesFrozen;
    }

    public void setFreezeEntities(boolean freezeEntities) {
        this.freezeEntities = freezeEntities;
    }

    /**
     * @return The dimensions of this mesh's entire area
     */
    public AABB dimensions() {
        return dimensions;
    }

    /**
     * Reset this mesh to {@link MeshState#NEW}, releasing
     * all vertex buffers in the process
     */
    public void reset() {
        this.bufferStorage.forEach((renderLayer, vertexBuffer) -> vertexBuffer.close());
        this.bufferStorage.clear();

        this.state = MeshState.NEW;
    }

    /**
     * Renamed to {@link #reset()}
     */
    @Deprecated(forRemoval = true)
    public void clear() {
        this.reset();
    }

    /**
     * Schedule a rebuild of this mesh on
     * the main worker executor
     */
    public synchronized void scheduleRebuild() {
        this.scheduleRebuild(Util.backgroundExecutor());
    }

    /**
     * Schedule a rebuild of this mesh,
     * on the supplied executor
     *
     * @return A future completing when the build process is finished,
     * or {@code null} if this mesh is already building
     */
    public synchronized CompletableFuture<Void> scheduleRebuild(Executor executor) {
        if (this.buildFuture != null) return this.buildFuture;

        this.buildProgress = 0;
        this.state = this.state != MeshState.NEW
                ? MeshState.REBUILDING
                : MeshState.BUILDING;

        this.buildFuture = CompletableFuture.runAsync(this::build, executor).whenComplete((unused, throwable) -> {
            this.buildFuture = null;

            if (throwable == null) {
                state = MeshState.READY;
            } else {
                LOGGER.warn("World mesh building failed", throwable);
                state = MeshState.CORRUPT;
            }
        });

        return this.buildFuture;
    }

    private void build() {
        var client = Minecraft.getInstance();
        var blockRenderManager = client.getBlockRenderer();

        var blockRenderer = new WorldMesherBlockModelRenderer();

        var matrices = new PoseStack();
        var builderStorage = new HashMap<RenderType, BufferBuilder>();

        this.entitiesFrozen = this.freezeEntities;
        var entitiesFuture = new CompletableFuture<List<DynamicRenderInfo.EntityEntry>>();
        client.execute(() -> entitiesFuture.complete(this.entitySupplier.apply(client.player, this.origin, this.end.offset(1, 1, 1))
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
                }).toList()));

        var blockEntities = new HashMap<BlockPos, BlockEntity>();

        int currentBlockIndex = 0;
        int blocksToBuild = (this.end.getX() - this.origin.getX() + 1) * (this.end.getY() - this.origin.getY() + 1) * (this.end.getZ() - this.origin.getZ() + 1);

        for (BlockPos pos : BlockPos.betweenClosed(this.origin, this.end)) {
            ++currentBlockIndex;
            this.buildProgress = (float) currentBlockIndex / (float) blocksToBuild;
            BlockState state = this.world.getBlockState(pos);
            if (!state.isAir()) {
                BlockPos renderPos = pos.subtract(this.origin);
                if (this.world.getBlockEntity(pos) != null) {
                    blockEntities.put(renderPos, this.world.getBlockEntity(pos));
                }

                if (!this.world.getFluidState(pos).isEmpty()) {
                    FluidState fluidState = this.world.getFluidState(pos);
                    RenderType fluidLayer = ItemBlockRenderTypes.getRenderLayer(fluidState);
                    matrices.pushPose();
                    matrices.translate((float) (-(pos.getX() & 15)), (float) (-(pos.getY() & 15)), (float) (-(pos.getZ() & 15)));
                    matrices.translate((float) renderPos.getX(), (float) renderPos.getY(), (float) renderPos.getZ());
                    WorldMesherFluidRenderer.setMatrix(matrices.last().pose());
                    WorldMesherFluidRenderer.render(this.world, pos, this.getOrCreateBuilder(builderStorage, fluidLayer), state, fluidState);
                    matrices.popPose();
                }

                matrices.pushPose();
                var blockLayer = ItemBlockRenderTypes.getChunkRenderType(state);
                matrices.translate((float) renderPos.getX(), (float) renderPos.getY(), (float) renderPos.getZ());
                boolean alwaysDrawVolumeEdges = !this.useGlobalNeighbors;
                blockRenderer.clearCullingOverrides();
                blockRenderer.setCullDirection(Direction.EAST, alwaysDrawVolumeEdges && pos.getX() == this.end.getX());
                blockRenderer.setCullDirection(Direction.WEST, alwaysDrawVolumeEdges && pos.getX() == this.origin.getX());
                blockRenderer.setCullDirection(Direction.SOUTH, alwaysDrawVolumeEdges && pos.getZ() == this.end.getZ());
                blockRenderer.setCullDirection(Direction.NORTH, alwaysDrawVolumeEdges && pos.getZ() == this.origin.getZ());
                blockRenderer.setCullDirection(Direction.UP, alwaysDrawVolumeEdges && pos.getY() == this.end.getY());
                blockRenderer.setCullDirection(Direction.DOWN, alwaysDrawVolumeEdges && pos.getY() == this.origin.getY());
                BakedModel model = blockRenderManager.getBlockModel(state);
                if (state.getRenderShape() == RenderShape.MODEL) {
                    blockRenderer.renderModel(matrices.last(), this.getOrCreateBuilder(builderStorage, blockLayer), state, model, 1f, 1f, 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                }

                matrices.popPose();
            }

            if (renderContext != null && !model.isVanillaAdapter()) {
                renderContext.tessellateBlock(this.world, state, pos, model, matrices);
            } else if (state.getRenderType() == BlockRenderType.MODEL) {
                blockRenderer.render(this.world, (BakedModel) model, state, pos, matrices, this.getOrCreateBuilder(builderStorage, blockLayer), cull, random, state.getRenderingSeed(pos), OverlayTexture.DEFAULT_UV);
            }


        var future = new CompletableFuture<Void>();
        RenderSystem.recordRenderCall(() -> {
            this.bufferStorage.forEach((renderLayer, vertexBuffer) -> vertexBuffer.close());
            this.bufferStorage.clear();

            builderStorage.forEach((renderLayer, bufferBuilder) -> {
                var newBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);

                newBuffer.bind();
                newBuffer.upload(bufferBuilder.end());

                GlAllocationUtilsAccessor.worldmesher$getAllocator().free(
                        MemoryUtil.memAddress(((BufferBuilderAccessor) bufferBuilder).worldmesher$getBuffer(), 0)
                );

                // primarily here to inform ModernFix about what we did
                ((BufferBuilderAccessor) bufferBuilder).worldmesher$setBuffer(null);

                var discardedBuffer = this.bufferStorage.put(renderLayer, newBuffer);
                if (discardedBuffer != null) {
                    discardedBuffer.close();
                }
            });

            future.complete(null);
        });
        future.join();

        var entities = HashMultimap.<Vec3d, DynamicRenderInfo.EntityEntry>create();
        for (var entityEntry : entitiesFuture.join()) {
            entities.put(
                    entityEntry.entity().getPos().subtract(this.origin.getX(), this.origin.getY(), this.origin.getZ()),
                    entityEntry
            );
        }

        this.renderInfo = new DynamicRenderInfo(
                blockEntities, entities
        );
    }

    private static double distanceToCamera(BakedQuad quad, Vec3 cam) {
        int[] v = quad.getVertices();

        float x = Float.intBitsToFloat(v[0]);
        float y = Float.intBitsToFloat(v[1]);
        float z = Float.intBitsToFloat(v[2]);

        return cam.distanceTo(new Vec3(x, y, z));
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

        MeshState(boolean buildStage, boolean canRender) {
            this.isBuildStage = buildStage;
            this.canRender = canRender;
        }
    }
    }
