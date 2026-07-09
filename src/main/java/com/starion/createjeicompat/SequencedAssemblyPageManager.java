package com.starion.createjeicompat;

import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import java.util.WeakHashMap;

/**
 * Tracks which "page" of steps is currently shown for a given Sequenced
 * Assembly recipe. Kept as a plain utility class (not part of the Mixin
 * itself) so it can be called from both the recipe category's draw code
 * and its click-handling code.
 *
 * Page state is stored per-recipe-instance in a WeakHashMap, so it resets
 * naturally whenever JEI/EMI rebuild their recipe instances (e.g. on
 * world/resource reload) without needing any manual cleanup.
 */
public class SequencedAssemblyPageManager {

    private static final int STEPS_PER_PAGE = 6;
    private static final WeakHashMap<SequencedAssemblyRecipe, Integer> currentPageMap = new WeakHashMap<>();

    public static int getStepsPerPage() {
        return STEPS_PER_PAGE;
    }

    public static int getTotalPages(SequencedAssemblyRecipe recipe) {
        int totalSteps = recipe.getSequence().size();
        return Math.max(1, (int) Math.ceil(totalSteps / (double) STEPS_PER_PAGE));
    }

    public static int getCurrentPage(SequencedAssemblyRecipe recipe) {
        int page = currentPageMap.getOrDefault(recipe, 0);
        // Clamp defensively in case the recipe/sequence size ever changes underneath us
        // (e.g. datapack reload) while a page index was already cached.
        int totalPages = getTotalPages(recipe);
        return Math.max(0, Math.min(page, totalPages - 1));
    }

    public static void setCurrentPage(SequencedAssemblyRecipe recipe, int page) {
        int totalPages = getTotalPages(recipe);
        currentPageMap.put(recipe, Math.max(0, Math.min(page, totalPages - 1)));
    }

    /** Moves to the previous page. Returns true if the page actually changed. */
    public static boolean previousPage(SequencedAssemblyRecipe recipe) {
        int current = getCurrentPage(recipe);
        if (current <= 0) {
            return false;
        }
        setCurrentPage(recipe, current - 1);
        return true;
    }

    /** Moves to the next page. Returns true if the page actually changed. */
    public static boolean nextPage(SequencedAssemblyRecipe recipe) {
        int current = getCurrentPage(recipe);
        int totalPages = getTotalPages(recipe);
        if (current >= totalPages - 1) {
            return false;
        }
        setCurrentPage(recipe, current + 1);
        return true;
    }
}
