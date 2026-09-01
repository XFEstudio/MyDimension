package com.xfestudio.mydimension.builder.anchor;

import com.xfestudio.mydimension.builder.BuilderMaterials;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Material bridge that gives ordered bound anchors priority over the player inventory. */
public final class AnchorRemoteBridge implements BuilderMaterials.RemoteBridge {
    public static final AnchorRemoteBridge INSTANCE = new AnchorRemoteBridge();
    private static final int MALFORMED_HANDLER_SLOT_LIMIT = 262_144;

    private AnchorRemoteBridge() {
    }

    @Override
    public BuilderMaterials.Extraction extract(ServerPlayer player, ItemStack scepter,
                                               ItemStack requested, int amount) {
        if (requested.isEmpty() || amount <= 0) {
            return BuilderMaterials.Extraction.empty();
        }

        int remaining = amount;
        List<ItemStack> extracted = new ArrayList<>();
        Set<Endpoint> visited = new HashSet<>();
        for (UUID anchorId : AnchorBindings.read(scepter)) {
            if (remaining <= 0) {
                break;
            }
            int requestedFromAnchor = remaining;
            AnchorAccess.AccessResult<BuilderMaterials.Extraction> result = AnchorAccess.withContainer(
                    player,
                    anchorId,
                    context -> {
                        Endpoint endpoint = Endpoint.of(context);
                        if (!visited.add(endpoint)) {
                            return BuilderMaterials.Extraction.empty();
                        }
                        return extractFromHandler(context, requested, requestedFromAnchor);
                    }
            );
            if (!result.available() || result.value() == null) {
                continue;
            }
            BuilderMaterials.Extraction part = result.value();
            int accepted = Math.min(remaining, part.count());
            appendUpTo(extracted, part.stacks(), accepted);
            remaining -= accepted;
        }
        return new BuilderMaterials.Extraction(amount - remaining, extracted);
    }

    @Override
    public ItemStack insert(ServerPlayer player, ItemStack scepter, ItemStack stack) {
        return insertAll(player, scepter, List.of(stack)).get(0);
    }

    @Override
    public List<ItemStack> insertAll(ServerPlayer player, ItemStack scepter, List<ItemStack> stacks) {
        List<ItemStack> remaining = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            remaining.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        if (remaining.stream().allMatch(ItemStack::isEmpty)) return List.copyOf(remaining);

        Set<Endpoint> visited = new HashSet<>();
        for (UUID anchorId : AnchorBindings.read(scepter)) {
            if (remaining.stream().allMatch(ItemStack::isEmpty)) break;
            List<ItemStack> offered = copyStacks(remaining);
            // Preserve the last known remainder even if lease cleanup fails after the handler has
            // already committed items. Falling back to the pre-anchor input would duplicate them.
            AtomicReference<List<ItemStack>> committedRemainder = new AtomicReference<>(offered);
            try {
                AnchorAccess.withContainer(player, anchorId, context -> {
                    Endpoint endpoint = Endpoint.of(context);
                    if (!visited.add(endpoint)) return offered;
                    List<ItemStack> processed = insertAllIntoHandler(context, offered);
                    committedRemainder.set(processed);
                    return processed;
                });
            } catch (RuntimeException exception) {
                // Acquisition/index failures occur before handler access; close failures occur after
                // the callback and are covered by committedRemainder.
            }
            remaining = new ArrayList<>(committedRemainder.get());
        }
        return List.copyOf(remaining);
    }

    /** Iterates every handler slot at most once for this extraction request. */
    private static BuilderMaterials.Extraction extractFromHandler(AnchorAccess.ContainerContext context,
                                                                  ItemStack requested,
                                                                  int requestedCount) {
        IItemHandler handler = context.container().handler();
        int remaining = requestedCount;
        List<ItemStack> extracted = new ArrayList<>();
        int slots;
        try {
            slots = Math.min(Math.max(0, handler.getSlots()), MALFORMED_HANDLER_SLOT_LIMIT);
        } catch (RuntimeException exception) {
            report(context, exception);
            return BuilderMaterials.Extraction.empty();
        }

        for (int slot = 0; slot < slots && remaining > 0; slot++) {
            try {
                ItemStack present = handler.getStackInSlot(slot);
                if (!matches(present, requested)) {
                    continue;
                }
                ItemStack simulated = handler.extractItem(slot, remaining, true);
                if (!matches(simulated, requested) || simulated.isEmpty()) {
                    continue;
                }
                int expected = Math.min(remaining, simulated.getCount());
                ItemStack beforeActual = present.copy();
                ItemStack actual;
                try {
                    actual = handler.extractItem(slot, expected, false);
                } catch (RuntimeException exception) {
                    // A broken handler may mutate its backing inventory before throwing. Reconcile the
                    // observable slot delta so the same items are not then removed from local inventory.
                    int observed = observedExtraction(handler, slot, beforeActual, requested, remaining);
                    if (observed > 0) {
                        extracted.add(requested.copyWithCount(observed));
                        remaining -= observed;
                    }
                    report(context, exception);
                    break;
                }
                if (!matches(actual, requested) || actual.isEmpty()) {
                    if (!actual.isEmpty()) {
                        handler.insertItem(slot, actual, false);
                    }
                    continue;
                }
                int accepted = Math.min(remaining, actual.getCount());
                extracted.add(actual.copyWithCount(accepted));
                remaining -= accepted;
                if (actual.getCount() > accepted) {
                    handler.insertItem(slot, actual.copyWithCount(actual.getCount() - accepted), false);
                }
            } catch (RuntimeException exception) {
                // Preserve already extracted stacks and let local inventory supply the remainder.
                report(context, exception);
                break;
            }
        }
        return new BuilderMaterials.Extraction(requestedCount - remaining, extracted);
    }

    /** Resolves the handler and its slot count once for every bound endpoint in this operation. */
    private static List<ItemStack> insertAllIntoHandler(AnchorAccess.ContainerContext context,
                                                        List<ItemStack> offered) {
        IItemHandler handler = context.container().handler();
        int slots;
        try {
            slots = Math.min(Math.max(0, handler.getSlots()), MALFORMED_HANDLER_SLOT_LIMIT);
        } catch (RuntimeException exception) {
            report(context, exception);
            return copyStacks(offered);
        }

        List<ItemStack> remaining = copyStacks(offered);
        for (int valueIndex = 0; valueIndex < remaining.size(); valueIndex++) {
            ItemStack value = remaining.get(valueIndex);
            if (value.isEmpty()) continue;
            for (int slot = 0; slot < slots && !value.isEmpty(); slot++) {
                try {
                    ItemStack offeredToSlot = value.copy();
                    ItemStack before = handler.getStackInSlot(slot).copy();
                    try {
                        ItemStack returned = handler.insertItem(slot, offeredToSlot.copy(), false);
                        if (!validRemainder(offeredToSlot, returned)) {
                            value = reconcileInsertion(handler, slot, before, offeredToSlot);
                            report(context, new IllegalStateException(
                                    "Item handler returned a malformed insertion remainder"));
                            break;
                        }
                        value = returned.isEmpty() ? ItemStack.EMPTY : returned.copy();
                    } catch (RuntimeException exception) {
                        value = reconcileInsertion(handler, slot, before, offeredToSlot);
                        report(context, exception);
                        break;
                    }
                } catch (RuntimeException exception) {
                    report(context, exception);
                    break;
                }
            }
            remaining.set(valueIndex, value.isEmpty() ? ItemStack.EMPTY : value.copy());
        }
        return List.copyOf(remaining);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) copies.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        return copies;
    }

    private static boolean validRemainder(ItemStack offered, ItemStack returned) {
        return returned != null && (returned.isEmpty()
                || matches(returned, offered) && returned.getCount() <= offered.getCount());
    }

    private static ItemStack reconcileInsertion(IItemHandler handler, int slot, ItemStack before,
                                                ItemStack offered) {
        int inserted = observedInsertion(handler, slot, before, offered);
        int remaining = offered.getCount() - Math.min(offered.getCount(), Math.max(0, inserted));
        return remaining <= 0 ? ItemStack.EMPTY : offered.copyWithCount(remaining);
    }

    private static void report(AnchorAccess.ContainerContext context, RuntimeException exception) {
        try {
            AnchorContainerResolver.reportHandlerFailure(context.location().dimension(),
                    context.container().position(), context.container().side(), exception);
        } catch (RuntimeException ignored) {
            // Diagnostics must never invalidate a remainder after a handler has committed items.
        }
    }

    private static void appendUpTo(List<ItemStack> target, List<ItemStack> source, int maximum) {
        int remaining = maximum;
        for (ItemStack stack : source) {
            if (remaining <= 0) {
                break;
            }
            int count = Math.min(remaining, stack.getCount());
            if (count > 0) {
                target.add(stack.copyWithCount(count));
                remaining -= count;
            }
        }
    }

    private static boolean matches(ItemStack left, ItemStack right) {
        return !left.isEmpty() && ItemStack.isSameItemSameTags(left, right);
    }

    private static int observedExtraction(IItemHandler handler, int slot, ItemStack before,
                                          ItemStack requested, int maximum) {
        try {
            ItemStack after = handler.getStackInSlot(slot);
            if (!matches(before, requested)) return 0;
            int afterCount = matches(after, requested) ? after.getCount() : 0;
            return Math.min(maximum, Math.max(0, before.getCount() - afterCount));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int observedInsertion(IItemHandler handler, int slot, ItemStack before,
                                         ItemStack offered) {
        try {
            ItemStack after = handler.getStackInSlot(slot);
            if (!matches(after, offered)) return 0;
            int beforeCount = matches(before, offered) ? before.getCount() : 0;
            return Math.min(offered.getCount(), Math.max(0, after.getCount() - beforeCount));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private record Endpoint(ResourceKey<Level> dimension, long position, Direction side) {
        private static Endpoint of(AnchorAccess.ContainerContext context) {
            return new Endpoint(context.location().dimension(),
                    context.container().position().asLong(), context.container().side());
        }
    }
}
