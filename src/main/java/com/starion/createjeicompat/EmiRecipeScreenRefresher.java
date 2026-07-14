package com.starion.createjeicompat;

import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * Best-effort attempt to force EMI's own RecipeScreen to rebuild its
 * current page's widgets IN PLACE after our page-turn buttons change which
 * page of a Sequenced Assembly recipe is showing.
 *
 * Unlike JEI, EMI's RecipeScreen has a genuinely PUBLIC method for this:
 * setPage(int tabPage, int tab, int page). Calling it with the screen's
 * OWN current tabPage/tab/page values just rebuilds the widget list for
 * what's already showing, without navigating to a different tab or recipe
 * - so this doesn't need to touch anything private except reading those
 * three current int values back out first (tabPage/tab/page are private
 * fields with no public getters).
 *
 * Every step logs what it's doing (search your log file for
 * "createjeicompat" to find these lines). If anything unexpected happens
 * the first time (EMI restructures RecipeScreen's fields in a future
 * version, etc.), we permanently stop trying for the rest of the session
 * rather than repeatedly failing.
 */
public class EmiRecipeScreenRefresher {

    private static final Logger LOGGER = LogManager.getLogger("createjeicompat");

    private static Field tabPageField;
    private static Field tabField;
    private static Field pageField;
    private static boolean brokenPreviously = false;

    private EmiRecipeScreenRefresher() {
    }

    public static void refreshIfShowing() {
        if (brokenPreviously) {
            LOGGER.debug("EmiRecipeScreenRefresher: skipped, a previous attempt already failed this session");
            return;
        }
        try {
            Object screen = Minecraft.getInstance().screen;
            if (!(screen instanceof RecipeScreen recipeScreen)) {
                LOGGER.info("EmiRecipeScreenRefresher: current screen is not EMI's RecipeScreen, nothing to refresh");
                return;
            }

            if (tabPageField == null) {
                tabPageField = RecipeScreen.class.getDeclaredField("tabPage");
                tabPageField.setAccessible(true);
                tabField = RecipeScreen.class.getDeclaredField("tab");
                tabField.setAccessible(true);
                pageField = RecipeScreen.class.getDeclaredField("page");
                pageField.setAccessible(true);
                LOGGER.info("EmiRecipeScreenRefresher: found tabPage/tab/page fields");
            }

            int tabPage = tabPageField.getInt(recipeScreen);
            int tab = tabField.getInt(recipeScreen);
            int page = pageField.getInt(recipeScreen);
            LOGGER.info("EmiRecipeScreenRefresher: calling setPage({}, {}, {}) to rebuild in place", tabPage, tab, page);

            recipeScreen.setPage(tabPage, tab, page);
            LOGGER.info("EmiRecipeScreenRefresher: setPage() called successfully");
        } catch (Throwable t) {
            brokenPreviously = true;
            LOGGER.error("EmiRecipeScreenRefresher: failed, disabling for the rest of this session", t);
        }
    }
}
