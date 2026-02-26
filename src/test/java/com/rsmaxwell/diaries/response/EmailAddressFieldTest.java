
package com.rsmaxwell.diaries.response;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.responder.utilities.Field;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;

public class EmailAddressFieldTest {

	private static class Testcase {
		public String emailAddress;
		public boolean expectedToBeGood;

		static Testcase good(String email) {
			return new Testcase(email, true);
		}

		static Testcase bad(String email) {
			return new Testcase(email, false);
		}

		public Testcase(String emailAddress, boolean expectedToBeGood) {
			this.emailAddress = emailAddress;
			this.expectedToBeGood = expectedToBeGood;
		}
	}

	// @formatter:off
	static final Testcase[] tests = {
			
			Testcase.good("email@example.com"),
			Testcase.good("firstname.lastname@example.com"),
			Testcase.good("email@subdomain.example.com"),
			Testcase.good("firstname+lastname@example.com"),
			Testcase.good("“email”@example.com"),
			Testcase.good("1234567890@example.com"),
			Testcase.good("email@example-one.com"),
			Testcase.good("_______@example.com"),
			Testcase.good("email@example.name"),
			Testcase.good("email@example.museum"),
			Testcase.good("email@example.co.jp"),
			
			Testcase.bad("firstname-lastname@example.com	"),		
			Testcase.bad("email@123.123.123.123"),
			Testcase.bad("plainaddress"),
			Testcase.bad("#@%^%#$@#$@#.com"),
			Testcase.bad("@example.com"),
			Testcase.bad("Joe Smith <email@example.com>"),
			Testcase.bad("email.example.com"),
			Testcase.bad("email@example@example.com"),
			Testcase.bad(".email@example.com"),
			Testcase.bad("email.@example.com"),
			Testcase.bad("email..email@example.com"),
			Testcase.bad("あいうえお@example.com"),
			Testcase.bad("email@example.com (Joe Smith)"),
			Testcase.bad("email@example"),
			Testcase.bad("email@-example.com"),
			Testcase.bad("email@example.web"),
			Testcase.bad("email@111.222.333.44444"),
			Testcase.bad("email@example..com"),
			Testcase.bad("Abc..123@example.com"),			
    };
	// @formatter:on

	@BeforeAll
	static void overallSetup() {

	}

	@Test
	void testEmailAddress() throws Exception {

		Map<String, Object> args = new HashMap<String, Object>();

		for (Testcase test : tests) {
			args.put("email", test.emailAddress);

			try {
				if (test.expectedToBeGood) {
					assertDoesNotThrow(() -> {
						String email = new Field("email", args).email().toString();
						assertEquals(test.emailAddress, email);
					});
				} else {
					assertThrows(BadRequest.class, () -> {
						new Field("phone", args).email().toString();
					});
				}
			} catch (Throwable e) {
				System.out.println("Failed!");
				System.out.println(String.format("email :   %s", test.emailAddress));
				System.out.println(String.format("expected: %s", test.expectedToBeGood ? "good" : "bad"));
				throw e;
			}
		}

		System.out.println("Success");
	}
}
