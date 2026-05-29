package com.rsmaxwell.diaries.responder.response;

import lombok.Data;

@Data
public class SigninReply {

	private String accessToken;
	private String refreshToken;
	private Integer refreshPeriod;
	private Long userId;
	private String username;
	private String knownAs;
	private String sessionId;
	private String status;
	private String role;

	public SigninReply(String accessToken, String refreshToken, Integer refreshPeriod, Long id, String username, String knownAs, String sessionId, String status, String role) {
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.refreshPeriod = refreshPeriod;
		this.userId = id;
		this.username = username;
		this.knownAs = knownAs;
		this.sessionId = sessionId;
		this.status = status;
		this.role = role;
	}

}
