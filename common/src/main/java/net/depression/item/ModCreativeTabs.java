package net.depression.item;

import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.depression.Depression;
import net.depression.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;

public class ModCreativeTabs {
    public static final CreativeTabRegistry.TabSupplier BLOCKS_TAB = CreativeTabRegistry.create(new ResourceLocation(Depression.MOD_ID, "blocks_tab"),
            () -> new ItemStack(ModBlocks.COMPUTER.get()));
    public static final CreativeTabRegistry.TabSupplier ITEMS_TAB = CreativeTabRegistry.create(new ResourceLocation(Depression.MOD_ID, "items_tab"),
                    () -> new ItemStack(ModItems.DIARY.get()));

    public static void register() {
        CreativeTabRegistry.append(BLOCKS_TAB,
                ModBlocks.COMPUTER);
        CreativeTabRegistry.append(ITEMS_TAB,
                ModItems.DIARY,
                ModItems.MENTAL_HEALTH_SCALE,
                ModItems.MILD_DEPRESSION_TABLET,
                ModItems.MODERATE_DEPRESSION_TABLET,
                ModItems.MDD_CAPSULE,
                ModItems.INSOMNIA_TABLET,
                ModItems.RIBBON_BANNER_PATTERN);
    }
}
