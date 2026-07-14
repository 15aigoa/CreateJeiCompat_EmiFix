package com.starion.createjeicompat.mixin;

/**
 * Mixin for Create's SequencedAssemblyCategory that adds pagination support
 * (page-turn buttons) for recipes with more than 6 steps.
 *
 * Target class (Create 6.0.8, Minecraft 1.20.1, Forge):
 *   com.simibubi.create.compat.jei.category.SequencedAssemblyCategory
 *
 * Why this single Mixin covers both JEI and EMI:
 * SequencedAssemblyCategory implements JEI's IRecipeCategory interface.
 * EMI's built-in JEI-compatibility layer ("JEmi") wraps JEI recipe
 * categories directly and calls the exact same setRecipe() / draw() /
 * getTooltipStrings() / handleInput() methods to render the recipe inside
 * EMI's own screens (see dev.emi.emi.jemi.JemiRecipe in EMI's source).
 * So patching this one class benefits both viewers without any
 * EMI-specific code.
 *
 * Design notes:
 * - Page navigation works two ways: clicking the on-screen ^ / v buttons,
 *   or pressing the Up / Down arrow keys on the keyboard. Both end up
 *   calling the same handleInput() method below, because both JEI and
 *   EMI forward mouse clicks AND key presses on the recipe widget to
 *   IRecipeCategory#handleInput(). (An earlier attempt hooked JEI's own
 *   RecipesGui mouse-scroll event directly via Mixin; that only ever
 *   worked in JEI, since EMI's wrapper doesn't forward scroll events at
 *   all. handleInput() is the one input path both viewers actually share.)
 * - setRecipe() only creates *visible/interactive* JEI ingredient slots
 *   for the steps on the current page, but still registers every step's
 *   ingredients as *invisible* ingredients regardless of page, via
 *   builder.addInvisibleIngredients(...). This keeps "what recipes use
 *   this item" search working correctly for steps that aren't on the
 *   page currently being displayed.
 * - Known limitation: switching pages updates the visuals, tooltips, and
 *   page-turn buttons immediately and correctly (they're recalculated
 *   every frame). The *visible* JEI ingredient slots for step icons are
 *   handled separately: after a page change, we call
 *   JeiRecipesGuiRefresher.refreshIfShowing(), which pokes JEI's private
 *   internals (via reflection) to rebuild its cached layout in place. We
 *   tried the public IJeiRuntime.getRecipesGui().showRecipes() API first,
 *   but it also changes JEI's browsing state/history and caused an
 *   unwanted "jump to an unrelated recipe list" side effect - see
 *   JeiRecipesGuiRefresher's own comment for details. The reflection
 *   approach is scoped to JEI only; EMI rebuilds its own widget list
 *   independently and isn't covered by either mechanism, so EMI's item
 *   icons may still lag behind a page change until EMI itself re-shows
 *   the recipe.
 *
 * Original Create mod code: https://github.com/Creators-of-Create/Create (MIT)
 * JEI: https://github.com/mezz/JustEnoughItems (MIT)
 */

import com.mojang.blaze3d.platform.InputConstants;
import com.simibubi.create.compat.jei.category.CreateRecipeCategory;
import com.simibubi.create.compat.jei.category.SequencedAssemblyCategory;
import com.simibubi.create.compat.jei.category.sequencedAssembly.SequencedAssemblySubCategory;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.simibubi.create.content.processing.sequenced.SequencedRecipe;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import com.starion.createjeicompat.EmiRecipeScreenRefresher;
import com.starion.createjeicompat.JeiRecipesGuiRefresher;
import com.starion.createjeicompat.SequencedAssemblyPageManager;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(value = SequencedAssemblyCategory.class)
public abstract class SequencedAssemblyCategoryMixin {

    @Unique
    private static final Logger createjeicompat$LOGGER = LogManager.getLogger("createjeicompat");

    @Unique
    private static final int STEP_MARGIN = 3;

    // Manually replicated from Create's AllGuiTextures/AllIcons (jei/widgets.png,
    // 256x256 icons.png), because those enums implement Catnip interfaces
    // (net.createmod.catnip.gui.*) whose class files aren't on this project's
    // compile classpath (Catnip ships nested inside Create's jar via jar-in-jar,
    // which isn't visible to plain compileOnly file dependencies). Using
    // GuiGraphics#blit() directly avoids needing those classes at all.
    @Unique
    private static final ResourceLocation createjeicompat$JEI_WIDGETS = new ResourceLocation("create", "textures/gui/jei/widgets.png");

    /**
     * Convert a step number to a Roman numeral string.
     * Vanilla Create hardcodes I-VI and falls back to "-" for step 7+;
     * this supports any page/step count.
     */
    @Unique
    private static String createjeicompat$toRomanNumeral(int number) {
        if (number < 1 || number > 3999) {
            return String.valueOf(number);
        }
        String[] thousands = {"", "M", "MM", "MMM"};
        String[] hundreds = {"", "C", "CC", "CCC", "CD", "D", "DC", "DCC", "DCCC", "CM"};
        String[] tens = {"", "X", "XX", "XXX", "XL", "L", "LX", "LXX", "LXXX", "XC"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return thousands[number / 1000] +
                hundreds[(number % 1000) / 100] +
                tens[(number % 100) / 10] +
                ones[number % 10];
    }

    // ---- Access to the target class's own private/protected members ----

    @Shadow(remap = false)
    Map<ResourceLocation, SequencedAssemblySubCategory> subCategories;

    @Invoker(value = "getSubCategory", remap = false)
    abstract SequencedAssemblySubCategory createjeicompat$getSubCategory(SequencedRecipe<?> sequencedRecipe);

    @Invoker(value = "chanceComponent", remap = false)
    abstract MutableComponent createjeicompat$chanceComponent(float chance);

    @Unique
    private IDrawable createjeicompat$cachedBackground;

    @Unique
    private IDrawable createjeicompat$getBackground() {
        if (createjeicompat$cachedBackground == null) {
            createjeicompat$cachedBackground = ((CreateRecipeCategory<?>) (Object) this).getBackground();
        }
        return createjeicompat$cachedBackground;
    }

    // ---- setRecipe: build slots for the current page, register the rest as invisible ----

    @Overwrite(remap = false)
    public void setRecipe(IRecipeLayoutBuilder builder, SequencedAssemblyRecipe recipe, IFocusGroup focuses) {
        createjeicompat$LOGGER.info("setRecipe called (mixin is active) - {} total steps, page size = {}",
                recipe.getSequence().size(), SequencedAssemblyPageManager.getStepsPerPage());
        boolean noRandomOutput = recipe.getOutputChance() == 1;
        int xOffset = noRandomOutput ? 0 : -7;

        builder
                .addSlot(RecipeIngredientRole.INPUT, 27 + xOffset, 91)
                .setBackground(CreateRecipeCategory.getRenderedSlot(), -1, -1)
                .addItemStacks(List.of(recipe.getIngredient().getItems()));
        builder
                .addSlot(RecipeIngredientRole.OUTPUT, 132 + xOffset, 91)
                .setBackground(CreateRecipeCategory.getRenderedSlot(recipe.getOutputChance()), -1, -1)
                .addItemStack(CreateRecipeCategory.getResultItem(recipe))
                .addTooltipCallback((recipeSlotView, tooltip) -> {
                    if (noRandomOutput)
                        return;
                    float chance = recipe.getOutputChance();
                    tooltip.add(1, createjeicompat$chanceComponent(chance));
                });

        List<SequencedRecipe<?>> sequence = recipe.getSequence();
        int totalSteps = sequence.size();
        int currentPage = SequencedAssemblyPageManager.getCurrentPage(recipe);
        int stepsPerPage = SequencedAssemblyPageManager.getStepsPerPage();
        int startIndex = currentPage * stepsPerPage;
        int endIndex = Math.min(startIndex + stepsPerPage, totalSteps);

        int pageWidth = 0;
        for (int i = startIndex; i < endIndex; i++) {
            pageWidth += createjeicompat$getSubCategory(sequence.get(i)).getWidth() + STEP_MARGIN;
        }
        if (pageWidth > 0) {
            pageWidth -= STEP_MARGIN;
        }

        int x = pageWidth / -2 + createjeicompat$getBackground().getWidth() / 2;

        for (int i = 0; i < totalSteps; i++) {
            SequencedRecipe<?> sequencedRecipe = sequence.get(i);
            if (i >= startIndex && i < endIndex) {
                // Current page: real, positioned, interactive slot.
                SequencedAssemblySubCategory subCategory = createjeicompat$getSubCategory(sequencedRecipe);
                subCategory.setRecipe(builder, sequencedRecipe, focuses, x);
                x += subCategory.getWidth() + STEP_MARGIN;
            } else {
                // Other pages: keep the ingredients registered (so "used in" search still
                // finds this recipe) without giving them a visible/interactive slot.
                createjeicompat$registerInvisible(builder, sequencedRecipe);
            }
        }

        // Repeats beyond the first loop were already invisible-only in vanilla Create.
        for (int i = 1; i < recipe.getLoops(); i++) {
            for (SequencedRecipe<?> sequencedRecipe : sequence) {
                createjeicompat$registerInvisible(builder, sequencedRecipe);
            }
        }
    }

    @Unique
    private void createjeicompat$registerInvisible(IRecipeLayoutBuilder builder, SequencedRecipe<?> sequencedRecipe) {
        NonNullList<Ingredient> sequencedIngredients = sequencedRecipe.getRecipe().getIngredients();
        for (Ingredient ingredient : sequencedIngredients.subList(1, sequencedIngredients.size()))
            builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addIngredients(ingredient);
        for (FluidIngredient fluidIngredient : sequencedRecipe.getRecipe().getFluidIngredients())
            builder.addInvisibleIngredients(RecipeIngredientRole.INPUT)
                    .addIngredients(ForgeTypes.FLUID_STACK, fluidIngredient.getMatchingFluidStacks());
    }

    // ---- draw: paginated step visuals + up/down page buttons ----

    @Overwrite(remap = false)
    public void draw(SequencedAssemblyRecipe recipe, IRecipeSlotsView iRecipeSlotsView, GuiGraphics graphics, double mouseX, double mouseY) {
        Font font = Minecraft.getInstance().font;

        graphics.pose().pushPose();

        graphics.pose().pushPose();
        graphics.pose().translate(0, 15, 0);
        boolean singleOutput = recipe.getOutputChance() == 1;
        int xOffset = singleOutput ? 0 : -7;
        // AllGuiTextures.JEI_LONG_ARROW: jei/widgets.png, u=19 v=0, 71x10
        graphics.blit(createjeicompat$JEI_WIDGETS, 52 + xOffset, 79, 19, 0, 71, 10);
        if (!singleOutput) {
            // AllGuiTextures.JEI_CHANCE_SLOT: jei/widgets.png, u=20 v=156, 18x18
            graphics.blit(createjeicompat$JEI_WIDGETS, 150 + xOffset, 75, 20, 156, 18, 18);
            Component component = Component.literal("?").withStyle(ChatFormatting.BOLD);
            graphics.drawString(font, component, font.width(component) / -2 + 8 + 150 + xOffset, 2 + 78, 0xefefef);
        }

        if (recipe.getLoops() > 1) {
            graphics.pose().pushPose();
            graphics.pose().translate(15, 9, 0);
            // (Skipping AllIcons.I_SEQ_REPEAT's small loop icon here - it lives in
            // Create's icons.png atlas and its exact atlas cell isn't worth
            // reverse-engineering just for a decorative icon; the "xN" text below
            // already conveys the repeat count.)
            Component repeat = Component.literal("x" + recipe.getLoops());
            graphics.drawString(font, repeat, 66 + xOffset, 80, 0x888888, false);
            graphics.pose().popPose();
        }

        graphics.pose().popPose();

        List<SequencedRecipe<?>> sequence = recipe.getSequence();
        int totalSteps = sequence.size();
        int currentPage = SequencedAssemblyPageManager.getCurrentPage(recipe);
        int stepsPerPage = SequencedAssemblyPageManager.getStepsPerPage();
        int startIndex = currentPage * stepsPerPage;
        int endIndex = Math.min(startIndex + stepsPerPage, totalSteps);
        int totalPages = SequencedAssemblyPageManager.getTotalPages(recipe);

        int pageWidth = 0;
        for (int i = startIndex; i < endIndex; i++) {
            pageWidth += createjeicompat$getSubCategory(sequence.get(i)).getWidth() + STEP_MARGIN;
        }
        if (pageWidth > 0) {
            pageWidth -= STEP_MARGIN;
        }

        IDrawable background = createjeicompat$getBackground();
        int bgWidth = background.getWidth();
        int bgHeight = background.getHeight();
        int x = bgWidth / 2 - pageWidth / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(x, 0, 0);
        for (int i = startIndex; i < endIndex; i++) {
            SequencedRecipe<?> sequencedRecipe = sequence.get(i);
            SequencedAssemblySubCategory subCategory = createjeicompat$getSubCategory(sequencedRecipe);
            int subWidth = subCategory.getWidth();
            MutableComponent component = Component.literal(createjeicompat$toRomanNumeral(i + 1));
            graphics.drawString(font, component, font.width(component) / -2 + subWidth / 2, 2, 0x888888, false);
            subCategory.draw(sequencedRecipe, graphics, mouseX - x, mouseY, i);
            graphics.pose().translate(subWidth + STEP_MARGIN, 0, 0);
        }
        graphics.pose().popPose();

        if (totalPages > 1) {
            createjeicompat$drawPageControls(graphics, font, bgWidth, bgHeight, currentPage, totalPages, mouseX, mouseY);
        }

        graphics.pose().popPose();
    }

    /**
     * Draws, top to bottom in the bottom-right corner: an up arrow (previous page),
     * the "current/total" page counter, and a down arrow (next page).
     * NOTE: these pixel coordinates are a reasonable first guess based on the
     * category's background size (bgWidth/bgHeight, bottom-right corner).
     * If they visually overlap anything once you see it in-game, adjust the
     * upX/upY/downX/downY math here AND in handleInput()/getTooltipStrings()
     * below (all three must stay in sync since they compute hit-boxes
     * independently rather than sharing cached state).
     */
    @Unique
    private void createjeicompat$drawPageControls(GuiGraphics graphics, Font font, int bgWidth, int bgHeight,
                                                    int currentPage, int totalPages, double mouseX, double mouseY) {
        boolean canPrev = currentPage > 0;
        boolean canNext = currentPage < totalPages - 1;

        Component up = Component.literal("^");
        Component down = Component.literal("v");
        Component pageText = Component.literal((currentPage + 1) + "/" + totalPages);

        int lineHeight = font.lineHeight;
        int downY = bgHeight - lineHeight;
        int pageY = downY - lineHeight;
        int upY = pageY - lineHeight;

        int upX = bgWidth - font.width(up);
        int downX = bgWidth - font.width(down);
        int pageX = bgWidth - font.width(pageText);

        boolean hoverUp = canPrev && createjeicompat$isOver(mouseX, mouseY, upX, upY, font.width(up), lineHeight);
        boolean hoverDown = canNext && createjeicompat$isOver(mouseX, mouseY, downX, downY, font.width(down), lineHeight);

        graphics.drawString(font, up, upX, upY, canPrev ? (hoverUp ? 0xffffff : 0xaaaaaa) : 0x555555, false);
        graphics.drawString(font, pageText, pageX, pageY, 0x888888, false);
        graphics.drawString(font, down, downX, downY, canNext ? (hoverDown ? 0xffffff : 0xaaaaaa) : 0x555555, false);
    }

    @Unique
    private boolean createjeicompat$isOver(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ---- handleInput: click the up/down buttons to change page (works in JEI and EMI) ----

    /**
     * This method does not exist as a real override in vanilla
     * SequencedAssemblyCategory (it only inherits IRecipeCategory's default
     * no-op implementation), so it's added here as a brand new method rather
     * than an @Overwrite of an existing one.
     *
     * IMPORTANT: the parameter type here is deliberately Object, not
     * SequencedAssemblyRecipe. IRecipeCategory<T>#handleInput's real
     * bytecode signature (after generic erasure) is
     * handleInput(Object, double, double, InputConstants.Key). A normal
     * Java class that "implements IRecipeCategory<SequencedAssemblyRecipe>"
     * gets a synthetic bridge method generated by javac for free, but a
     * method merged in via Mixin does NOT get that bridge generated
     * automatically. Without this, JEI/EMI's interface-typed call
     * (recipeCategory.handleInput(recipe, ...)) still resolves to the
     * original do-nothing default method, and every click/keypress is
     * silently swallowed - which was exactly the bug (nothing happened on
     * click or key press).
     *
     * Handles two independent ways to change page (both call the same
     * SequencedAssemblyPageManager methods, so either one keeps the other in sync):
     *   1) Clicking the on-screen ^ / v buttons (mouse input).
     *   2) Pressing the Up / Down arrow keys on the keyboard (keyboard input).
     * Both JEI and EMI forward mouse clicks AND key presses on the recipe
     * widget to this same handleInput() method, so this one method covers
     * both viewers for both input styles.
     */
    public boolean handleInput(Object recipeObj, double mouseX, double mouseY, InputConstants.Key input) {
        SequencedAssemblyRecipe recipe = (SequencedAssemblyRecipe) recipeObj;
        createjeicompat$LOGGER.info("handleInput called: type={} value={} mouseX={} mouseY={}",
                input.getType(), input.getValue(), mouseX, mouseY);

        if (input.getType() == InputConstants.Type.KEYSYM) {
            if (input.getValue() == GLFW.GLFW_KEY_UP) {
                boolean changed = SequencedAssemblyPageManager.previousPage(recipe);
                createjeicompat$LOGGER.info("UP key: page changed = {}", changed);
                if (changed) createjeicompat$scheduleRefresh();
                return changed;
            }
            if (input.getValue() == GLFW.GLFW_KEY_DOWN) {
                boolean changed = SequencedAssemblyPageManager.nextPage(recipe);
                createjeicompat$LOGGER.info("DOWN key: page changed = {}", changed);
                if (changed) createjeicompat$scheduleRefresh();
                return changed;
            }
            return false;
        }

        if (input.getType() != InputConstants.Type.MOUSE || input.getValue() != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }

        int totalPages = SequencedAssemblyPageManager.getTotalPages(recipe);
        if (totalPages <= 1) {
            createjeicompat$LOGGER.info("mouse click: totalPages={}, nothing to page through", totalPages);
            return false;
        }

        Font font = Minecraft.getInstance().font;
        IDrawable background = createjeicompat$getBackground();
        int bgWidth = background.getWidth();
        int bgHeight = background.getHeight();
        int lineHeight = font.lineHeight;

        Component up = Component.literal("^");
        Component down = Component.literal("v");
        int downY = bgHeight - lineHeight;
        int upY = downY - lineHeight - lineHeight;
        int upX = bgWidth - font.width(up);
        int downX = bgWidth - font.width(down);

        int currentPage = SequencedAssemblyPageManager.getCurrentPage(recipe);
        createjeicompat$LOGGER.info(
                "mouse click: bgWidth={} bgHeight={} upBox=({},{},{},{}) downBox=({},{},{},{}) currentPage={} totalPages={}",
                bgWidth, bgHeight, upX, upY, font.width(up), lineHeight, downX, downY, font.width(down), lineHeight,
                currentPage, totalPages);

        if (currentPage > 0 && createjeicompat$isOver(mouseX, mouseY, upX, upY, font.width(up), lineHeight)) {
            boolean changed = SequencedAssemblyPageManager.previousPage(recipe);
            createjeicompat$LOGGER.info("up button hit: page changed = {}", changed);
            if (changed) createjeicompat$scheduleRefresh();
            return changed;
        }
        if (currentPage < totalPages - 1 && createjeicompat$isOver(mouseX, mouseY, downX, downY, font.width(down), lineHeight)) {
            boolean changed = SequencedAssemblyPageManager.nextPage(recipe);
            createjeicompat$LOGGER.info("down button hit: page changed = {}", changed);
            if (changed) createjeicompat$scheduleRefresh();
            return changed;
        }
        createjeicompat$LOGGER.info("mouse click: not within either button's box");
        return false;
    }

    /**
     * Deferred via Minecraft.execute() (runs right after the current
     * frame/input event finishes) rather than called synchronously, because
     * this runs from inside JEI's/EMI's own click-dispatch loop - mutating
     * their cached layout/widget lists while they're still iterating over
     * it could corrupt that iteration.
     *
     * Calls both refreshers; each one internally checks whether the
     * currently-open screen is actually theirs and no-ops otherwise, so
     * only the relevant one does anything. The EMI call is additionally
     * gated on ModList so we never even touch EMI's classes (which would
     * throw NoClassDefFoundError) on a setup that doesn't have EMI
     * installed - JEI-only users are unaffected either way.
     */
    @Unique
    private void createjeicompat$scheduleRefresh() {
        Minecraft.getInstance().execute(() -> {
            JeiRecipesGuiRefresher.refreshIfShowing();
            if (net.minecraftforge.fml.ModList.get().isLoaded("emi")) {
                EmiRecipeScreenRefresher.refreshIfShowing();
            }
        });
    }

    // ---- getTooltipStrings: paginated step tooltips + button hint ----

    @Overwrite(remap = false)
    public List<Component> getTooltipStrings(SequencedAssemblyRecipe recipe, IRecipeSlotsView iRecipeSlotsView, double mouseX, double mouseY) {
        List<Component> tooltip = new ArrayList<>();

        MutableComponent junk = Component.translatable("create.recipe.assembly.junk");

        boolean singleOutput = recipe.getOutputChance() == 1;
        boolean willRepeat = recipe.getLoops() > 1;

        int xOffset = -7;
        int minX = 150 + xOffset;
        int maxX = minX + 18;
        int minY = 90;
        int maxY = minY + 18;
        if (!singleOutput && mouseX >= minX && mouseX < maxX && mouseY >= minY && mouseY < maxY) {
            float chance = recipe.getOutputChance();
            tooltip.add(junk);
            tooltip.add(createjeicompat$chanceComponent(1 - chance));
            return tooltip;
        }

        minX = 55 + xOffset;
        maxX = minX + 65;
        minY = 92;
        maxY = minY + 24;
        if (willRepeat && mouseX >= minX && mouseX < maxX && mouseY >= minY && mouseY < maxY) {
            tooltip.add(Component.translatable("create.recipe.assembly.repeat", recipe.getLoops()));
            return tooltip;
        }

        List<SequencedRecipe<?>> sequence = recipe.getSequence();
        int totalSteps = sequence.size();
        int currentPage = SequencedAssemblyPageManager.getCurrentPage(recipe);
        int stepsPerPage = SequencedAssemblyPageManager.getStepsPerPage();
        int startIndex = currentPage * stepsPerPage;
        int endIndex = Math.min(startIndex + stepsPerPage, totalSteps);
        int totalPages = SequencedAssemblyPageManager.getTotalPages(recipe);

        if (totalPages > 1) {
            Font font = Minecraft.getInstance().font;
            IDrawable background = createjeicompat$getBackground();
            int bgWidth = background.getWidth();
            int bgHeight = background.getHeight();
            int lineHeight = font.lineHeight;

            Component up = Component.literal("^");
            Component down = Component.literal("v");
            int downY = bgHeight - lineHeight;
            int upY = downY - lineHeight - lineHeight;
            int upX = bgWidth - font.width(up);
            int downX = bgWidth - font.width(down);

            if (currentPage > 0 && createjeicompat$isOver(mouseX, mouseY, upX, upY, font.width(up), lineHeight)) {
                tooltip.add(Component.translatable("create.recipe.assembly.step", startIndex));
                return tooltip;
            }
            if (currentPage < totalPages - 1 && createjeicompat$isOver(mouseX, mouseY, downX, downY, font.width(down), lineHeight)) {
                tooltip.add(Component.translatable("create.recipe.assembly.step", endIndex + 1));
                return tooltip;
            }
        }

        int pageWidth = 0;
        for (int i = startIndex; i < endIndex; i++) {
            pageWidth += createjeicompat$getSubCategory(sequence.get(i)).getWidth() + STEP_MARGIN;
        }
        if (pageWidth > 0) {
            pageWidth -= STEP_MARGIN;
        }

        int bgWidth = createjeicompat$getBackground().getWidth();
        int pageX = bgWidth / 2 - pageWidth / 2;

        double relativeX = mouseX - pageX;
        for (int i = startIndex; i < endIndex; i++) {
            SequencedRecipe<?> sequencedRecipe = sequence.get(i);
            SequencedAssemblySubCategory subCategory = createjeicompat$getSubCategory(sequencedRecipe);
            if (relativeX >= 0 && relativeX < subCategory.getWidth() && mouseY >= 0 && mouseY < 25) {
                tooltip.add(Component.translatable("create.recipe.assembly.step", i + 1));
                tooltip.add(sequencedRecipe.getAsAssemblyRecipe().getDescriptionForAssembly().plainCopy().withStyle(ChatFormatting.DARK_GREEN));
                return tooltip;
            }
            relativeX -= subCategory.getWidth() + STEP_MARGIN;
        }

        return tooltip;
    }
}
