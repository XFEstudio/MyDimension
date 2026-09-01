package com.xfestudio.mydimension.client.builder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.xfestudio.mydimension.MyDimension;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Persistent builder-preview geometry grouped into 16x16x16 render sections.
 *
 * <p>Every section owns three static VBOs: shader-pack-safe quad outlines,
 * translucent block models, and the animated six-face rift veil. A new
 * snapshot reuses unchanged sections instead of discarding all GPU geometry;
 * this is important while a large blueprint is shrinking every server tick.</p>
 */
final class BuilderPreviewSectionMeshCache {
    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_SIZE = 1 << SECTION_SHIFT;
    private static final double FRUSTUM_GUARD = 1.5D;
    private static final double OUTLINE_HALF_WIDTH = 0.010D;
    private static final int WAVE_CELLS_PER_UPLOAD = 256;
    private static final int MODEL_CELLS_PER_UPLOAD = 128;
    /** Prevents adjacent projected models from owning two exactly coplanar faces. */
    private static final float GHOST_MODEL_INSET = 0.0025F;
    private static final Set<ResourceLocation> WARNED_MODEL_TYPES = new HashSet<>();
    private static final Set<ResourceLocation> WARNED_FACE_CULL_TYPES = new HashSet<>();

    @Nullable
    private BuilderPreviewState.Snapshot source;
    private Map<SectionKey, SectionMesh> sections = new LinkedHashMap<>();
    private final Set<SectionMesh> activeGhostUploads = identitySet();
    @Nullable
    private PendingGeneration pending;
    /**
     * Only the newest desired snapshot is retained while a generation is uploading. Replacing an
     * in-flight generation for every server tick would continuously throw away its partial VBOs;
     * a shrinking blueprint could then remain frozen forever without publishing even one section.
     */
    private final LatestWinsQueue<BuilderPreviewState.Snapshot> queued = new LatestWinsQueue<>();

    /** Stages lightweight cell groups; the currently rendered generation remains untouched. */
    synchronized void advance(@Nullable BuilderPreviewState.Snapshot snapshot, int ignoredCellBudget) {
        synchronize(snapshot);
    }

    /** Returns in-range, frustum-visible sections nearest-first. */
    synchronized List<SectionMesh> visibleSections(
            net.minecraft.client.renderer.culling.Frustum frustum,
            Vec3 camera, double maximumDistanceSqr) {
        List<SectionMesh> visible = new ArrayList<>();
        for (SectionMesh section : sections.values()) {
            if (section.closestDistanceToSqr(camera) > maximumDistanceSqr) continue;
            if (frustum.isVisible(section.bounds().inflate(FRUSTUM_GUARD))) visible.add(section);
        }
        visible.sort(Comparator.comparingDouble(section -> section.distanceToSqr(camera)));
        return visible;
    }

    /**
     * Computes the concrete-model working set once for an active frame.  Sections touching the
     * distance-limited seed set across an occupied projection boundary form a one-section guard
     * band.  Preparation and rendering consume this same immutable result, so neither side can
     * make a subtly different decision at the distance boundary.
     */
    synchronized ModelResidency modelResidency(Vec3 camera, double modelDistanceSqr) {
        Set<SectionKey> keys = modelResidentSectionKeys(sections, camera, modelDistanceSqr);
        Set<SectionMesh> meshes = identitySet();
        for (SectionKey key : keys) {
            SectionMesh section = sections.get(key);
            if (section != null) meshes.add(section);
        }
        return new ModelResidency(keys, meshes);
    }

    /**
     * Builds section back buffers without exposing partial VBOs. Sections whose shared boundary
     * changed are published as one dependency group; unrelated sections still publish
     * independently. This prevents a target-side culled face from briefly meeting an old
     * neighbour while retaining bounded nearest-section latency for large blueprints.
     *
     * @return the number of upload-budget slots consumed
     */
    synchronized int preparePending(Minecraft minecraft, Vec3 camera, double modelDistanceSqr,
                                    int uploadBudget, long deadlineNanos) {
        PendingGeneration generation = pending;
        if (generation == null) return 0;

        Set<SectionKey> modelResidency = modelResidentSectionKeys(
                generation.sections(), camera, modelDistanceSqr);
        generation.initializePublicationGroups(modelResidency);
        promoteReadyGroups(generation, modelResidency);
        if (generation.fullyPublished()) {
            finishGeneration(generation);
            return 0;
        }
        if (uploadBudget <= 0) return 0;

        List<Map.Entry<SectionKey, SectionMesh>> ordered =
                new ArrayList<>(generation.sections().entrySet());
        ordered.sort(Comparator.comparingDouble(
                entry -> entry.getValue().closestDistanceToSqr(camera)));
        int uploaded = 0;
        while (uploaded < uploadBudget && System.nanoTime() < deadlineNanos) {
            boolean progressed = false;
            for (Map.Entry<SectionKey, SectionMesh> entry : ordered) {
                if (!generation.requiresPublication(entry.getKey())
                        || generation.isPublished(entry.getKey())) continue;
                SectionMesh section = entry.getValue();
                boolean requireModels = requireGhostCompletion(
                        modelResidency.contains(entry.getKey()),
                        section.ghostUploadStarted());
                if (section.ready(requireModels)) continue;
                int uploadCost = section.uploadNext(minecraft, requireModels);
                if (uploadCost > 0) {
                    uploaded += uploadCost;
                    progressed = true;
                    // Spend the next slot on this same nearest section. It can therefore publish
                    // in ceil(sectionUploads / frameBudget) frames instead of waiting behind every
                    // section in a 65k-cell blueprint.
                    break;
                }
            }
            if (!progressed) break;
        }

        if (pending == generation) {
            promoteReadyGroups(generation, modelResidency);
            if (generation.fullyPublished()) {
                finishGeneration(generation);
            }
        }
        return uploaded;
    }

    /**
     * Uploads active concrete models selected by {@code residency}.  A section whose model upload
     * already started remains in the work queue after leaving residency so it cannot become a
     * permanently partial VBO.  Rendering still filters it through the original residency set.
     */
    synchronized void prepareVisibleSections(Minecraft minecraft, ModelResidency residency,
                                             Vec3 camera, int uploadBudget,
                                             long deadlineNanos) {
        if (uploadBudget <= 0) return;
        Set<SectionMesh> candidates = identitySet();
        for (SectionMesh section : residency.meshes()) {
            if (!section.ghostModelsComplete()) candidates.add(section);
        }
        candidates.addAll(activeGhostUploads);
        List<SectionMesh> work = new ArrayList<>(candidates);
        work.sort(Comparator.comparingDouble(section -> section.closestDistanceToSqr(camera)));
        int uploaded = 0;
        for (SectionMesh section : work) {
            if (uploaded >= uploadBudget) break;
            if (uploaded > 0 && System.nanoTime() >= deadlineNanos) break;
            boolean buildModels = requireGhostCompletion(
                    residency.contains(section), section.ghostUploadStarted());
            int uploadCost = section.uploadNext(minecraft, buildModels);
            if (uploadCost > 0) {
                uploaded += uploadCost;
                if (section.ghostUploadInProgress()) activeGhostUploads.add(section);
                else activeGhostUploads.remove(section);
            }
        }
    }

    /** Snapshot whose VBO generation is currently safe to draw. */
    @Nullable
    synchronized BuilderPreviewState.Snapshot renderSnapshot(
            @Nullable BuilderPreviewState.Snapshot requested) {
        return source == null ? requested : source;
    }

    synchronized boolean represents(@Nullable BuilderPreviewState.Snapshot snapshot) {
        return snapshot == null
                ? source == null && pending == null && queued.isEmpty()
                : source != null && sameGeometry(source, snapshot)
                && pending == null && queued.isEmpty();
    }

    synchronized void clear() {
        Set<SectionMesh> closed = identitySet();
        sections.values().forEach(mesh -> closeOnce(mesh, closed));
        if (pending != null) {
            pending.sections().values().forEach(mesh -> closeOnce(mesh, closed));
        }
        source = null;
        sections.clear();
        activeGhostUploads.clear();
        pending = null;
        queued.clear();
    }

    private void synchronize(@Nullable BuilderPreviewState.Snapshot snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        if (source == snapshot && pending == null && queued.isEmpty()
                || pending != null && pending.source() == snapshot && queued.isEmpty()) return;

        // Old-world geometry must never survive a dimension handoff. This is the only update
        // that intentionally bypasses the back-buffer transition.
        if (source != null && !Objects.equals(source.dimension(), snapshot.dimension())
                || pending != null && !Objects.equals(
                pending.source().dimension(), snapshot.dimension())) {
            clear();
        }

        // Explicit completion/cancellation is already a fully prepared result. It must not wait
        // behind an in-flight upload, and it is also the lifecycle escape hatch used when the
        // player puts the scepter away or leaves the world.
        if (snapshot.cells().isEmpty()) {
            discardPending();
            queued.clear();
            sections.values().forEach(SectionMesh::close);
            sections = new LinkedHashMap<>();
            activeGhostUploads.clear();
            source = snapshot;
            return;
        }

        if (pending != null) {
            if (sameGeometry(pending.source(), snapshot)) {
                // The newest packet returned to the in-flight target. Keep its current VBO work
                // and discard a now-stale deferred target.
                pending.source = snapshot;
                queued.clear();
                return;
            }
            if (source != null && canFastRevertToSource(
                    sameGeometry(source, snapshot), pending.mutatedActive)) {
                // No staged section has been published yet, so returning to the committed frame
                // can safely cancel the transition without generating anything else.
                discardPending();
                pending = null;
                queued.clear();
                source = snapshot;
                return;
            }

            // Coalesce high-frequency progress snapshots. In particular, do not regroup 65k
            // cells or regenerate wave adjacency every tick while the current target is still
            // uploading. Once it completes, finishGeneration starts exactly this latest target.
            queued.offer(snapshot);
            return;
        }

        if (source != null && sameGeometry(source, snapshot)) {
            source = snapshot;
            return;
        }

        stageSnapshot(snapshot);
    }

    /** Creates one immutable target generation. Called only when no other target is in flight. */
    private void stageSnapshot(BuilderPreviewState.Snapshot snapshot) {

        // Surface builds and blueprint deployments both show the concrete material preview.
        // The rift-wave overlay remains restricted to missing-material cells by isWaveCell().
        boolean includeBuildGhosts = true;

        Map<SectionKey, List<BuilderPreviewState.Cell>> grouped = new LinkedHashMap<>();
        LongSet missingGhostPositions = new LongOpenHashSet();
        ProjectionBlockGetter projectionBlocks = new ProjectionBlockGetter(snapshot.cells(),
                includeBuildGhosts);
        FaceVisibilityContext faceVisibility = new FaceVisibilityContext(projectionBlocks);
        for (BuilderPreviewState.Cell cell : snapshot.cells()) {
            grouped.computeIfAbsent(SectionKey.of(cell), ignored -> new ArrayList<>()).add(cell);
            if (isWaveCell(cell)) missingGhostPositions.add(cell.pos().asLong());
        }

        Map<SectionKey, SectionMesh> replacement = new LinkedHashMap<>(grouped.size());
        for (Map.Entry<SectionKey, List<BuilderPreviewState.Cell>> entry : grouped.entrySet()) {
            SectionKey key = entry.getKey();
            List<WaveCell> waveCells = createWaveCells(entry.getValue(), missingGhostPositions,
                    key.x() * SECTION_SIZE, key.y() * SECTION_SIZE, key.z() * SECTION_SIZE);
            List<Integer> visibleGhostFaces = createVisibleGhostFaces(entry.getValue(),
                    faceVisibility, includeBuildGhosts);
            SectionMesh existing = sections.get(entry.getKey());
            if (existing != null && existing.matches(
                    entry.getValue(), waveCells, visibleGhostFaces, includeBuildGhosts)) {
                replacement.put(entry.getKey(), existing);
            } else {
                SectionMesh staged = pending == null ? null
                        : pending.sections().get(entry.getKey());
                if (staged != null && staged.matches(
                        entry.getValue(), waveCells, visibleGhostFaces, includeBuildGhosts)) {
                    replacement.put(entry.getKey(), staged);
                } else {
                    SectionMesh created = new SectionMesh(entry.getKey(), entry.getValue(),
                            waveCells, visibleGhostFaces, includeBuildGhosts);
                    replacement.put(entry.getKey(), created);
                }
            }
        }
        Set<SectionKey> changed = changedSectionKeys(sections, replacement);
        boolean initialGeneration = source == null || sections.isEmpty();
        Set<SectionBoundary> dependencies = changedBoundaryDependencies(
                changed, sections, replacement);
        pending = new PendingGeneration(snapshot, replacement, changed,
                dependencies, initialGeneration);
    }

    private void promoteReadyGroups(PendingGeneration generation,
                                    Set<SectionKey> modelResidency) {
        if (pending != generation) return;
        for (Set<SectionKey> group : generation.publicationGroups()) {
            if (generation.isPublished(group)) continue;
            Predicate<SectionKey> ready = key -> {
                SectionMesh section = generation.sections().get(key);
                if (section == null) return true; // Removed sections need no back buffer.
                boolean requireModels = requireGhostCompletion(
                        modelResidency.contains(key),
                        section.ghostUploadStarted());
                return section.ready(requireModels);
            };
            if (!publicationGroupReady(group, ready)) continue;
            publishGroup(generation, group);
        }
    }

    /** Publishes every member while holding this cache's monitor, then retires old buffers. */
    private void publishGroup(PendingGeneration generation, Set<SectionKey> group) {
        Set<SectionMesh> replaced = identitySet();
        for (SectionKey key : group) {
            SectionMesh replacement = generation.sections().get(key);
            SectionMesh old = replacement == null
                    ? sections.remove(key) : sections.put(key, replacement);
            if (sectionMapEntryChanged(old, replacement)) generation.mutatedActive = true;
            if (old != null && old != replacement) activeGhostUploads.remove(old);
            if (replacement != null && replacement.ghostUploadInProgress()) {
                activeGhostUploads.add(replacement);
            }
            if (old != null && old != replacement) replaced.add(old);
        }
        generation.markPublished(group);

        // A mesh may be shared with the target map when its cells did not change. Never close a
        // buffer that is still reachable after the whole dependency group has been installed.
        Set<SectionMesh> active = identitySet();
        active.addAll(sections.values());
        replaced.forEach(mesh -> {
            if (!active.contains(mesh)) mesh.close();
        });
    }

    /** Identity, rather than value equality, defines whether the active VBO map was mutated. */
    static boolean sectionMapEntryChanged(@Nullable Object before, @Nullable Object after) {
        return before != after;
    }

    static boolean canFastRevertToSource(boolean committedGeometryMatches,
                                         boolean activeMapMutated) {
        return committedGeometryMatches && !activeMapMutated;
    }

    private void finishGeneration(PendingGeneration generation) {
        if (pending != generation) return;
        // Rebuild only the small map shell to restore deterministic section order. Every changed
        // dependency group has already been swapped, so no VBO is cleared between generations.
        sections = new LinkedHashMap<>(generation.sections());
        source = generation.source();
        pending = null;

        BuilderPreviewState.Snapshot latest = queued.take();
        if (latest == null) return;
        if (!Objects.equals(source.dimension(), latest.dimension())) {
            // This is defensive: synchronize normally hard-clears a dimension change before it
            // can enter the queue.
            clear();
            if (!latest.cells().isEmpty()) stageSnapshot(latest);
            else source = latest;
        } else if (sameGeometry(source, latest)) {
            source = latest;
        } else if (latest.cells().isEmpty()) {
            sections.values().forEach(SectionMesh::close);
            sections = new LinkedHashMap<>();
            activeGhostUploads.clear();
            source = latest;
        } else {
            stageSnapshot(latest);
        }
    }

    private void discardPending() {
        discardPendingExcept(identitySet());
        pending = null;
    }

    private void discardPendingExcept(Set<SectionMesh> retained) {
        if (pending == null) return;
        Set<SectionMesh> active = identitySet();
        active.addAll(sections.values());
        Set<SectionMesh> closed = identitySet();
        for (SectionMesh mesh : pending.sections().values()) {
            if (!active.contains(mesh) && !retained.contains(mesh)) closeOnce(mesh, closed);
        }
    }

    private static boolean sameGeometry(BuilderPreviewState.Snapshot first,
                                        BuilderPreviewState.Snapshot second) {
        return Objects.equals(first.dimension(), second.dimension())
                && first.cells().equals(second.cells());
    }

    private static Set<SectionMesh> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void closeOnce(SectionMesh mesh, Set<SectionMesh> closed) {
        if (closed.add(mesh)) mesh.close();
    }

    private static Set<SectionKey> modelResidentSectionKeys(
            Map<SectionKey, SectionMesh> candidates,
            Vec3 camera, double modelDistanceSqr) {
        Set<SectionKey> seeds = new LinkedHashSet<>();
        for (Map.Entry<SectionKey, SectionMesh> entry : candidates.entrySet()) {
            if (entry.getValue().closestDistanceToSqr(camera) <= modelDistanceSqr) {
                seeds.add(entry.getKey());
            }
        }

        Set<SectionBoundary> connectedEdges = new LinkedHashSet<>();
        // Deliberately iterate only the original seeds.  Newly included neighbours form a guard
        // band, not a transitive closure that could upload an entire remote blueprint.
        for (SectionKey key : seeds) {
            SectionMesh section = candidates.get(key);
            if (section == null) continue;
            for (Direction direction : Direction.values()) {
                SectionKey neighbourKey = key.relative(direction);
                SectionMesh neighbour = candidates.get(neighbourKey);
                if (neighbour != null
                        && projectionBoundaryConnected(section, neighbour, direction)) {
                    connectedEdges.add(new SectionBoundary(key, neighbourKey));
                }
            }
        }
        return expandModelResidency(seeds, connectedEdges);
    }

    /** Expands only the supplied seed set by one layer of connected section-boundary edges. */
    static Set<SectionKey> expandModelResidency(
            Set<SectionKey> seeds, Set<SectionBoundary> connectedEdges) {
        Set<SectionKey> result = new LinkedHashSet<>(seeds);
        for (SectionBoundary edge : connectedEdges) {
            boolean firstSeed = seeds.contains(edge.first());
            boolean secondSeed = seeds.contains(edge.second());
            if (firstSeed) result.add(edge.second());
            if (secondSeed) result.add(edge.first());
        }
        return Set.copyOf(result);
    }

    private static Set<SectionKey> changedSectionKeys(
            Map<SectionKey, SectionMesh> current,
            Map<SectionKey, SectionMesh> replacement) {
        Set<SectionKey> changed = new LinkedHashSet<>();
        for (Map.Entry<SectionKey, SectionMesh> entry : replacement.entrySet()) {
            if (current.get(entry.getKey()) != entry.getValue()) changed.add(entry.getKey());
        }
        for (SectionKey key : current.keySet()) {
            if (!replacement.containsKey(key)) changed.add(key);
        }
        return changed;
    }

    /**
     * Adds an atomic dependency only where a cross-section projection boundary actually changed.
     * Interior-only edits therefore retain the old one-section publication latency.
     */
    private static Set<SectionBoundary> changedBoundaryDependencies(
            Set<SectionKey> changed,
            Map<SectionKey, SectionMesh> current,
            Map<SectionKey, SectionMesh> replacement) {
        Set<SectionBoundary> dependencies = new LinkedHashSet<>();
        Direction[] positiveDirections = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (SectionKey key : changed) {
            for (Direction direction : positiveDirections) {
                SectionKey neighbour = key.relative(direction);
                if (!changed.contains(neighbour)) continue;
                boolean connectedBefore = projectionBoundaryConnected(
                        current.get(key), current.get(neighbour), direction);
                boolean connectedAfter = projectionBoundaryConnected(
                        replacement.get(key), replacement.get(neighbour), direction);
                boolean firstChanged = boundaryChanged(
                        current.get(key), replacement.get(key), direction);
                boolean secondChanged = boundaryChanged(
                        current.get(neighbour), replacement.get(neighbour),
                        direction.getOpposite());
                if (requiresAtomicBoundaryPublication(connectedBefore, connectedAfter,
                        firstChanged, secondChanged)) {
                    dependencies.add(new SectionBoundary(key, neighbour));
                }
            }
        }
        return dependencies;
    }

    static boolean requiresAtomicBoundaryPublication(
            boolean connectedBefore, boolean connectedAfter,
            boolean firstBoundaryChanged, boolean secondBoundaryChanged) {
        return (connectedBefore || connectedAfter)
                && (firstBoundaryChanged || secondBoundaryChanged);
    }

    private static boolean projectionBoundaryConnected(
            @Nullable SectionMesh section, @Nullable SectionMesh neighbour,
            Direction direction) {
        return section != null && neighbour != null
                && section.projectionBoundaryConnects(neighbour, direction);
    }

    private static boolean boundaryChanged(@Nullable SectionMesh before,
                                           @Nullable SectionMesh after,
                                           Direction direction) {
        BoundarySignature oldSignature = before == null
                ? BoundarySignature.EMPTY : before.boundary(direction);
        BoundarySignature newSignature = after == null
                ? BoundarySignature.EMPTY : after.boundary(direction);
        return !oldSignature.equals(newSignature);
    }

    /** Connected components are the smallest sets that can be published without mixed faces. */
    static List<Set<SectionKey>> connectedPublicationGroups(
            Set<SectionKey> changed, Set<SectionBoundary> dependencies) {
        if (changed.isEmpty()) return List.of();
        Map<SectionKey, SectionKey> parents = new LinkedHashMap<>();
        changed.forEach(key -> parents.put(key, key));
        for (SectionBoundary dependency : dependencies) {
            if (!parents.containsKey(dependency.first())
                    || !parents.containsKey(dependency.second())) continue;
            union(parents, dependency.first(), dependency.second());
        }
        Map<SectionKey, Set<SectionKey>> groups = new LinkedHashMap<>();
        for (SectionKey key : changed) {
            SectionKey root = find(parents, key);
            groups.computeIfAbsent(root, ignored -> new LinkedHashSet<>()).add(key);
        }
        List<Set<SectionKey>> result = new ArrayList<>(groups.size());
        groups.values().forEach(group -> result.add(Collections.unmodifiableSet(group)));
        return List.copyOf(result);
    }

    static boolean publicationGroupReady(Set<SectionKey> group,
                                         Predicate<SectionKey> ready) {
        for (SectionKey key : group) {
            if (!ready.test(key)) return false;
        }
        return true;
    }

    /**
     * The first generation has no old concrete models to preserve outside the current residency.
     * Restricting its atomic edges to that working set prevents one long, connected blueprint from
     * delaying the nearest pair until every remote outline section has uploaded.
     */
    static Set<SectionBoundary> publicationDependenciesForResidency(
            Set<SectionBoundary> dependencies, Set<SectionKey> residency) {
        Set<SectionBoundary> result = new LinkedHashSet<>();
        for (SectionBoundary dependency : dependencies) {
            if (residency.contains(dependency.first())
                    && residency.contains(dependency.second())) {
                result.add(dependency);
            }
        }
        return Set.copyOf(result);
    }

    private static SectionKey find(Map<SectionKey, SectionKey> parents, SectionKey key) {
        SectionKey parent = parents.get(key);
        if (parent.equals(key)) return key;
        SectionKey root = find(parents, parent);
        parents.put(key, root);
        return root;
    }

    private static void union(Map<SectionKey, SectionKey> parents,
                              SectionKey first, SectionKey second) {
        SectionKey firstRoot = find(parents, first);
        SectionKey secondRoot = find(parents, second);
        if (!firstRoot.equals(secondRoot)) parents.put(secondRoot, firstRoot);
    }

    private static final class PendingGeneration {
        private BuilderPreviewState.Snapshot source;
        private final Map<SectionKey, SectionMesh> sections;
        private final Set<SectionKey> changed;
        private final Set<SectionBoundary> dependencies;
        private final boolean initialGeneration;
        @Nullable private List<Set<SectionKey>> publicationGroups;
        private final Set<SectionKey> published = new HashSet<>();
        private boolean mutatedActive;

        private PendingGeneration(BuilderPreviewState.Snapshot source,
                                  Map<SectionKey, SectionMesh> sections,
                                  Set<SectionKey> changed,
                                  Set<SectionBoundary> dependencies,
                                  boolean initialGeneration) {
            this.source = source;
            this.sections = new LinkedHashMap<>(sections);
            this.changed = Set.copyOf(changed);
            this.dependencies = Set.copyOf(dependencies);
            this.initialGeneration = initialGeneration;
        }

        private BuilderPreviewState.Snapshot source() { return source; }
        private Map<SectionKey, SectionMesh> sections() { return sections; }
        private void initializePublicationGroups(Set<SectionKey> modelResidency) {
            if (publicationGroups != null) return;
            Set<SectionBoundary> activeDependencies = initialGeneration
                    ? publicationDependenciesForResidency(dependencies, modelResidency)
                    : dependencies;
            publicationGroups = connectedPublicationGroups(changed, activeDependencies);
        }
        private List<Set<SectionKey>> publicationGroups() {
            return publicationGroups == null ? List.of() : publicationGroups;
        }
        private boolean requiresPublication(SectionKey key) { return changed.contains(key); }
        private boolean isPublished(SectionKey key) { return published.contains(key); }
        private boolean isPublished(Set<SectionKey> group) {
            return !group.isEmpty() && published.contains(group.iterator().next());
        }
        private void markPublished(Set<SectionKey> group) { published.addAll(group); }
        private boolean fullyPublished() { return published.size() == changed.size(); }
    }

    /** Single-slot latest-wins handoff used to coalesce immutable preview snapshots. */
    static final class LatestWinsQueue<T> {
        @Nullable
        private T latest;

        void offer(T value) {
            latest = value;
        }

        @Nullable
        T take() {
            T value = latest;
            latest = null;
            return value;
        }

        void clear() {
            latest = null;
        }

        boolean isEmpty() {
            return latest == null;
        }

        @Nullable
        T peek() {
            return latest;
        }
    }

    @Nullable
    synchronized BuilderPreviewState.Snapshot inFlightSnapshotForTesting() {
        return pending == null ? null : pending.source();
    }

    @Nullable
    synchronized BuilderPreviewState.Snapshot queuedSnapshotForTesting() {
        return queued.peek();
    }

    static final class SectionMesh {
        private final SectionKey key;
        private final int originX;
        private final int originY;
        private final int originZ;
        private final AABB bounds;
        private final List<BuilderPreviewState.Cell> cells;
        private final List<WaveCell> waveCells;
        private final List<Integer> visibleGhostFaces;
        private final BoundarySignature[] boundaries;
        private final long[][] projectedBoundaryMasks;
        private final boolean includeBuildGhosts;
        private final boolean hasGhosts;

        @Nullable private VertexBuffer outlineBuffer;
        private final List<VertexBuffer> ghostBuffers = new ArrayList<>();
        private final List<List<VertexBuffer>> boundaryGhostBuffers = new ArrayList<>(
                Direction.values().length);
        private final List<List<VertexBuffer>> ghostDrawLists = new ArrayList<>(
                1 << Direction.values().length);
        private final List<VertexBuffer> waveBuffers = new ArrayList<>();
        private int ghostCursor;
        private int waveCursor;

        private SectionMesh(SectionKey key, List<BuilderPreviewState.Cell> cells,
                            List<WaveCell> waveCells, List<Integer> visibleGhostFaces,
                            boolean includeBuildGhosts) {
            this.key = key;
            originX = key.x() * SECTION_SIZE;
            originY = key.y() * SECTION_SIZE;
            originZ = key.z() * SECTION_SIZE;
            bounds = new AABB(originX, originY, originZ,
                    originX + SECTION_SIZE, originY + SECTION_SIZE, originZ + SECTION_SIZE)
                    .inflate(0.02D);
            this.cells = List.copyOf(cells);
            this.includeBuildGhosts = includeBuildGhosts;
            this.waveCells = List.copyOf(waveCells);
            this.visibleGhostFaces = List.copyOf(visibleGhostFaces);
            boundaries = createBoundarySignatures(this.cells, this.waveCells,
                    this.visibleGhostFaces, includeBuildGhosts, originX, originY, originZ);
            projectedBoundaryMasks = createProjectedBoundaryMasks(this.cells,
                    includeBuildGhosts, originX, originY, originZ);
            hasGhosts = cells.stream().anyMatch(cell -> isGhostCell(cell, includeBuildGhosts));
            for (Direction ignored : Direction.values()) {
                boundaryGhostBuffers.add(new ArrayList<>());
            }
            for (int ignored = 0; ignored < 1 << Direction.values().length; ignored++) {
                ghostDrawLists.add(null);
            }
        }

        private boolean matches(List<BuilderPreviewState.Cell> replacement,
                                 List<WaveCell> replacementWaves,
                                 List<Integer> replacementVisibleGhostFaces,
                                 boolean replacementBuildGhosts) {
            return includeBuildGhosts == replacementBuildGhosts
                    && cells.equals(replacement)
                    && waveCells.equals(replacementWaves)
                    && visibleGhostFaces.equals(replacementVisibleGhostFaces);
        }

        /** @return upload-budget slots consumed, or zero when no work was available. */
        private int uploadNext(Minecraft minecraft, boolean buildModels) {
            RenderSystem.assertOnRenderThread();
            if (outlineBuffer == null) {
                outlineBuffer = uploadOutlineBuffer();
                return uploadBudgetCost(outlineBuffer == null ? 0 : 1);
            }
            if (waveCursor < waveCells.size()) {
                int end = Math.min(waveCells.size(), waveCursor + WAVE_CELLS_PER_UPLOAD);
                VertexBuffer wave = uploadWaveBuffer(waveCursor, end);
                waveCursor = end;
                if (wave != null) waveBuffers.add(wave);
                return uploadBudgetCost(wave == null ? 0 : 1);
            }
            if (hasGhosts && buildModels && ghostCursor < cells.size()) {
                int end = Math.min(cells.size(), ghostCursor + MODEL_CELLS_PER_UPLOAD);
                int uploadedBuffers = uploadGhostBuffers(minecraft, ghostCursor, end);
                ghostCursor = end;
                return uploadBudgetCost(uploadedBuffers);
            }
            return 0;
        }

        private boolean ready(boolean requireModels) {
            return outlineBuffer != null
                    && waveCursor >= waveCells.size()
                    && (!requireModels || ghostModelsComplete());
        }

        private boolean ghostModelsComplete() {
            return !hasGhosts || ghostCursor >= cells.size();
        }

        private boolean ghostUploadStarted() {
            return ghostCursor > 0;
        }

        private boolean ghostUploadInProgress() {
            return ghostUploadStarted() && !ghostModelsComplete();
        }

        @Nullable
        private VertexBuffer uploadOutlineBuffer() {
            Set<EdgeKey> edges = new HashSet<>(Math.max(16, cells.size() * 4));
            for (BuilderPreviewState.Cell cell : cells) {
                int x = cell.pos().getX() - originX;
                int y = cell.pos().getY() - originY;
                int z = cell.pos().getZ() - originZ;
                addCellEdges(edges, x, y, z, cell.kind());
            }
            BufferBuilder builder = new BufferBuilder(Math.max(512, edges.size() * 160));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            PoseStack.Pose identity = new PoseStack().last();
            for (EdgeKey edge : edges) {
                double x2 = edge.x() + (edge.axis() == Axis.X ? 1.0D : 0.0D);
                double y2 = edge.y() + (edge.axis() == Axis.Y ? 1.0D : 0.0D);
                double z2 = edge.z() + (edge.axis() == Axis.Z ? 1.0D : 0.0D);
                if (edge.kind() == BuilderPreviewState.Kind.INVALID) {
                    emitDashedEdge(identity, builder, edge.x(), edge.y(), edge.z(),
                            x2, y2, z2, edge.kind());
                } else {
                    BuilderPreviewGeometry.emitEdge(identity, builder,
                            edge.x(), edge.y(), edge.z(), x2, y2, z2,
                            edge.kind(), OUTLINE_HALF_WIDTH, 1.0F);
                }
            }
            return upload(builder);
        }

        private int uploadGhostBuffers(Minecraft minecraft, int start, int end) {
            BufferBuilder builder = new BufferBuilder(Math.max(4096, (end - start) * 1024));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            BufferBuilder[] boundaryBuilders = new BufferBuilder[Direction.values().length];
            PoseStack pose = new PoseStack();
            for (int index = start; index < end; index++) {
                BuilderPreviewState.Cell cell = cells.get(index);
                if (!isGhostCell(cell, includeBuildGhosts)) continue;
                int localX = cell.pos().getX() - originX;
                int localY = cell.pos().getY() - originY;
                int localZ = cell.pos().getZ() - originZ;
                pose.pushPose();
                pose.translate(localX + GHOST_MODEL_INSET,
                        localY + GHOST_MODEL_INSET,
                        localZ + GHOST_MODEL_INSET);
                float modelScale = 1.0F - GHOST_MODEL_INSET * 2.0F;
                pose.scale(modelScale, modelScale, modelScale);
                VertexConsumer tinted = new GhostVertexConsumer(builder, cell.kind());
                MultiBufferSource singleBuffer = ignored -> tinted;
                try {
                    renderGhostBlock(minecraft, cell, visibleGhostFaces.get(index), pose,
                            singleBuffer, tinted, boundaryBuilders,
                            localX, localY, localZ);
                } catch (RuntimeException exception) {
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(cell.state().getBlock());
                    if (blockId != null && WARNED_MODEL_TYPES.add(blockId)) {
                        MyDimension.LOGGER.warn("Skipping incompatible projected block model {}",
                                blockId, exception);
                    }
                }
                pose.popPose();
            }
            VertexBuffer ghost = upload(builder);
            int uploadedBuffers = 0;
            if (ghost != null) {
                ghostBuffers.add(ghost);
                uploadedBuffers++;
            }
            // Keep the main mesh and its directional fallback faces in one atomic 128-cell upload
            // step.  A cap contains only boundary directional quads from that same bounded batch;
            // it cannot grow with the rest of the blueprint or leave a drawable mesh uncapped.
            for (Direction direction : Direction.values()) {
                BufferBuilder boundaryBuilder = boundaryBuilders[direction.ordinal()];
                if (boundaryBuilder == null) continue;
                VertexBuffer boundary = upload(boundaryBuilder);
                if (boundary != null) {
                    boundaryGhostBuffers.get(direction.ordinal()).add(boundary);
                    uploadedBuffers++;
                }
            }
            return uploadedBuffers;
        }

        /**
         * Renders directional model quads with the same cross-section visibility decision used by
         * normal chunk meshing.  {@link net.minecraft.client.renderer.block.BlockRenderDispatcher
         * #renderSingleBlock} deliberately renders every directional quad, which exposes the
         * inset grey side of a projected cube and is especially obvious at a 16-block section
         * boundary where the neighbouring block lives in another VBO.
         */
        private static void renderGhostBlock(Minecraft minecraft,
                                             BuilderPreviewState.Cell cell,
                                             int visibleFaceMask,
                                             PoseStack pose,
                                             MultiBufferSource singleBuffer,
                                             VertexConsumer tinted,
                                             BufferBuilder[] boundaryBuilders,
                                             int localX, int localY, int localZ) {
            if (cell.state().getRenderShape() != RenderShape.MODEL) {
                minecraft.getBlockRenderer().renderSingleBlock(cell.state(), pose, singleBuffer,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY,
                        BuilderPreviewRenderTypes.ghostModel());
                return;
            }

            BakedModel model = minecraft.getBlockRenderer().getBlockModel(cell.state());
            int tint = minecraft.getBlockColors().getColor(cell.state(), null, null, 0);
            float red = (tint >> 16 & 255) / 255.0F;
            float green = (tint >> 8 & 255) / 255.0F;
            float blue = (tint & 255) / 255.0F;
            RandomSource renderTypeRandom = RandomSource.create(42L);
            for (RenderType renderType : model.getRenderTypes(
                    cell.state(), renderTypeRandom, ModelData.EMPTY)) {
                for (Direction direction : Direction.values()) {
                    RandomSource quadRandom = RandomSource.create(42L);
                    List<BakedQuad> quads = model.getQuads(cell.state(), direction, quadRandom,
                            ModelData.EMPTY, renderType);
                    if (isFaceVisible(visibleFaceMask, direction)) {
                        renderQuadList(pose.last(), tinted, quads, red, green, blue);
                    } else if (onBoundary(localX, localY, localZ, direction)) {
                        BufferBuilder boundaryBuilder = boundaryBuilder(
                                boundaryBuilders, direction);
                        VertexConsumer boundaryTinted = new GhostVertexConsumer(
                                boundaryBuilder, cell.kind());
                        renderQuadList(pose.last(), boundaryTinted,
                                quads, red, green, blue);
                    }
                }
                RandomSource unculledRandom = RandomSource.create(42L);
                renderQuadList(pose.last(), tinted,
                        model.getQuads(cell.state(), null, unculledRandom,
                                ModelData.EMPTY, renderType), red, green, blue);
            }
        }

        private static BufferBuilder boundaryBuilder(BufferBuilder[] builders,
                                                       Direction direction) {
            int index = direction.ordinal();
            BufferBuilder builder = builders[index];
            if (builder == null) {
                builder = new BufferBuilder(4096);
                builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
                builders[index] = builder;
            }
            return builder;
        }

        private static void renderQuadList(PoseStack.Pose pose, VertexConsumer consumer,
                                           List<BakedQuad> quads,
                                           float red, float green, float blue) {
            for (BakedQuad quad : quads) {
                float quadRed = quad.isTinted() ? red : 1.0F;
                float quadGreen = quad.isTinted() ? green : 1.0F;
                float quadBlue = quad.isTinted() ? blue : 1.0F;
                consumer.putBulkData(pose, quad, quadRed, quadGreen, quadBlue,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
        }

        @Nullable
        private VertexBuffer uploadWaveBuffer(int start, int end) {
            BufferBuilder builder = new BufferBuilder(Math.max(2048, (end - start) * 640));
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            PoseStack.Pose identity = new PoseStack().last();
            for (int index = start; index < end; index++) {
                WaveCell cell = waveCells.get(index);
                BuilderPreviewGeometry.emitWaveCube(identity, builder,
                        cell.x(), cell.y(), cell.z(), cell.faceMask());
            }
            return upload(builder);
        }

        AABB bounds() { return bounds; }
        @Nullable VertexBuffer outlineBuffer() { return outlineBuffer; }
        List<VertexBuffer> ghostBuffers(Set<SectionKey> drawableSections) {
            boolean drawable = drawableSections.contains(key);
            if (!shouldRenderGhostModels(drawable, ghostModelsComplete())) return List.of();
            int capMask = 0;
            for (Direction direction : Direction.values()) {
                List<VertexBuffer> boundary = boundaryGhostBuffers.get(direction.ordinal());
                if (boundary.isEmpty() || !shouldRenderBoundaryCap(
                        drawable, drawableSections.contains(key.relative(direction)))) continue;
                capMask |= 1 << direction.ordinal();
            }
            if (capMask == 0) return ghostBuffers;
            List<VertexBuffer> result = ghostDrawLists.get(capMask);
            if (result != null) return result;
            List<VertexBuffer> created = new ArrayList<>(ghostBuffers);
            for (Direction direction : Direction.values()) {
                if ((capMask & 1 << direction.ordinal()) != 0) {
                    created.addAll(boundaryGhostBuffers.get(direction.ordinal()));
                }
            }
            result = List.copyOf(created);
            ghostDrawLists.set(capMask, result);
            return result;
        }
        List<VertexBuffer> waveBuffers() { return waveBuffers; }
        int originX() { return originX; }
        int originY() { return originY; }
        int originZ() { return originZ; }
        BoundarySignature boundary(Direction direction) {
            return boundaries[direction.ordinal()];
        }

        private boolean projectionBoundaryConnects(SectionMesh neighbour,
                                                    Direction direction) {
            if (!key.relative(direction).equals(neighbour.key)) return false;
            long[] first = projectedBoundaryMasks[direction.ordinal()];
            long[] second = neighbour.projectedBoundaryMasks[
                    direction.getOpposite().ordinal()];
            for (int index = 0; index < first.length; index++) {
                if ((first[index] & second[index]) != 0L) return true;
            }
            return false;
        }

        double closestDistanceToSqr(Vec3 point) {
            double dx = axisDistance(point.x, bounds.minX, bounds.maxX);
            double dy = axisDistance(point.y, bounds.minY, bounds.maxY);
            double dz = axisDistance(point.z, bounds.minZ, bounds.maxZ);
            return dx * dx + dy * dy + dz * dz;
        }

        private double distanceToSqr(Vec3 point) {
            double x = (bounds.minX + bounds.maxX) * 0.5D - point.x;
            double y = (bounds.minY + bounds.maxY) * 0.5D - point.y;
            double z = (bounds.minZ + bounds.maxZ) * 0.5D - point.z;
            return x * x + y * y + z * z;
        }

        private void close() {
            close(outlineBuffer);
            ghostBuffers.forEach(SectionMesh::close);
            boundaryGhostBuffers.forEach(
                    buffers -> buffers.forEach(SectionMesh::close));
            waveBuffers.forEach(SectionMesh::close);
            outlineBuffer = null;
            ghostBuffers.clear();
            boundaryGhostBuffers.forEach(List::clear);
            Collections.fill(ghostDrawLists, null);
            waveBuffers.clear();
        }

        private static void close(@Nullable VertexBuffer buffer) {
            if (buffer == null || buffer.isInvalid()) return;
            if (RenderSystem.isOnRenderThread()) buffer.close();
            else RenderSystem.recordRenderCall(buffer::close);
        }

        private static double axisDistance(double value, double minimum, double maximum) {
            if (value < minimum) return minimum - value;
            if (value > maximum) return value - maximum;
            return 0.0D;
        }
    }

    @Nullable
    private static VertexBuffer upload(BufferBuilder builder) {
        BufferBuilder.RenderedBuffer rendered = builder.endOrDiscardIfEmpty();
        if (rendered == null) return null;
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(rendered);
        VertexBuffer.unbind();
        return buffer;
    }

    private static void addCellEdges(Set<EdgeKey> edges, int x, int y, int z,
                                     BuilderPreviewState.Kind kind) {
        edges.add(new EdgeKey(x, y, z, Axis.X, kind));
        edges.add(new EdgeKey(x, y + 1, z, Axis.X, kind));
        edges.add(new EdgeKey(x, y, z + 1, Axis.X, kind));
        edges.add(new EdgeKey(x, y + 1, z + 1, Axis.X, kind));
        edges.add(new EdgeKey(x, y, z, Axis.Y, kind));
        edges.add(new EdgeKey(x + 1, y, z, Axis.Y, kind));
        edges.add(new EdgeKey(x, y, z + 1, Axis.Y, kind));
        edges.add(new EdgeKey(x + 1, y, z + 1, Axis.Y, kind));
        edges.add(new EdgeKey(x, y, z, Axis.Z, kind));
        edges.add(new EdgeKey(x + 1, y, z, Axis.Z, kind));
        edges.add(new EdgeKey(x, y + 1, z, Axis.Z, kind));
        edges.add(new EdgeKey(x + 1, y + 1, z, Axis.Z, kind));
    }

    private static void emitDashedEdge(PoseStack.Pose pose, VertexConsumer consumer,
                                       double x1, double y1, double z1,
                                       double x2, double y2, double z2,
                                       BuilderPreviewState.Kind kind) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        for (double start = 0.0D; start < length; start += 0.235D) {
            double end = Math.min(length, start + 0.15D);
            double first = start / length;
            double second = end / length;
            BuilderPreviewGeometry.emitEdge(pose, consumer,
                    x1 + dx * first, y1 + dy * first, z1 + dz * first,
                    x1 + dx * second, y1 + dy * second, z1 + dz * second,
                    kind, OUTLINE_HALF_WIDTH, 1.0F);
        }
    }

    private enum Axis { X, Y, Z }

    private record WaveCell(int x, int y, int z, int faceMask) { }

    private record EdgeKey(int x, int y, int z, Axis axis,
                           BuilderPreviewState.Kind kind) { }

    record SectionKey(int x, int y, int z) {
        private static SectionKey of(BuilderPreviewState.Cell cell) {
            return new SectionKey(cell.pos().getX() >> SECTION_SHIFT,
                    cell.pos().getY() >> SECTION_SHIFT,
                    cell.pos().getZ() >> SECTION_SHIFT);
        }

        private SectionKey relative(Direction direction) {
            return new SectionKey(x + direction.getStepX(), y + direction.getStepY(),
                    z + direction.getStepZ());
        }
    }

    record SectionBoundary(SectionKey first, SectionKey second) { }

    record ModelResidency(Set<SectionKey> keys, Set<SectionMesh> meshes) {
        ModelResidency {
            keys = Set.copyOf(keys);
            meshes = Collections.unmodifiableSet(meshes);
        }

        boolean contains(SectionMesh section) {
            return meshes.contains(section);
        }

        Set<SectionKey> drawableGhostSections() {
            Set<SectionKey> drawable = new LinkedHashSet<>();
            for (SectionMesh section : meshes) {
                if (section.ghostModelsComplete()) drawable.add(section.key);
            }
            return Set.copyOf(drawable);
        }
    }

    private record BoundarySignature(long[] entries) {
        private static final BoundarySignature EMPTY = new BoundarySignature(new long[0]);

        @Override
        public boolean equals(Object other) {
            return other instanceof BoundarySignature signature
                    && Arrays.equals(entries, signature.entries);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(entries);
        }
    }

    private static boolean isWaveCell(BuilderPreviewState.Cell cell) {
        return cell.ghost() && cell.kind() == BuilderPreviewState.Kind.MISSING
                && !cell.state().isAir();
    }

    /** Every ghost-enabled preview kind, including ordinary BUILD, may render its block model. */
    static boolean isGhostCell(BuilderPreviewState.Cell cell, boolean blueprintPreview) {
        return cell.ghost()
                && permitsGhostKind(cell.kind(), blueprintPreview)
                && !cell.state().isAir()
                && cell.state().getRenderShape() != RenderShape.INVISIBLE;
    }

    static boolean permitsGhostKind(BuilderPreviewState.Kind kind, boolean blueprintPreview) {
        return true;
    }

    /** Conservative promotion latency for one section under the configured upload budget. */
    static int maximumPromotionFrames(int cellCount, int waveCellCount, boolean hasGhosts,
                                      boolean requireModels, int uploadsPerFrame) {
        if (cellCount <= 0 || uploadsPerFrame <= 0) return 0;
        int uploads = 1 + divideRoundUp(Math.max(0, waveCellCount), WAVE_CELLS_PER_UPLOAD);
        if (hasGhosts && requireModels) {
            uploads += divideRoundUp(cellCount, MODEL_CELLS_PER_UPLOAD);
        }
        return divideRoundUp(uploads, uploadsPerFrame);
    }

    /** Once a section starts its ghost back-buffer, moving away may not publish it half-built. */
    static boolean requireGhostCompletion(boolean withinModelDistance,
                                          boolean ghostUploadStarted) {
        return withinModelDistance || ghostUploadStarted;
    }

    /** Partial or out-of-residency concrete buffers are never exposed to the renderer. */
    static boolean shouldRenderGhostModels(boolean resident, boolean uploadComplete) {
        return resident && uploadComplete;
    }

    /** A culled cross-section face is restored until its projected neighbour is drawable. */
    static boolean shouldRenderBoundaryCap(boolean sectionDrawable,
                                           boolean neighbourDrawable) {
        return sectionDrawable && !neighbourDrawable;
    }

    /** Empty batches still consume one scheduling step; cap VBOs consume their real upload cost. */
    static int uploadBudgetCost(int uploadedVertexBuffers) {
        return Math.max(1, uploadedVertexBuffers);
    }

    private static int divideRoundUp(int value, int divisor) {
        return value == 0 ? 0 : 1 + (value - 1) / divisor;
    }

    private static List<WaveCell> createWaveCells(List<BuilderPreviewState.Cell> cells,
                                                   LongSet missingGhostPositions,
                                                   int originX, int originY, int originZ) {
        List<WaveCell> result = new ArrayList<>();
        for (BuilderPreviewState.Cell cell : cells) {
            if (!isWaveCell(cell)) continue;
            long packed = cell.pos().asLong();
            int faceMask = 0;
            for (Direction direction : Direction.values()) {
                if (!missingGhostPositions.contains(BlockPos.offset(packed, direction))) {
                    faceMask |= 1 << direction.ordinal();
                }
            }
            if (faceMask != 0) {
                result.add(new WaveCell(cell.pos().getX() - originX,
                        cell.pos().getY() - originY,
                        cell.pos().getZ() - originZ, faceMask));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Exact, compact descriptions of the six section boundaries. They are built once with each
     * staged mesh and let publication dependency discovery compare only a few primitive arrays;
     * no per-refresh BlockPos or boundary-cell objects are allocated.
     */
    private static BoundarySignature[] createBoundarySignatures(
            List<BuilderPreviewState.Cell> cells,
            List<WaveCell> waveCells,
            List<Integer> visibleGhostFaces,
            boolean includeBuildGhosts,
            int originX, int originY, int originZ) {
        LongArrayList[] entries = new LongArrayList[Direction.values().length];
        for (int index = 0; index < cells.size(); index++) {
            BuilderPreviewState.Cell cell = cells.get(index);
            if (!isGhostCell(cell, includeBuildGhosts)) continue;
            int x = cell.pos().getX() - originX;
            int y = cell.pos().getY() - originY;
            int z = cell.pos().getZ() - originZ;
            int localPosition = x << 8 | y << 4 | z;
            int faceMask = visibleGhostFaces.get(index);
            for (Direction direction : Direction.values()) {
                if (!onBoundary(x, y, z, direction)) continue;
                long entry = Integer.toUnsignedLong(Block.getId(cell.state())) << 32
                        | (long) localPosition << 16
                        | 0x100L
                        | (long) cell.kind().ordinal() << 1
                        | (isFaceVisible(faceMask, direction) ? 1L : 0L);
                boundaryEntries(entries, direction).add(entry);
            }
        }
        for (WaveCell cell : waveCells) {
            int localPosition = cell.x() << 8 | cell.y() << 4 | cell.z();
            for (Direction direction : Direction.values()) {
                if (!onBoundary(cell.x(), cell.y(), cell.z(), direction)) continue;
                long entry = (long) localPosition << 16
                        | 0x200L
                        | (isFaceVisible(cell.faceMask(), direction) ? 1L : 0L);
                boundaryEntries(entries, direction).add(entry);
            }
        }

        BoundarySignature[] result = new BoundarySignature[Direction.values().length];
        for (Direction direction : Direction.values()) {
            LongArrayList values = entries[direction.ordinal()];
            if (values == null || values.isEmpty()) {
                result[direction.ordinal()] = BoundarySignature.EMPTY;
            } else {
                long[] sorted = values.toLongArray();
                Arrays.sort(sorted);
                result[direction.ordinal()] = new BoundarySignature(sorted);
            }
        }
        return result;
    }

    /** Four longs encode the 16x16 occupied ghost cells on each of the six section faces. */
    private static long[][] createProjectedBoundaryMasks(
            List<BuilderPreviewState.Cell> cells, boolean includeBuildGhosts,
            int originX, int originY, int originZ) {
        long[][] result = new long[Direction.values().length][4];
        for (BuilderPreviewState.Cell cell : cells) {
            if (!isGhostCell(cell, includeBuildGhosts)) continue;
            int x = cell.pos().getX() - originX;
            int y = cell.pos().getY() - originY;
            int z = cell.pos().getZ() - originZ;
            for (Direction direction : Direction.values()) {
                if (!onBoundary(x, y, z, direction)) continue;
                int bit = boundaryCellIndex(x, y, z, direction);
                result[direction.ordinal()][bit >>> 6] |= 1L << (bit & 63);
            }
        }
        return result;
    }

    private static int boundaryCellIndex(int x, int y, int z, Direction direction) {
        return switch (direction.getAxis()) {
            case X -> y << SECTION_SHIFT | z;
            case Y -> x << SECTION_SHIFT | z;
            case Z -> x << SECTION_SHIFT | y;
        };
    }

    private static LongArrayList boundaryEntries(LongArrayList[] entries,
                                                  Direction direction) {
        int index = direction.ordinal();
        LongArrayList result = entries[index];
        if (result == null) {
            result = new LongArrayList();
            entries[index] = result;
        }
        return result;
    }

    private static boolean onBoundary(int x, int y, int z, Direction direction) {
        return switch (direction) {
            case DOWN -> y == 0;
            case UP -> y == SECTION_SIZE - 1;
            case NORTH -> z == 0;
            case SOUTH -> z == SECTION_SIZE - 1;
            case WEST -> x == 0;
            case EAST -> x == SECTION_SIZE - 1;
        };
    }

    /**
     * Computes face visibility against the complete projection, before it is divided into render
     * sections.  This is the important distinction from querying only a {@link SectionMesh}:
     * neighbours at x/y/z 15 and 16 must suppress their shared face exactly like neighbours inside
     * one VBO.  The mask is retained in the section identity so changing an adjacent section also
     * invalidates the affected boundary mesh.
     */
    private static List<Integer> createVisibleGhostFaces(
            List<BuilderPreviewState.Cell> cells,
            FaceVisibilityContext context,
            boolean includeBuildGhosts) {
        List<Integer> result = new ArrayList<>(cells.size());
        for (BuilderPreviewState.Cell cell : cells) {
            result.add(isGhostCell(cell, includeBuildGhosts)
                    ? visibleGhostFaceMask(cell, context) : 0);
        }
        return List.copyOf(result);
    }

    static int visibleGhostFaceMask(BuilderPreviewState.Cell cell,
                                    ProjectionBlockGetter projectionBlocks) {
        return visibleGhostFaceMask(cell, new FaceVisibilityContext(projectionBlocks));
    }

    private static int visibleGhostFaceMask(BuilderPreviewState.Cell cell,
                                            FaceVisibilityContext context) {
        if (context.disabledBlocks.contains(cell.state().getBlock())) return allFaceMask();
        int faceMask = 0;
        BlockPos pos = cell.pos();
        long packedPosition = pos.asLong();
        for (Direction direction : Direction.values()) {
            long packedNeighbour = BlockPos.offset(packedPosition, direction);
            BlockState neighbourState = context.blocks.projectedState(packedNeighbour);
            if (neighbourState == null) {
                faceMask |= 1 << direction.ordinal();
                continue;
            }
            try {
                if (shouldRenderProjectedFace(cell.state(), neighbourState, pos,
                        packedNeighbour, direction, context)) {
                    faceMask |= 1 << direction.ordinal();
                }
            } catch (RuntimeException exception) {
                // Some modded states assume the supplied BlockGetter is a concrete Level or
                // require a live block entity while calculating their occlusion shape. Such a
                // model remains renderable; it simply opts out of projection-only face culling.
                ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(cell.state().getBlock());
                if (blockId != null && WARNED_FACE_CULL_TYPES.add(blockId)) {
                    MyDimension.LOGGER.warn(
                            "Projected block {} does not support virtual neighbour culling; "
                                    + "rendering all model faces", blockId, exception);
                }
                context.disabledBlocks.add(cell.state().getBlock());
                return allFaceMask();
            }
        }
        return faceMask;
    }

    private static boolean shouldRenderProjectedFace(
            BlockState state, BlockState neighbourState, BlockPos pos, long packedNeighbour,
            Direction direction, FaceVisibilityContext context) {
        // These two decisions use state and direction only, so avoid both a MutableBlockPos write
        // and the BlockStatePairKey allocation in Block.shouldRenderFace for the common cases.
        if (state.skipRendering(neighbourState, direction)) return false;
        boolean externalFaceHiding = state.supportsExternalFaceHiding();
        if (!externalFaceHiding && !neighbourState.canOcclude()) return true;

        // Forge external face hiding may inspect a capability, block entity, or wider world state.
        // Never cache that path. The virtual getter still deliberately exposes the complete
        // projection and the existing exception fallback preserves unusual modded models.
        if (externalFaceHiding) {
            return callShouldRenderFace(state, pos, packedNeighbour, direction, context);
        }

        byte cached = context.cache.get(state, neighbourState, direction);
        if (cached >= 0) return cached != 0;
        boolean visible = callShouldRenderFace(
                state, pos, packedNeighbour, direction, context);
        context.cache.put(state, neighbourState, direction, visible);
        return visible;
    }

    private static boolean callShouldRenderFace(
            BlockState state, BlockPos pos, long packedNeighbour, Direction direction,
            FaceVisibilityContext context) {
        context.neighbour.set(BlockPos.getX(packedNeighbour), BlockPos.getY(packedNeighbour),
                BlockPos.getZ(packedNeighbour));
        return Block.shouldRenderFace(
                state, context.blocks, pos, direction, context.neighbour);
    }

    private static int allFaceMask() {
        return (1 << Direction.values().length) - 1;
    }

    static boolean hasProjectedNeighbour(LongSet projectedPositions, BlockPos pos,
                                         Direction direction) {
        return projectedPositions.contains(BlockPos.offset(pos.asLong(), direction));
    }

    static boolean isFaceVisible(int faceMask, Direction direction) {
        return (faceMask & 1 << direction.ordinal()) != 0;
    }

    /** Per-generation scratch state: one cursor and a state-identity visibility cache. */
    private static final class FaceVisibilityContext {
        private final ProjectionBlockGetter blocks;
        private final BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        private final FaceVisibilityCache cache = new FaceVisibilityCache();
        private final Set<Block> disabledBlocks =
                Collections.newSetFromMap(new IdentityHashMap<>());

        private FaceVisibilityContext(ProjectionBlockGetter blocks) {
            this.blocks = blocks;
        }
    }

    /**
     * Mirrors vanilla's state-pair occlusion cache without allocating a BlockStatePairKey for
     * every occupied neighbour. Values are cached only when Forge external face hiding is absent.
     */
    private static final class FaceVisibilityCache {
        private static final byte UNKNOWN = -1;
        private final IdentityHashMap<BlockState, IdentityHashMap<BlockState, byte[]>> values =
                new IdentityHashMap<>();

        private byte get(BlockState state, BlockState neighbour, Direction direction) {
            IdentityHashMap<BlockState, byte[]> neighbours = values.get(state);
            if (neighbours == null) return UNKNOWN;
            byte[] directions = neighbours.get(neighbour);
            return directions == null ? UNKNOWN : directions[direction.ordinal()];
        }

        private void put(BlockState state, BlockState neighbour, Direction direction,
                         boolean visible) {
            IdentityHashMap<BlockState, byte[]> neighbours = values.computeIfAbsent(
                    state, ignored -> new IdentityHashMap<>());
            byte[] directions = neighbours.computeIfAbsent(neighbour, ignored -> {
                byte[] created = new byte[Direction.values().length];
                Arrays.fill(created, UNKNOWN);
                return created;
            });
            directions[direction.ordinal()] = (byte) (visible ? 1 : 0);
        }
    }

    /** Read-only virtual world containing every concrete projected block in the snapshot. */
    static final class ProjectionBlockGetter implements BlockGetter {
        private final Long2ObjectMap<BlockState> states = new Long2ObjectOpenHashMap<>();

        ProjectionBlockGetter(List<BuilderPreviewState.Cell> cells,
                              boolean includeBuildGhosts) {
            for (BuilderPreviewState.Cell cell : cells) {
                if (isGhostCell(cell, includeBuildGhosts)) {
                    states.put(cell.pos().asLong(), cell.state());
                }
            }
        }

        LongSet projectedPositions() {
            return states.keySet();
        }

        @Nullable
        BlockState projectedState(long packedPosition) {
            return states.get(packedPosition);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = states.get(pos.asLong());
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getMinBuildHeight() {
            return -2048;
        }

        @Override
        public int getHeight() {
            return 4096;
        }
    }

    /** Applies stable projection tint and alpha while retaining the block atlas UVs. */
    private static final class GhostVertexConsumer implements VertexConsumer {
        private final VertexConsumer delegate;
        private final float red;
        private final float green;
        private final float blue;
        private final float alpha;

        private GhostVertexConsumer(VertexConsumer delegate, BuilderPreviewState.Kind kind) {
            this.delegate = delegate;
            float mix = 0.18F;
            red = 1.0F - mix + kind.red() * mix;
            green = 1.0F - mix + kind.green() * mix;
            blue = 1.0F - mix + kind.blue() * mix;
            // Keep the material recognizable at a glance without making it indistinguishable
            // from a real placed block. These values remain translucent and shader-pack safe.
            alpha = kind == BuilderPreviewState.Kind.MISSING ? 0.72F : 0.62F;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            delegate.color(scale(r, red), scale(g, green), scale(b, blue), scale(a, alpha));
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            // Entity translucent shaders apply directional diffuse lighting even to FULL_BRIGHT
            // vertices.  A projection is a UI overlay, not world geometry, so use one stable
            // upward normal for every face.  This removes the arbitrary grey east/west face while
            // retaining the well-supported vanilla entity shader path used by shader packs.
            delegate.normal(0.0F, 1.0F, 0.0F);
            return this;
        }

        @Override
        public void endVertex() {
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(scale(r, red), scale(g, green), scale(b, blue), scale(a, alpha));
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }

        private static int scale(int channel, float multiplier) {
            return Math.max(0, Math.min(255, Math.round(channel * multiplier)));
        }
    }
}
