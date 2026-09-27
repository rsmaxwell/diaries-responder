package com.rsmaxwell.diaries.responder.utilities;

import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

/** Deployment gate, separate from user authorization and read/lifecycle access. */
public final class ImageFragmentWritePolicy {
    private ImageFragmentWritePolicy() { }
    public static boolean enabled(DiaryContext context) {
        return context.getConfig() != null && context.getConfig().isImageFragmentWritesEnabled();
    }
    public static void requireEnabled(DiaryContext context) throws RpcStatusException {
        if (!enabled(context)) throw RpcStatusException.forbidden("Image Fragment authoring is disabled by responder configuration");
    }
}
