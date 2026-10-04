package com.gtocutcorners;

import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gtocutcorners.bootstrap.GTOCScannerUnlock;
import com.gtocutcorners.data.ContentDump;
import com.gtocutcorners.registry.GTOBlocks;
import com.gtocutcorners.registry.GTOCreativeTabs;
import com.gtocutcorners.registry.GTOCLang;
import com.gtocutcorners.registry.GTOItems;
import com.gtocutcorners.recipe.GTOCRecipes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GTOCutCorners.MODID)
public class GTOCutCorners {
    public static final String MODID = "gtocutcorners";

    private static GTOConfig config = new GTOConfig();

    private static final AtomicBoolean patchStarted = new AtomicBoolean(false);
    private static final AtomicBoolean patchDone = new AtomicBoolean(false);
    private static final AtomicBoolean jvmtiRetransformDone = new AtomicBoolean(false);

    private static FileWriter fw;
    private static FileWriter vfw;
    private static boolean vLogOk = false;

    static {
        try { fw = new FileWriter("gtocutcorners_java.log", true); }
        catch (Exception e) { System.err.println("[GTO] Cannot init log: " + e); }
        try { vfw = new FileWriter("gtocutcorners_vanilla_patch.log", false); vLogOk = true; }
        catch (Exception e) { System.err.println("[GTO] Cannot init vanilla log: " + e); }
    }

    public GTOCutCorners() {
        config = GTOConfig.load();
        jlog("Config: oneTick=" + config.oneTickMode
            + " speedDivisor=" + config.speedDivisor
            + " vanilla=" + config.patchVanilla + " gt=" + config.patchGT + " massPatch=" + config.patchGTMass);
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        GTOBlocks.BLOCKS.register(modBus);
        GTOItems.ITEMS.register(modBus);
        GTOCreativeTabs.TABS.register(modBus);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> {
            if (config.dumpContent) {
                jlog("[GTO] dumpContent enabled, exporting content database...");
                ContentDump.dump(e.getServer());
            }
        });
        modBus.addListener((FMLCommonSetupEvent e) -> {
            jlog("[GTO] FMLCommonSetupEvent: machine registration is handled by the GTO-window coremod");
            GTOCScannerUnlock.apply();
        });
        modBus.addListener((FMLClientSetupEvent e) -> GTOCLang.registerDynamic());
        modBus.addListener((FMLLoadCompleteEvent e) -> {
            // Recipes are owned by the coremod inline path only (GTOHJS-aligned).
            // No second registration path touches GTO recipe maps, to avoid
            // racing with GTO's async client-side data init.
            jlog("[GTO] FMLLoadCompleteEvent: recipes are owned by the inline coremod path");
        });
    }

    public static boolean registerMachines() {
        return config.registerMachines;
    }

    public static GTOConfig getConfig() {
        return config;
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MODID, path);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (level == null) return;
        if (patchStarted.compareAndSet(false, true)) {
            jlog("=== Patching at server start (once, synchronous) ===");
            try {
                loadAndPatch(level);
            } catch (Throwable t) {
                jlog("Server-start patch failed: " + t);
                patchStarted.set(false); // allow one login-time retry
            } finally {
                patchDone.set(true);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (patchDone.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!patchStarted.compareAndSet(false, true)) return;
        ServerLevel level = player.serverLevel();
        jlog("=== Server-start patch missed; patching on first login (background) ===");
        new Thread(() -> {
            try {
                loadAndPatch(level);
            } catch (Throwable t) {
                jlog("Login patch failed: " + t);
            } finally {
                patchDone.set(true);
            }
        }, "GTOCutCorners-Patcher").start();
    }

    // ======================== logging ========================

    public static synchronized void jlog(String msg) {
        String line = "[GTO] " + msg + "\n";
        System.out.print(line);
        if (fw == null) try { fw = new FileWriter("gtocutcorners_java.log", true); } catch (Exception ignored) {}
        if (fw != null) try { fw.write(line); fw.flush(); } catch (Exception ignored) {}
    }

    private static synchronized void vlog(String tag, String msg) {
        if (!vLogOk) return;
        try { vfw.write("[" + tag + "] " + msg + "\n"); vfw.flush(); } catch (Exception ignored) {}
    }
    private static void vlog(String tag, String fmt, Object... args) { vlog(tag, String.format(fmt, args)); }

    // ======================== JNI (real functions only) ========================

    private static native Collection<GTRecipeDefinition> getRecipeCollection();
    private static native int nativeSetIntField(Object obj, String fieldName, int newValue);
    /**
     * Batch-patch GT recipe durations. divisor semantics: 0 = force 1 tick
     * (oneTickMode sentinel), N >= 1 = duration = max(1, original / N).
     */
    private static native int nativeMassPatch(int divisor);
    private static native void nativeInitJVMTI();

    // ======================== cookingTime field discovery ========================

    private static Object unsafeObj;
    private static long cookingTimeOffset = -1;
    private static boolean unsafeOk, unsafeTried;
    private static Field cookingTimeField;
    private static boolean needRuntimeDiscovery = true;

    private static void tryNameCandidates() {
        String[] candidates = {"cookingTime", "f_43750_", "cookingTime_", "field_43750"};
        Class<?> cls = AbstractCookingRecipe.class;
        while (cls != null && cls != Object.class) {
            for (String name : candidates) {
                try {
                    Field f = cls.getDeclaredField(name);
                    if (f.getType() == int.class) { f.setAccessible(true); cookingTimeField = f; needRuntimeDiscovery = false; return; }
                } catch (NoSuchFieldException ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static void ensureUnsafe() {
        if (unsafeTried) return;
        unsafeTried = true;
        tryNameCandidates();
        if (cookingTimeField != null) tryInitUnsafe();
    }

    private static void tryInitUnsafe() {
        if (unsafeOk || cookingTimeField == null) return;
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field uf = uc.getDeclaredField("theUnsafe"); uf.setAccessible(true);
            unsafeObj = uf.get(null);
            Method ofo = uc.getMethod("objectFieldOffset", Field.class);
            cookingTimeOffset = (long) ofo.invoke(unsafeObj, cookingTimeField);
            unsafeOk = true;
        } catch (Throwable t) { jlog("Unsafe unavailable: " + t); }
    }

    private static void discoverFieldByValue(AbstractCookingRecipe sample) {
        if (cookingTimeField != null || !needRuntimeDiscovery) return;
        int expected = sample.getCookingTime();
        List<Field> candidates = new ArrayList<>();
        Class<?> cls = sample.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == int.class) { f.setAccessible(true); candidates.add(f); }
            }
            cls = cls.getSuperclass();
        }
        if (candidates.size() == 1) { cookingTimeField = candidates.get(0); needRuntimeDiscovery = false; return; }
        for (Field f : candidates) {
            try { if (f.getInt(sample) == expected) { cookingTimeField = f; needRuntimeDiscovery = false; return; } }
            catch (Exception ignored) {}
        }
    }

    private static boolean setCookingTime(Object recipe, int value) {
        if (needRuntimeDiscovery && recipe instanceof AbstractCookingRecipe acr) { discoverFieldByValue(acr); if (cookingTimeField != null) tryInitUnsafe(); }
        if (cookingTimeField == null) return false;
        try {
            if (unsafeOk && unsafeObj != null) {
                Method putInt = unsafeObj.getClass().getMethod("putInt", Object.class, long.class, int.class);
                putInt.invoke(unsafeObj, recipe, cookingTimeOffset, value);
            } else { cookingTimeField.setInt(recipe, value); }
            return true;
        } catch (Exception e) { return false; }
    }

    // ======================== GT recipe patch ========================

    /**
     * Exact recipe-type names excluded from duration patching, kept verbatim in
     * sync with genTypes[] in src/main/native/patcher.c (isExcludedType).
     * Matched with equals() against the path segment after ':' in
     * recipeType.toString() ("namespace:path", no trailing slash on types).
     * rock_breaker and heat_exchanger are deliberate balance exemptions,
     * not generators.
     */
    private static final Set<String> EXCLUDED_TYPES = Set.of(
        "combustion_generator", "gas_turbine", "steam_turbine",
        "plasma_generator", "thermal_generator", "semi_fluid_generator",
        "supercritical_steam_turbine", "rocket_engine", "large_boiler",
        "steam_boiler",
        "naquadah_reactor", "hyper_reactor", "advanced_hyper_reactor",
        "large_naquadah_reactor", "mana_garden", "annihilate_generator",
        "fuel_cell_energy_absorption",
        "fuel_cell_energy_transfer", "fuel_cell_energy_release",
        "space_elevator",
        "scanner", "world_data_scanner", "radiation_hatch", "space_probe_surface_reception",
        "rock_breaker", "heat_exchanger"
    );

    /** Identity-keyed original durations, so a repaired retry path can never divide twice. */
    private static final Map<GTRecipeDefinition, Integer> durationSnapshot = new IdentityHashMap<>();

    /** oneTickMode=true -> 1; otherwise max(1, orig/divisor) with identity-keyed snapshot. */
    private static int targetDuration(GTRecipeDefinition r) {
        if (config.oneTickMode) return 1;
        int orig = durationSnapshot.computeIfAbsent(r, k -> k.duration);
        return Math.max(1, orig / Math.max(1, config.speedDivisor));
    }

    private static boolean isExcluded(GTRecipeDefinition r) {
        if (r.recipeType == null) return false;
        String tn = r.recipeType.toString(); // "namespace:path"
        int colon = tn.indexOf(':');
        String path = colon >= 0 ? tn.substring(colon + 1) : tn;
        int slash = path.indexOf('/');
        if (slash >= 0) path = path.substring(0, slash);
        return EXCLUDED_TYPES.contains(path);
    }

    private static int patchGT(Collection<GTRecipeDefinition> recipes) {
        int count = 0, skipped = 0, errors = 0, total = 0;
        for (GTRecipeDefinition r : recipes) {
            total++;
            try {
                if (isExcluded(r)) { skipped++; continue; }
                int target = targetDuration(r);
                if (r.duration != target) {
                    nativeSetIntField(r, "duration", target);
                    count++;
                } else {
                    skipped++;
                }
            } catch (Exception e) { errors++; }
        }
        jlog("patchGT: " + count + " patched " + skipped + " skipped " + errors + " errors");
        return count;
    }

    // ======================== vanilla recipe patch ========================

    private static int vanillaCached = -1;

    private static int patchVanilla(ServerLevel level) {
        if (vanillaCached > 0) { jlog("patchVanilla: already patched " + vanillaCached + " (cache)"); return vanillaCached; }
        ensureUnsafe();
        int count = 0, errs = 0, total = 0;
        try {
            RecipeManager rm = level.getRecipeManager();
            Collection<?> all = rm.getRecipes();
            total = all.size();
            if (total == 0) return 0;
            for (Object r : all) {
                if (r instanceof AbstractCookingRecipe acr) {
                    int target;
                    if (config.oneTickMode) {
                        target = 1;
                    } else {
                        int orig = acr.getCookingTime();
                        target = Math.max(1, orig / Math.max(1, config.speedDivisor));
                    }
                    try { if (setCookingTime(acr, target)) count++; else errs++; } catch (Exception e) { errs++; }
                }
            }
            jlog("patchVanilla: total=" + total + " patched=" + count + " errors=" + errs);
            vanillaCached = count;
        } catch (Exception e) { jlog("patchVanilla FATAL: " + e); }
        return count;
    }

    // ======================== main ========================

    private static void loadAndPatch(ServerLevel level) {
        jlog("=== START ===");
        try {
            String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String libName;
            if (osName.contains("win")) libName = "libgtocutcorners_native.dll";
            else if (osName.contains("mac")) libName = "libgtocutcorners_native.dylib";
            else libName = "libgtocutcorners_native.so";
            InputStream in = GTOCutCorners.class.getClassLoader().getResourceAsStream("native/" + libName);
            if (in == null) { jlog("Native lib not found: " + libName); vlog("ERR", "Native lib not found: %s", libName); return; }
            Path tmp = Files.createTempDirectory("g"); Path dp = tmp.resolve(libName);
            Files.copy(in, dp, StandardCopyOption.REPLACE_EXISTING); in.close();
            System.load(dp.toAbsolutePath().toString());
            jlog("Native lib loaded: " + libName);

            // JVMTI init (bytecode hooks: drills, custom machines, overclocking)
            if (jvmtiRetransformDone.compareAndSet(false, true)) {
                nativeInitJVMTI();
                jlog("JVMTI retransform done");
            } else {
                jlog("JVMTI retransform already done, skipping");
            }

            // divisor: 0 = oneTickMode sentinel (force 1 tick), else the configured factor
            int divisor = config.oneTickMode ? 0 : Math.max(1, config.speedDivisor);

            // vanilla patch
            int va = 0;
            if (config.patchVanilla) va = patchVanilla(level);

            // GT patch
            int massResult = 0;
            if (config.patchGT) {
                Collection<GTRecipeDefinition> recipes = getRecipeCollection();
                jlog("Got " + recipes.size() + " GT recipes");
                if (config.patchGTMass) {
                    massResult = nativeMassPatch(divisor);
                    jlog("nativeMassPatch(divisor=" + divisor + "): " + massResult);
                    if (massResult < 0) {
                        int fallback = patchGT(recipes);
                        jlog("native mass patch failed; Java fallback patched " + fallback);
                        massResult = fallback;
                    }
                }
            }

            jlog("=== DONE MassPatch=" + massResult + " Vanilla=" + va + " ===");
        } catch (Throwable t) {
            jlog("FATAL: " + t);
            for (StackTraceElement s : t.getStackTrace()) jlog("  " + s);
        }
    }
}
