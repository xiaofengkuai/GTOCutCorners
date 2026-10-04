package com.gtocutcorners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GTOConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Paths.get("config", "gtocutcorners.json");

    /** Legacy switch: true forces every non-excluded recipe to exactly 1 tick (ignores speedDivisor). */
    public boolean oneTickMode = false;

    /**
     * Divide non-excluded recipe durations by this factor: duration = max(1, original / speedDivisor).
     * Only used when oneTickMode is false. Values below 1 are clamped back to 1 on load.
     */
    public int speedDivisor = 4;

    public boolean patchVanilla = true;
    public boolean patchGT = true;
    public boolean patchGTMass = true;

    /**
     * Diagnostic switch: set false to skip GTO-window machine registration.
     * Used to isolate whether the registered multiblocks break GTO's EMI data init.
     */
    public boolean registerMachines = true;

    /** Set true and start a world once to export content database to config/gtocutcorners/content_dump/. */
    public boolean dumpContent = false;

    /** Super ME Pattern Buffer layout (capacity = patternsPerRow * rowsPerPage * maxPages). */
    public SuperPatternBuffer superPatternBuffer = new SuperPatternBuffer();

    public static class SuperPatternBuffer {
        /** 每行样板数量. */
        public int patternsPerRow = 9;
        /** 每页行数. */
        public int rowsPerPage = 6;
        /** 最大页数. */
        public int maxPages = 255;
    }

    public static GTOConfig load() {
        GTOConfig cfg = new GTOConfig();
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            if (Files.exists(CONFIG_PATH)) {
                String raw = Files.readString(CONFIG_PATH);
                cfg = GSON.fromJson(raw, GTOConfig.class);
                if (cfg == null) {
                    cfg = new GTOConfig();
                }
            } else {
                Files.writeString(CONFIG_PATH, GSON.toJson(cfg));
            }
        } catch (Exception e) {
            System.err.println("[GTOConfig] load failed: " + e.getMessage());
        }
        if (cfg.speedDivisor < 1) cfg.speedDivisor = 1;
        return cfg;
    }
}
