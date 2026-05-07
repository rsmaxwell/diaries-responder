package com.rsmaxwell.diaries.responder.model;

public enum Role {
	READER(10), EDITOR(20), ADMIN(30);

	private final int level;

	Role(int level) {
		this.level = level;
	}

	public boolean atLeast(Role required) {
		return this.level >= required.level;
	}
}
