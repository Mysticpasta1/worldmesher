package io.wispforest.worldmesher;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Vector3d;

import java.util.Map;

public class DynamicRenderInfo {

    public static DynamicRenderInfo EMPTY = new DynamicRenderInfo(ImmutableMap.of(), ImmutableMultimap.of());

    protected Map<BlockPos, BlockEntity> blockEntities;
    protected Multimap<Vector3d, EntityEntry> entities;

    public DynamicRenderInfo(Map<BlockPos, BlockEntity> blockEntities, Multimap<Vector3d, EntityEntry> entities) {
        this.blockEntities = ImmutableMap.copyOf(blockEntities);
        this.entities = ImmutableMultimap.copyOf(entities);
    }

    public Map<BlockPos, BlockEntity> blockEntities() {
        return this.blockEntities;
    }

    public Multimap<Vector3d, EntityEntry> entities() {
        return this.entities;
    }

    public boolean isEmpty() {
        return this.blockEntities.isEmpty() && this.entities.isEmpty();
    }

    public record EntityEntry(Entity entity, int light) {}

}
