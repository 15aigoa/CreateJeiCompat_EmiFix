package com.starion.createjeicompat.mixin;

import net.neoforged.fml.ModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class CreateJeiCompatMixinPlugin implements IMixinConfigPlugin {
    private static final String EMI_ID = "emi";

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (ModList.get().isLoaded(EMI_ID)) {
            if (mixinClassName.endsWith("RecipesGuiMixin")) return false;
            if (mixinClassName.endsWith("SequencedAssemblyCategoryMixin")) return false;
        }
        return true;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
