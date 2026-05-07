package com.rsmaxwell.diaries.responder.utilities;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class Authorization {

	private static final Logger log = LoggerFactory.getLogger(Authorization.class);

	public static String getToken(String secret, String subject, int expiration, ChronoUnit units) {

		Map<String, Object> claims = new HashMap<String, Object>();

		return getTokenWithClaims(secret, subject, expiration, units, claims);
	}

	public static String getTokenWithClaims(String secret, String subject, int expiration, ChronoUnit units, Map<String, Object> claims) {

		Instant now = Instant.now();
		Date expireTime = Date.from(now.plus(expiration, units));

		byte[] secretBytes = Base64.getDecoder().decode(secret);
		SecretKey secretKey = Keys.hmacShaKeyFor(secretBytes);

		String jwt = null;
		try {
			// @formatter:off
			    JwtBuilder builder = Jwts.builder()
					.subject(subject)
			        .expiration(expireTime)
			        .signWith(secretKey);
				// @formatter:on

			for (String key : claims.keySet()) {
				Object value = claims.get(key);
				builder.claim(key, value);
			}

			jwt = builder.compact();

		} catch (Throwable t) {
			log.error("Unhandled exception while handling request", t);
		}

		return jwt;
	}

	public static Claims parseToken(String secret, String token) throws ExpiredJwtException {

		byte[] secretBytes = Base64.getDecoder().decode(secret);
		SecretKey key = Keys.hmacShaKeyFor(secretBytes);

		//@formatter:off
		return Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		//@formatter:on
	}

	public static String getRefreshToken(Map<String, Object> args) {

		log.info("Authorization.getRefreshToken");

		Object value = args.get("refreshToken");
		if (value == null) {
			log.info("Authorization.getRefreshToken: refreshToken not found");
			return null;
		}

		String refreshToken = null;
		if (value instanceof String) {
			refreshToken = (String) value;
		} else {
			log.info("Authorization.getRefreshToken: refreshToken is an unexpected type: " + value.getClass().getName());
			return null;
		}

		return refreshToken;
	}

	public static String getAccessToken(List<UserProperty> userProperties) {

		log.info("Authorization.getAccessToken");

		String accessToken = null;
		for (UserProperty property : userProperties) {
			if ("accessToken".equals(property.getKey())) {
				accessToken = property.getValue();
				break;
			}
		}
		if (accessToken == null) {
			log.info("Authorization.getAccessToken: accessToken not found'");
			return null;
		}

		return accessToken;
	}

	public static Claims checkToken(DiaryContext context, String subject, String token) throws RpcStatusException {

		log.info("Authorization.checkToken");

		if (token == null) {
			throw RpcStatusException.unauthorized("accessToken not found'");
		}

		Claims claims = null;
		try {
			String secret = context.getSecret();
			claims = parseToken(secret, token);
		} catch (ExpiredJwtException e) {
			log.info("Authorization.checkToken: JWT has expired'");
			throw RpcStatusException.unauthorized("JWT has expired");
		}

		String actual = claims.getSubject();
		if (!subject.equals(actual)) {
			throw RpcStatusException.unauthorized(String.format("unexpected subject: expected: %s, actual: %s", subject, actual));
		}

		return claims;
	}

	public static void checkActive(Claims claims) throws RpcStatusException {
		String status = (String) claims.get("status");
		if (!"ACTIVE".equals(status)) {
			throw RpcStatusException.unauthorized("account is not active");
		}
	}

	public static void checkRoleAtLeast(Claims claims, Role requiredRole) throws RpcStatusException {
		String roleValue = (String) claims.get("role");
		if (roleValue == null) {
			throw RpcStatusException.unauthorized("no role claim");
		}

		try {
			Role actualRole = Role.valueOf(roleValue);

			if (!actualRole.atLeast(requiredRole)) {
				throw RpcStatusException.unauthorized("insufficient role");
			}

		} catch (IllegalArgumentException e) {
			throw RpcStatusException.unauthorized(String.format("invalid role claim: {}", roleValue));
		}
	}
}
