package com.rsmaxwell.diaries.responder.utilities;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

/** Three-state external image link writer: omission PRESERVEs, value SETs, true CLEARs. */
public final class ImageLinkWritePolicy {
    private ImageLinkWritePolicy() { }

    public static String validate(String value) throws RpcStatusException {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 4096)
            throw RpcStatusException.badRequest("Invalid image link URL");
        for (int pos = 0; pos < value.length(); pos++) {
            char c = value.charAt(pos);
            if (c <= ' ' || c == 0x7f)
                throw RpcStatusException.badRequest("Invalid image link URL");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.isOpaque() || uri.getRawAuthority() == null || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535)
                throw RpcStatusException.badRequest("Invalid image link URL");
            return value; // Do not canonicalise away query fragments, case or encoding.
        } catch (URISyntaxException invalid) {
            throw RpcStatusException.badRequest("Invalid image link URL");
        }
    }

    public static void apply(Map<String, Object> args, Fragment fragment)
            throws RpcStatusException {
        boolean supplied = args.containsKey("imageLinkUrl");
        boolean clear = args.containsKey("clearImageLinkUrl");
        if (!supplied && !clear) return;
        if (fragment.getType() != FragmentType.IMAGE)
            throw RpcStatusException.badRequest("Only IMAGE Fragments may have an image link");
        if (supplied == clear) // Both fields are forbidden (and false clear is never a valid request).
            throw RpcStatusException.badRequest("Choose imageLinkUrl or clearImageLinkUrl");
        if (clear && !Boolean.TRUE.equals(args.get("clearImageLinkUrl")))
            throw RpcStatusException.badRequest("clearImageLinkUrl must be true");
        String value = null;
        if (supplied) {
            if (!(args.get("imageLinkUrl") instanceof String))
                throw RpcStatusException.badRequest("imageLinkUrl must be a URL string");
            value = validate((String) args.get("imageLinkUrl"));
        }
        fragment.setImageLinkUrl(value);
    }
}
