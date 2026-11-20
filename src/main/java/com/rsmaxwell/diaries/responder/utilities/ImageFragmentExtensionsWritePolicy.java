package com.rsmaxwell.diaries.responder.utilities;

import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

/** Independent safety switch: existing IMAGE authoring stays enabled when extensions are off. */
public final class ImageFragmentExtensionsWritePolicy {
    private ImageFragmentExtensionsWritePolicy() { }
    public static boolean enabled(DiaryContext context) {
        return context.getConfig() != null && context.getConfig().isImageFragmentExtensionsWritesEnabled();
    }
    public static void requireEnabled(DiaryContext context) throws RpcStatusException {
        if (!enabled(context))
            throw RpcStatusException.forbidden("IMAGE link and Marquee authoring is disabled by responder configuration");
    }
}
