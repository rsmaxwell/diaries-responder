package com.rsmaxwell.diaries.responder.utilities;

@FunctionalInterface
public interface TriFunction<A, B, C, R> {
	R apply(A a, B b, C c) throws Exception;
}
