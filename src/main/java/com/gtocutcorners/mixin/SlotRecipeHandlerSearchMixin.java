package com.gtocutcorners.mixin;

import com.fast.recipesearch.IntLongMap;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * GTOCore bug fix: {@code SlotRecipeHandler.getSearchMap} builds the recipe
 * search map from the slot's AE item/fluid inputs only, so the programmed
 * circuit that {@code MEPatternVirtualInputHelper.convertPattern} moved into
 * {@code InternalSlot.circuitInventory} slot 0 is invisible to the fast recipe
 * search.
 *
 * <p>Consequences in GTOCore (bytecode-verified against gtocore 0.5.6-beta /
 * gtceu 26.7.3): a recipe containing an {@code IntCircuitIngredient} is keyed
 * by its circuit number in the RecipeDB container ({@code
 * GTRecipeType.convertItem}), but the search map never contains that key, so
 * the no-cache search path ({@code AbstractRHL.findRecipe} → {@code
 * GTRecipeType.search} → {@code IntMapContainer.match}) can never match a
 * circuit recipe. The slot's cached recipe masks this until something clears
 * it (pattern replacement, a transient failure of the cached-recipe
 * simulation); the buffer then dead-locks — inputs are pushed in, the circuit
 * slot shows the right circuit, yet no recipe is found — and only a re-log
 * rebuilds the cache via {@code readRecipeTag}.</p>
 *
 * <p>Fix: at the end of {@code getSearchMap}, add the circuit number currently
 * present in the slot's circuit inventory (key = configuration, amount = 1),
 * mirroring exactly what {@code GTRecipeType.convertItem} does on the recipe
 * side. Empty or non-circuit slots add nothing, preserving existing behavior.
 * With the search path unblocked, a lost cache self-heals: the next search
 * finds the recipe and GTOCore writes it back through {@code
 * onRecipeHandled}.</p>
 *
 * <p>Targets GTOCore-internal names, hence {@code remap = false}; {@code
 * require = 0} keeps the game loadable if GTOCore reshuffles the class. The
 * package-private {@code slot} field is read through a cached reflective
 * lookup because the {@code InternalSlot} class itself is package-private and
 * cannot be referenced from this package.</p>
 */
@Mixin(targets = "com.gtocore.common.machine.trait.InternalSlotRecipeHandler$SlotRecipeHandler", remap = false)
public abstract class SlotRecipeHandlerSearchMixin {

    private static Field gtocutcorners$slotField = null;

    /**
     * Resolves the package-private {@code SlotRecipeHandler.slot} field once;
     * returns null on any structural mismatch, disabling the fix gracefully.
     */
    private static Field gtocutcorners$resolveSlotField(Class<?> handlerClass) {
        if (gtocutcorners$slotField != null) {
            return gtocutcorners$slotField;
        }
        try {
            Field f = handlerClass.getDeclaredField("slot");
            f.setAccessible(true);
            gtocutcorners$slotField = f;
        } catch (ReflectiveOperationException | SecurityException e) {
            gtocutcorners$slotField = null;
        }
        return gtocutcorners$slotField;
    }

    @Inject(method = "getSearchMap", at = @At("RETURN"), require = 0)
    private void gtocutcorners$addCircuitKeyToSearchMap(com.gregtechceu.gtceu.api.recipe.GTRecipeType type,
                                                        CallbackInfoReturnable<IntLongMap> cir) {
        IntLongMap map = cir.getReturnValue();
        if (map == null) {
            return;
        }
        Field slotField = gtocutcorners$resolveSlotField(((Object) this).getClass());
        if (slotField == null) {
            return;
        }
        Object slot;
        try {
            slot = slotField.get(this);
        } catch (IllegalAccessException e) {
            return;
        }
        if (slot == null) {
            return;
        }
        ItemStack circuit = gtocutcorners$readCircuit(slot);
        if (circuit.isEmpty() || !IntCircuitBehaviour.isIntegratedCircuit(circuit)) {
            return;
        }
        // Same key/amount contract as GTRecipeType.convertItem for IntCircuitIngredient.
        map.add(IntCircuitBehaviour.getCircuitConfiguration(circuit), 1L);
    }

    /**
     * Reads slot 0 of the slot's circuit inventory. Both the field owner and
     * the field's declaring class are GTOCore-internal; the value type
     * ({@code NotifiableItemStackHandler}) is public, so only the field lookup
     * itself needs reflection.
     */
    private static ItemStack gtocutcorners$readCircuit(Object slot) {
        for (Class<?> c = slot.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().endsWith("MEPatternBufferPartMachine$InternalSlot")) {
                try {
                    Field f = c.getField("circuitInventory");
                    NotifiableItemStackHandler handler = (NotifiableItemStackHandler) f.get(slot);
                    return handler.storage.getStackInSlot(0);
                } catch (ReflectiveOperationException | ClassCastException e) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
