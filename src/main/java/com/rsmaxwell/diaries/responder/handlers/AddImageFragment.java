package com.rsmaxwell.diaries.responder.handlers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentCreationResult;
import com.rsmaxwell.diaries.responder.utilities.FragmentSequenceNormaliser;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

/** Creates an IMAGE fragment in the existing Page/date chronology. */
public class AddImageFragment extends RequestHandler {
    @Override
    public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> properties) throws Exception {
        DiaryContext context = (DiaryContext) ctx;
        try {
            var claims = Authorization.checkToken(context, "access", Authorization.getAccessToken(properties == null ? List.of() : properties));
            Authorization.checkActive(claims);
            Authorization.checkRoleAtLeast(claims, Role.EDITOR);
        } catch (RpcStatusException | io.jsonwebtoken.JwtException | IllegalArgumentException | ClassCastException failure) {
            throw RpcStatusException.unauthorized("Active editor authorization required");
        }

        com.rsmaxwell.diaries.responder.utilities.ImageFragmentWritePolicy.requireEnabled(context);

        Fragment candidate;
        long pageId;
        try {
            pageId = positiveId(args.get("pageId"));
            int year = number(args.get("year")).intValueExact();
            int month = number(args.get("month")).intValueExact();
            int day = number(args.get("day")).intValueExact();
            LocalDate.of(year, month, day);
            // Same four-decimal, no-rounding insertion semantics as AddFragment.
            BigDecimal sequence = number(args.get("sequence")).setScale(4);
            if (sequence.precision() > 10) throw new IllegalArgumentException("Sequence exceeds database precision");
            if (!(args.get("text") instanceof String text) || text.length() > 4096)
                throw new IllegalArgumentException("Text must be a string of at most 4096 characters");
            Long imageId = args.get("imageId") == null ? null : positiveId(args.get("imageId"));
            // Caller type/marquee/id fields cannot override server-authoritative identity.
            candidate = Fragment.builder().version(0L).type(FragmentType.IMAGE).persistedImageId(imageId)
                    .year(year).month(month).day(day).sequence(sequence).text(text).build();
        } catch (Exception invalid) {
            throw RpcStatusException.badRequest("Invalid pageId, date, sequence, text or imageId");
        }
        try {
            candidate.setPage(context.inflatePage(pageId));
        } catch (Exception missingPage) {
            throw RpcStatusException.badRequest("Page could not be resolved: " + pageId);
        }
        FragmentCreationResult creation;
        try {
            creation = context.saveImageFragmentAndNormalise(candidate);
        } catch (RpcStatusException status) {
            throw status;
        } catch (IllegalArgumentException invalidReference) {
            throw RpcStatusException.badRequest("Image reference could not be resolved");
        } catch (Exception failure) {
            throw RpcStatusException.internalError("Unable to save Image Fragment");
        }
        Fragment saved = creation.getFragment();
        FragmentPublishDTO dto = new FragmentPublishDTO(saved, null);
        try {
            FragmentSequenceNormaliser.publishCreation(context, creation);
        } catch (Exception failure) {
            throw RpcStatusException.internalError("Fragment " + saved.getId()
                    + " was saved, but retained publication failed; do not repeat creation blindly");
        }
        return Response.success(dto);
    }

    private static BigDecimal number(Object value) {
        if (!(value instanceof Number) && !(value instanceof String))
            throw new IllegalArgumentException("Number required");
        return new BigDecimal(value.toString());
    }

    private static long positiveId(Object value) {
        long id = number(value).longValueExact();
        if (id <= 0) throw new IllegalArgumentException("Positive id required");
        return id;
    }
}
