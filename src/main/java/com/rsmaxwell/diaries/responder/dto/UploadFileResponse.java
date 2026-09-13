package com.rsmaxwell.diaries.responder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Additive file RPC response; deployment-specific paths never enter the nested Image DTO. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UploadFileResponse(String name, String subdir, long size, String path, String url,
        Long imageId, ImagePublishDTO image) { }
