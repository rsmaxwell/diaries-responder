package com.rsmaxwell.diaries.response.utilities;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Random;

import javax.crypto.SecretKey;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

public class Authorization {

	private static final Logger log = LogManager.getLogger(Authorization.class);

	public static String getToken(String secret, String subject, int expiration, ChronoUnit units) {

		Instant now = Instant.now();
		Date expireTime = Date.from(now.plus(expiration, units));

		byte[] secretBytes = Base64.getDecoder().decode(secret);
		SecretKey key = Keys.hmacShaKeyFor(secretBytes);

		String jwt = null;
		try {
			// @formatter:off
			    jwt = Jwts.builder()
					.subject(subject)
			        .claim("id20", new Random().nextInt(20) + 1)
			        .expiration(expireTime)
			        .signWith(key)
			        .compact(); 
			// @formatter:on
		} catch (Throwable t) {
			log.catching(t);
		}

		return jwt;
	}

	public static Claims parseToken(String secret, String token) throws ExpiredJwtException {
		log.info(String.format("Authorization.parseToken: secret: %s, token: %s", secret, token));

		byte[] secretBytes = Base64.getDecoder().decode(secret);
		SecretKey key = Keys.hmacShaKeyFor(secretBytes);

		log.info("Authorization.parseToken: before Jwts.parser");

		//@formatter:off
		return Jwts.parser()
				.verifyWith(key)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		//@formatter:on
	}

	public static Claims check(DiaryContext context, String subject, List<UserProperty> userProperties) {

		log.info("Authorization.check");

		String accessToken = null;
		for (UserProperty property : userProperties) {
			if ("accessToken".equals(property.getKey())) {
				accessToken = property.getValue();
				break;
			}
		}
		if (accessToken == null) {
			log.info("Authorization.check: accessToken not found'");
			return null;
		}

		log.info("Authorization.check: before 'parseToken'");
		Claims claims = null;
		try {
			String secret = context.getSecret();
			claims = parseToken(secret, accessToken);
		} catch (ExpiredJwtException e) {
			log.info("Authorization.check: exception thrown'");
			log.catching(e);
			return null;
		}

		String actual = claims.getSubject();
		if (!subject.equals(actual)) {
			log.info(String.format("Authorization.check: unexpected subject: expected: %s, actual: %s", subject, actual));
			return null;
		}

		log.info("Authorization.check: returning claims");
		return claims;
	}

	public static void main(String[] args) throws Exception {

		String secret = "p8l4Qk6gw6QIvNo0uqZNyAsExRPxH7a7fW4Bz0MUk0w=";
		String subject = "access";

		int expirationPeriod = 5;

		String accessToken = getToken(secret, subject, expirationPeriod, ChronoUnit.SECONDS);

		DiaryContext context = new DiaryContext();
		context.setSecret(secret);

		List<UserProperty> userProperties = new ArrayList<UserProperty>();
		userProperties.add(new UserProperty("accessToken", accessToken));

		Claims claims = check(context, subject, userProperties);

		if (claims == null) {
			System.out.println("Unauthorized");
		} else {
			Integer id20 = claims.get("id20", Integer.class);
			System.out.println(String.format("id20 = %d", id20));
			System.out.println("Success");
		}

	}
}
