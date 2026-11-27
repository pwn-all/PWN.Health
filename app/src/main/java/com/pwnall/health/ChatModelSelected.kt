package com.pwnall.health

/**
 * Smoke weights load only in a local model-test build (a debug build or the separate
 * `.modeltestrelease` one with an asset override). A shipped reader is never a smoke model, and no
 * hash-pinned exception exists.
 */
internal fun allowExperimentalChatModel(): Boolean =
    BuildConfig.CHAT_MODEL_EXPERIMENTAL &&
        (BuildConfig.DEBUG || BuildConfig.APPLICATION_ID.endsWith(".modeltestrelease"))
