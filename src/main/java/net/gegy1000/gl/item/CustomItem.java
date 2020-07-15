package net.gegy1000.gl.item;

import com.google.common.base.Preconditions;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.gegy1000.gl.GameLib;
import net.gegy1000.gl.event.SwingHandCallback;
import net.gegy1000.gl.registry.TinyRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Set;

public final class CustomItem {
    private static final TinyRegistry<CustomItem> REGISTRY = TinyRegistry.newStable();

    private final Identifier id;
    private final Text name;

    private UseItemCallback use;
    private SwingHandCallback swingHand;

    private CustomItem(Identifier identifier, Text name) {
        this.id = identifier;
        this.name = name;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ItemStack applyTo(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(GameLib.ID + ":custom_item", this.id.toString());

        if (this.name != null && !stack.hasCustomName()) {
            stack.setCustomName(this.name);
        }

        return stack;
    }

    public ItemStack create(Item item) {
        ItemStack stack = new ItemStack(item);
        this.applyTo(stack);
        return stack;
    }

    @Nullable
    public static CustomItem match(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;

        String customItem = tag.getString(GameLib.ID + ":custom_item");
        if (customItem != null) {
            return REGISTRY.get(new Identifier(customItem));
        }

        return null;
    }

    public static Set<Identifier> getKeys() {
        return REGISTRY.keySet();
    }

    @Nullable
    public static CustomItem get(Identifier identifier) {
        return REGISTRY.get(identifier);
    }

    public TypedActionResult<ItemStack> onUse(PlayerEntity player, World world, Hand hand) {
        if (this.use == null) {
            return TypedActionResult.pass(ItemStack.EMPTY);
        }
        return this.use.interact(player, world, hand);
    }

    public void onSwingHand(ServerPlayerEntity player, Hand hand) {
        if (this.swingHand != null) {
            this.swingHand.onSwingHand(player, hand);
        }
    }

    public static class Builder {
        private Identifier id;
        private Text name;
        private UseItemCallback use;
        private SwingHandCallback swingHand;

        private Builder() {
        }

        public Builder id(Identifier id) {
            this.id = id;
            return this;
        }

        public Builder name(Text name) {
            this.name = name;
            return this;
        }

        public Builder onUse(UseItemCallback use) {
            this.use = use;
            return this;
        }

        public Builder onSwingHand(SwingHandCallback swingHand) {
            this.swingHand = swingHand;
            return this;
        }

        public CustomItem register() {
            Preconditions.checkNotNull(this.id, "id not set");
            if (REGISTRY.containsKey(this.id)) {
                throw new IllegalArgumentException(this.id + " already registered");
            }

            CustomItem item = new CustomItem(this.id, this.name);
            item.use = this.use;
            item.swingHand = this.swingHand;

            REGISTRY.register(this.id, item);

            return item;
        }
    }
}