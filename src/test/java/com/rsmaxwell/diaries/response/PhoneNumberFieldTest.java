
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

public class PhoneNumberFieldTest {

	private static class Testcase {
		public String phoneNumber;
		public boolean expectedToBeGood;

		public Testcase(String phoneNumber, boolean expectedToBeGood) {
			this.phoneNumber = phoneNumber;
			this.expectedToBeGood = expectedToBeGood;
		}

		static Testcase good(String number) {
			return new Testcase(number, true);
		}

		static Testcase bad(String number) {
			return new Testcase(number, false);
		}
	}

	// @formatter:off
	static final Testcase[] tests = {

			Testcase.good("+44 (123) 4567890"),
			Testcase.good("+44 (1722) 842753"),
			Testcase.good("(01694) 603845"),
			Testcase.good("01700  592357"),
			Testcase.good("01256 456789"),
			Testcase.good("01228 789015"),
			Testcase.good("+44 141 222-3344"),
			Testcase.good("+44 (7854) 604311"),
			Testcase.good("01261 550125"),
			Testcase.good("(0123) 4567890"),
			Testcase.good("01303 678905"),
			Testcase.good("01872824672"),
			Testcase.good("+44 141 222-3344"),
			Testcase.good("+44 785 604-3111"),
			Testcase.good("+44 785 4604-311"),
			Testcase.good("+44 (1364) 604331"),
			Testcase.good("+44 (1346) 604311"),
			Testcase.good("+44 (785) 4604311"),
			Testcase.good("01427 550125"),
			Testcase.good("(01651) 550125"),
			Testcase.good("01697 550125"),
			Testcase.good("1470 670125"),
			Testcase.good("(1503) 555125"),
			Testcase.good("+33644444444"),
			Testcase.good("+33 6 44 44 44 44"),
			Testcase.good("+33 6 36 85 67 89"),
			Testcase.good("07-23456-7892"),
			Testcase.good("1234567890"),
			Testcase.good("123)4567890"),
			
			Testcase.bad("(1)234567890666"),
			Testcase.bad("1"),
			Testcase.bad("123-4567"),
			Testcase.bad("Hello world"),
    };
	// @formatter:on

	@BeforeAll
	static void overallSetup() {

	}

	@Test
	void testPhoneNumber() throws Exception {

		Map<String, Object> args = new HashMap<String, Object>();

		for (Testcase test : tests) {
			args.put("phone", test.phoneNumber);

			try {
				if (test.expectedToBeGood) {
					assertDoesNotThrow(() -> {
						String phone = new Field("phone", args).phone().toString();
						assertEquals(test.phoneNumber, phone);
					});
				} else {
					assertThrows(BadRequest.class, () -> {
						new Field("phone", args).phone().toString();
					});
				}
			} catch (Throwable e) {
				System.out.println("Failed!");
				System.out.println(String.format("phone:    %s", test.phoneNumber));
				System.out.println(String.format("expected: %s", test.expectedToBeGood ? "good" : "bad"));
				throw e;
			}
		}

		System.out.println("Success");
	}
}
