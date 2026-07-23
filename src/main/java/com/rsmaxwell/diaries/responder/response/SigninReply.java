package com.rsmaxwell.diaries.responder.response;

public record SigninReply(
        String accessToken,
        String refreshToken,
        Integer refreshPeriod,
        Long userId,
        String username,
        String knownAs,
        String sessionId,
        String status,
        String role) {
}
