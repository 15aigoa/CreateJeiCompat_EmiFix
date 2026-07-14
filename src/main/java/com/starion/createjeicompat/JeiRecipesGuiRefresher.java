package com.starion.createjeicompat;

import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Best-effort attempt to force JEI's own RecipesGui to rebuild its cached
 * recipe layout (and therefore its item slots) IN PLACE, without navigating
 * away or touching JEI's browsing history/category selection.
 *
 * We tried the public, supported API for this first
 * (IJeiRuntime.getRecipesGui().showRecipes(...)), but that method also
 * changes JEI's browsing *state* (it's built on the same code path as
 * "show all recipes for category X"), which caused an unwanted side effect:
 * clicking our page button sometimes jumped the whole JEI window to an
 * unrelated recipe list instead of just refreshing the current one.
 *
 * This instead directly invalidates RecipeGuiLogic's private
 * "cachedRecipeLayoutsWithButtons" field and re-runs RecipesGui's private
 * "updateLayout()" - the same two steps JEI itself takes internally when
 * something *should* trigger a rebuild (window resize, focus change, etc),
 * without going through any of the "state"/history-changing code paths.
 *
 * This pokes at JEI internals that are NOT public API and can be renamed or
 * restructured in a future JEI update without warning. Every step logs
 * what it's doing (search your log file for "createjeicompat" to find
 * these lines) so a failure can actually be diagnosed instead of silently
 * vanishing. If anything unexpected happens the first time, we permanently
 * stop trying for the rest of the session rather than repeatedly failing.
 */
public class JeiRecipesGuiRefresher {

    private static final Logger LOGGER = LogManager.getLogger("createjeicompat");

    private static Field logicField;
    private static Field cachedLayoutsField;
    private static Method updateLayoutMethod;
    private static boolean brokenPreviously = false;

    private JeiRecipesGuiRefresher() {
    }

    public static void refreshIfShowing() {
        if (brokenPreviously) {
            LOGGER.debug("refreshIfShowing: skipped, a previous attempt already failed this session");
            return;
        }
        try {
            Object screen = Minecraft.getInstance().screen;
            LOGGER.info("refreshIfShowing: current screen is {}", screen == null ? "null" : screen.getClass().getName());
            if (!(screen instanceof RecipesGui recipesGui)) {
                LOGGER.info("refreshIfShowing: current screen is not JEI's RecipesGui, nothing to refresh");
                return;
            }

            if (logicField == null) {
                logicField = RecipesGui.class.getDeclaredField("logic");
                logicField.setAccessible(true);
                LOGGER.info("refreshIfShowing: found RecipesGui#logic field");
            }
            Object logic = logicField.get(recipesGui);
            if (logic == null) {
                LOGGER.warn("refreshIfShowing: RecipesGui#logic was null");
                return;
            }
            LOGGER.info("refreshIfShowing: logic instance class is {}", logic.getClass().getName());

            if (cachedLayoutsField == null) {
                cachedLayoutsField = logic.getClass().getDeclaredField("cachedRecipeLayoutsWithButtons");
                cachedLayoutsField.setAccessible(true);
                LOGGER.info("refreshIfShowing: found {}#cachedRecipeLayoutsWithButtons field", logic.getClass().getSimpleName());
            }
            cachedLayoutsField.set(logic, null);
            LOGGER.info("refreshIfShowing: cleared cachedRecipeLayoutsWithButtons");

            if (updateLayoutMethod == null) {
                updateLayoutMethod = RecipesGui.class.getDeclaredMethod("updateLayout");
                updateLayoutMethod.setAccessible(true);
                LOGGER.info("refreshIfShowing: found RecipesGui#updateLayout() method");
            }
            updateLayoutMethod.invoke(recipesGui);
            LOGGER.info("refreshIfShowing: called updateLayout() successfully");
        } catch (Throwable t) {
            brokenPreviously = true;
            LOGGER.error("refreshIfShowing: failed, disabling for the rest of this session", t);
        }
    }
}
