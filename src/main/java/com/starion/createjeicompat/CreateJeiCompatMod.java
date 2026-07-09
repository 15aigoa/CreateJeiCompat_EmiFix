package com.starion.createjeicompat;

import net.minecraftforge.fml.common.Mod;

/**
 * Create JEI/EMI Compat - adds pagination (page-turn buttons) to Create's
 * Sequenced Assembly recipe display, so recipes with more than 6 steps
 * display correctly in both JEI and EMI.
 *
 * All of the actual work happens in a Mixin into Create's own
 * {@code com.simibubi.create.compat.jei.category.SequencedAssemblyCategory}.
 * That class implements JEI's {@code IRecipeCategory} interface, and EMI's
 * built-in JEI-compatibility layer ("JEmi") calls the very same
 * setRecipe()/draw()/getTooltipStrings()/handleInput() methods to display
 * the recipe inside EMI's own screens. Because of that, patching this one
 * class benefits both JEI and EMI without needing any EMI-specific code.
 *
 * This mod does not need to register anything at startup; no mod
 * constructor logic is required beyond the @Mod annotation itself.
 *
 * Original Create mod: https://github.com/Creators-of-Create/Create (MIT)
 * JEI: https://github.com/mezz/JustEnoughItems (MIT)
 * EMI: https://github.com/emilyploszaj/emi (MIT)
 */
@Mod(CreateJeiCompatMod.MOD_ID)
public class CreateJeiCompatMod {
    public static final String MOD_ID = "createjeicompat";
}
