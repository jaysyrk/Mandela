package io.github.jaysyrk.mandela.core.visibility;

/** What the visibility cache wants the caller to do with an object this frame. */
public enum Visibility {
    /** No usable cached answer; run the occlusion test and report back via {@code submit}. */
    TEST,
    /** Draw it, without testing. */
    RENDER,
    /** Skip it, without testing. */
    CULL
}
