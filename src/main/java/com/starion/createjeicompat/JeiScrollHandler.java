package com.starion.createjeicompat;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import mezz.jei.gui.recipes.RecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Event-based scroll handler for JEI RecipesGui.
 * Fallback when RecipesGuiMixin fails to find target (Forge/JEI mapping mismatch).
 */
@Mod.EventBusSubscriber(modid = CreateJeiCompatMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class JeiScrollHandler {

    private static Field layoutsField;
    private static Field logicField;
    private static Field recipeLayoutsField;
    private static Field logicCacheField;
    private static Method updateLayoutMethod;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof RecipesGui recipesGui)) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        double scrollDelta = event.getScrollDelta();

        if (!recipesGui.isMouseOver(mouseX, mouseY)) {
            return;
        }

        try {
            Object layouts = getLayouts(recipesGui);
            if (layouts == null) return;

            @SuppressWarnings("unchecked")
            List<RecipeLayoutWithButtons<?>> recipeLayouts = (List<RecipeLayoutWithButtons<?>>) getRecipeLayouts(layouts);
            if (recipeLayouts == null || recipeLayouts.isEmpty()) return;

            for (RecipeLayoutWithButtons<?> layoutWithButtons : recipeLayouts) {
                IRecipeLayoutDrawable<?> recipeLayout = layoutWithButtons.recipeLayout();
                if (!recipeLayout.isMouseOver(mouseX, mouseY)) continue;

                SequencedAssemblyRecipe sequencedRecipe = unwrapRecipe(recipeLayout.getRecipe());
                if (sequencedRecipe == null) continue;

                if (!SequencedAssemblyPageManager.canScroll(sequencedRecipe, scrollDelta)) {
                    event.setCanceled(true);
                    return;
                }

                if (SequencedAssemblyPageManager.handleScroll(sequencedRecipe, scrollDelta)) {
                    invalidateAndUpdate(recipesGui);
                    event.setCanceled(true);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Object getLayouts(RecipesGui gui) throws Exception {
        if (layoutsField == null) {
            layoutsField = RecipesGui.class.getDeclaredField("layouts");
            layoutsField.setAccessible(true);
        }
        return layoutsField.get(gui);
    }

    private static Object getRecipeLayouts(Object layouts) throws Exception {
        if (recipeLayoutsField == null) {
            recipeLayoutsField = RecipeGuiLayouts.class.getDeclaredField("recipeLayoutsWithButtons");
            recipeLayoutsField.setAccessible(true);
        }
        return recipeLayoutsField.get(layouts);
    }

    private static SequencedAssemblyRecipe unwrapRecipe(Object recipeObj) {
        if (recipeObj == null) return null;
        try {
            Method valueMethod = recipeObj.getClass().getMethod("value");
            Object unwrapped = valueMethod.invoke(recipeObj);
            if (unwrapped instanceof SequencedAssemblyRecipe r) return r;
        } catch (Exception ignored) {
        }
        if (recipeObj instanceof SequencedAssemblyRecipe r) return r;
        return null;
    }

    private static void invalidateAndUpdate(RecipesGui gui) {
        try {
            if (logicField == null) {
                logicField = RecipesGui.class.getDeclaredField("logic");
                logicField.setAccessible(true);
            }
            Object logic = logicField.get(gui);
            if (logic != null && logicCacheField == null) {
                logicCacheField = logic.getClass().getDeclaredField("cachedRecipeLayoutsWithButtons");
                logicCacheField.setAccessible(true);
            }
            if (logic != null && logicCacheField != null) {
                logicCacheField.set(logic, null);
            }

            if (updateLayoutMethod == null) {
                updateLayoutMethod = RecipesGui.class.getDeclaredMethod("updateLayout");
                updateLayoutMethod.setAccessible(true);
            }
            updateLayoutMethod.invoke(gui);
        } catch (Exception ignored) {
        }
    }
}
