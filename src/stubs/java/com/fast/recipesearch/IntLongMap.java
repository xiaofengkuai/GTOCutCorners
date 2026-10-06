package com.fast.recipesearch;

/**
 * Compile-only stub for the native-protected com.fast.recipesearch.IntLongMap
 * shipped by GTOCore (gtonativelib). The real class is injected at runtime by
 * the GTO native services, so it is absent from every jar on disk. This stub
 * exists solely so the mixin handler can name the type; it must never be
 * packaged into the mod jar (see the stubs source set in build.gradle).
 *
 * Only the members actually used by GTOCutCorners mixins are declared.
 */
public class IntLongMap {

    public void add(int key, long amount) {
        throw new IllegalStateException("compile-only stub; real IntLongMap is provided by gtonativelib at runtime");
    }

    public boolean isEmpty() {
        throw new IllegalStateException("compile-only stub; real IntLongMap is provided by gtonativelib at runtime");
    }

    public void clear() {
        throw new IllegalStateException("compile-only stub; real IntLongMap is provided by gtonativelib at runtime");
    }
}
