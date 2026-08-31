package com.xfestudio.mydimension.builder;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered material access shared by layer and blueprint jobs.  The anchor implementation installs the
 * remote bridge during common setup; keeping the bridge here also makes a failed third-party handler
 * incapable of preventing the player inventory fallbacks from running.
 */
public final class BuilderMaterials {
    public interface RemoteBridge {
        Extraction extract(ServerPlayer player, ItemStack scepter, ItemStack requested, int amount);

        /** Returns the part that could not be stored. */
        ItemStack insert(ServerPlayer player, ItemStack scepter, ItemStack stack);
    }

    public record Extraction(int count, List<ItemStack> stacks) {
        public Extraction {
            count = Math.max(0, count);
            stacks = stacks.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        }

        public static Extraction empty() {
            return new Extraction(0, List.of());
        }
    }

    private static final RemoteBridge NO_REMOTE = new RemoteBridge() {
        @Override
        public Extraction extract(ServerPlayer player, ItemStack scepter, ItemStack requested, int amount) {
            return Extraction.empty();
        }

        @Override
        public ItemStack insert(ServerPlayer player, ItemStack scepter, ItemStack stack) {
            return stack.copy();
        }
    };

    private static volatile RemoteBridge remote = NO_REMOTE;

    private BuilderMaterials() {
    }

    public static void installRemoteBridge(RemoteBridge bridge) {
        remote = bridge == null ? NO_REMOTE : bridge;
    }

    public static Extraction extract(ServerPlayer player, ItemStack scepter, ItemStack requested, int amount) {
        return extract(player, scepter, requested, amount, true);
    }

    private static Extraction extract(ServerPlayer player, ItemStack scepter, ItemStack requested, int amount,
                                      boolean allowOffhand) {
        if (amount <= 0 || requested.isEmpty()) return Extraction.empty();
        List<ItemStack> debits = new ArrayList<>();
        int remaining = amount;

        try {
            Extraction fromRemote = remote.extract(player, scepter, requested, remaining);
            int accepted = Math.min(remaining, Math.max(0, fromRemote.count()));
            if (accepted > 0) {
                remaining -= accepted;
                appendUpTo(debits, fromRemote.stacks(), accepted);
            }
        } catch (RuntimeException ignored) {
            // A broken capability is isolated to its endpoint; local inventory remains usable.
        }

        remaining = takeSlotRange(player, requested, remaining, 9, 35, debits, -1);
        remaining = takeSlotRange(player, requested, remaining, 0, 8, debits,
                player.getInventory().selected);

        if (allowOffhand && remaining > 0) {
            ItemStack offhand = player.getOffhandItem();
            if (matches(offhand, requested) && offhand != scepter) {
                int taken = Math.min(remaining, offhand.getCount());
                debits.add(offhand.copyWithCount(taken));
                offhand.shrink(taken);
                remaining -= taken;
            }
        }
        return new Extraction(amount - remaining, debits);
    }

    /** Stores in anchor order first, then the player's ordinary inventory. */
    public static List<ItemStack> insert(ServerPlayer player, ItemStack scepter, List<ItemStack> values) {
        return insert(player, scepter, values, true);
    }

    /** Transaction bookkeeping uses this variant so an exact offhand tool is never replaced by a drop. */
    public static List<ItemStack> insertPreservingOffhand(ServerPlayer player, ItemStack scepter,
                                                           List<ItemStack> values) {
        return insert(player, scepter, values, false);
    }

    private static List<ItemStack> insert(ServerPlayer player, ItemStack scepter, List<ItemStack> values,
                                          boolean allowOffhand) {
        List<ItemStack> overflow = new ArrayList<>();
        for (ItemStack value : values) {
            if (value.isEmpty()) continue;
            ItemStack remainder;
            try {
                remainder = remote.insert(player, scepter, value.copy());
            } catch (RuntimeException ignored) {
                remainder = value.copy();
            }
            remainder = insertSlotRange(player, remainder, 9, 35, -1);
            remainder = insertSlotRange(player, remainder, 0, 8, player.getInventory().selected);
            if (allowOffhand && !remainder.isEmpty()) remainder = insertOffhand(player, remainder, scepter);
            if (!remainder.isEmpty()) overflow.add(remainder.copy());
        }
        return overflow;
    }

    /** Conservative exact removal used before undoing demolition. */
    public static boolean canAndRemove(ServerPlayer player, ItemStack scepter, List<ItemStack> values) {
        return canAndRemove(player, scepter, values, List.of());
    }

    public static boolean canAndRemove(ServerPlayer player, ItemStack scepter, List<ItemStack> values,
                                       List<ItemEntity> transactionEntities) {
        List<ItemStack> required = merge(values);
        List<ItemStack> removed = new ArrayList<>();
        List<EntityDebit> entityDebits = new ArrayList<>();
        for (ItemStack wanted : required) {
            // The offhand is separately fingerprinted and restored as tool state; accepting it as a drop
            // debit would let undo consume the tool and then recreate it from offhandBefore.
            Extraction extraction = extract(player, scepter, wanted, wanted.getCount(), false);
            removed.addAll(extraction.stacks());
            int remaining = wanted.getCount() - extraction.count();
            for (ItemEntity entity : transactionEntities) {
                if (remaining <= 0 || entity.isRemoved() || !matches(entity.getItem(), wanted)) continue;
                int alreadyPlanned = entityDebits.stream().filter(debit -> debit.entity == entity)
                        .mapToInt(debit -> debit.count).sum();
                int take = Math.min(remaining, Math.max(0, entity.getItem().getCount() - alreadyPlanned));
                if (take > 0) {
                    entityDebits.add(new EntityDebit(entity, take));
                    remaining -= take;
                }
            }
            if (remaining > 0) {
                // Roll back every debit before reporting the transaction conflict.
                List<ItemStack> overflow = insert(player, scepter, removed, false);
                for (ItemStack stack : overflow) player.drop(stack, false);
                return false;
            }
        }
        for (EntityDebit debit : entityDebits) {
            ItemStack stack = debit.entity.getItem();
            stack.shrink(debit.count);
            if (stack.isEmpty()) debit.entity.discard();
        }
        return true;
    }

    private static int takeSlotRange(ServerPlayer player, ItemStack requested, int remaining,
                                     int first, int last, List<ItemStack> result, int excluded) {
        for (int slot = first; slot <= last && remaining > 0; slot++) {
            if (slot == excluded) continue;
            ItemStack found = player.getInventory().getItem(slot);
            if (!matches(found, requested)) continue;
            int taken = Math.min(remaining, found.getCount());
            result.add(found.copyWithCount(taken));
            found.shrink(taken);
            remaining -= taken;
        }
        return remaining;
    }

    private static ItemStack insertSlotRange(ServerPlayer player, ItemStack offered,
                                             int first, int last, int excluded) {
        ItemStack remaining = offered.copy();
        // Fill compatible stacks before empty slots, while preserving the requested slot-class priority.
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int slot = first; slot <= last && !remaining.isEmpty(); slot++) {
                if (slot == excluded) continue;
                ItemStack current = player.getInventory().getItem(slot);
                if (pass == 0) {
                    if (!matches(current, remaining)) continue;
                    int room = Math.min(current.getMaxStackSize(), player.getInventory().getMaxStackSize())
                            - current.getCount();
                    if (room <= 0) continue;
                    int moved = Math.min(room, remaining.getCount());
                    current.grow(moved);
                    remaining.shrink(moved);
                } else if (current.isEmpty()) {
                    int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                    player.getInventory().setItem(slot, remaining.copyWithCount(moved));
                    remaining.shrink(moved);
                }
            }
        }
        return remaining;
    }

    private static ItemStack insertOffhand(ServerPlayer player, ItemStack offered, ItemStack scepter) {
        ItemStack remaining = offered.copy();
        ItemStack current = player.getOffhandItem();
        if (current == scepter) return remaining;
        if (current.isEmpty()) {
            int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, remaining.copyWithCount(moved));
            remaining.shrink(moved);
        } else if (matches(current, remaining)) {
            int room = current.getMaxStackSize() - current.getCount();
            int moved = Math.min(Math.max(0, room), remaining.getCount());
            current.grow(moved);
            remaining.shrink(moved);
        }
        return remaining;
    }

    private static boolean matches(ItemStack left, ItemStack right) {
        return !left.isEmpty() && ItemStack.isSameItemSameTags(left, right);
    }

    private static void appendUpTo(List<ItemStack> target, List<ItemStack> values, int maximum) {
        int remaining = maximum;
        for (ItemStack value : values) {
            if (remaining <= 0) break;
            int count = Math.min(remaining, value.getCount());
            if (count > 0) target.add(value.copyWithCount(count));
            remaining -= count;
        }
    }

    private static List<ItemStack> merge(List<ItemStack> values) {
        List<ItemStack> result = new ArrayList<>();
        outer:
        for (ItemStack value : values) {
            if (value.isEmpty()) continue;
            for (ItemStack existing : result) {
                if (matches(existing, value)) {
                    existing.grow(value.getCount());
                    continue outer;
                }
            }
            result.add(value.copy());
        }
        return result;
    }

    private record EntityDebit(ItemEntity entity, int count) { }
}
