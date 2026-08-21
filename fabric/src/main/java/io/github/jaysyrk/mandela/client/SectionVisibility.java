package io.github.jaysyrk.mandela.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Answers "was this part of the world drawn this frame?" by reusing Minecraft's own answer.
 *
 * <p>The renderer already walks the chunk graph every frame and ends up with the set of sections
 * that survived frustum and occlusion culling -- that is how terrain behind a hill avoids being
 * drawn. Entities do not get the same treatment: a hundred mobs in a cave under a mountain are
 * submitted for rendering because they are in range and in front of the camera, even though every
 * one of them is behind solid rock.
 *
 * <p>Rather than tracing rays to work out what is hidden, this reads the set the renderer already
 * computed. An entity in a section that was not drawn is behind the same geometry that section was
 * behind. It costs one hash lookup, it is exactly as accurate as the terrain culling, and it needs
 * no occlusion queries or extra passes.
 *
 * <p>The trade is that section granularity is coarse: an entity leaning out of a hidden section into
 * a visible one would be culled wrongly. That is what the AABB spanning below is for, and what the
 * grace frames in the visibility cache are for.
 */
public final class SectionVisibility {

    private final LongOpenHashSet visible = new LongOpenHashSet(4096);
    private boolean populated;

    /** Replaces the set with the sections drawn this frame. */
    public void refresh(Iterable<SectionRenderDispatcher.RenderSection> sections) {
        visible.clear();
        if (sections == null) {
            populated = false;
            return;
        }
        for (SectionRenderDispatcher.RenderSection section : sections) {
            visible.add(section.getSectionNode());
        }
        populated = !visible.isEmpty();
    }

    public void clear() {
        visible.clear();
        populated = false;
    }

    /**
     * Whether the set is usable. Before the first frame, or in a dimension being loaded, it is
     * empty -- and an empty set would answer "nothing is visible", which would cull the world.
     */
    public boolean isPopulated() {
        return populated;
    }

    /**
     * Whether any section overlapping the box was drawn.
     *
     * <p>Spans the box rather than testing a single point because an entity standing at a section
     * boundary, or one taller than a section, genuinely occupies more than one. Erring towards
     * drawing costs a few microseconds; erring the other way is a mob that vanishes when it steps
     * over a line.
     */
    public boolean isVisible(AABB box) {
        if (!populated) {
            return true;
        }
        int minX = SectionPos.blockToSectionCoord(box.minX);
        int minY = SectionPos.blockToSectionCoord(box.minY);
        int minZ = SectionPos.blockToSectionCoord(box.minZ);
        int maxX = SectionPos.blockToSectionCoord(box.maxX);
        int maxY = SectionPos.blockToSectionCoord(box.maxY);
        int maxZ = SectionPos.blockToSectionCoord(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (visible.contains(SectionPos.asLong(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Whether the section containing this block position was drawn. */
    public boolean isVisible(int blockX, int blockY, int blockZ) {
        if (!populated) {
            return true;
        }
        return visible.contains(SectionPos.asLong(
                SectionPos.blockToSectionCoord(blockX),
                SectionPos.blockToSectionCoord(blockY),
                SectionPos.blockToSectionCoord(blockZ)));
    }

    public int visibleSectionCount() {
        return visible.size();
    }
}
