package com.rsmaxwell.diaries.response.utilities;

import java.util.ArrayList;
import java.util.List;

public final class SqlBuilder {
	private final List<String> select = new ArrayList<>();
	private String from;
	private final List<String> joins = new ArrayList<>();
	private final List<String> where = new ArrayList<>();
	private String orderBy;

	public static SqlBuilder create() {
		return new SqlBuilder();
	}

	public SqlBuilder select(String columns) {
		select.add(columns);
		return this;
	}

	public SqlBuilder selectWithAlias(String column, String alias) {
		select.add(column + " AS " + alias);
		return this;
	}

	public SqlBuilder from(String table) {
		this.from = table;
		return this;
	}

	public SqlBuilder join(String table, String condition) {
		joins.add("JOIN " + table + " ON " + condition);
		return this;
	}

	public JoinBuilder innerJoin(String tableAlias) {
		return new JoinBuilder(this, "JOIN ", tableAlias);
	}

	public JoinBuilder leftJoin(String tableAlias) {
		return new JoinBuilder(this, "LEFT JOIN ", tableAlias);
	}

	public SqlBuilder where(String condition) {
		where.add(condition);
		return this;
	}

	public SqlBuilder whereIsNull(String column) {
		where.add(column + " IS NULL");
		return this;
	}

	public SqlBuilder whereEquals(String column, String valueExpression) {
		where.add(column + " = " + valueExpression);
		return this;
	}

	/** Convenience for matching a fragment date assuming alias f */
	public SqlBuilder whereFragmentDate(int year, int month, int day) {
		where.add("f.year = " + year);
		where.add("f.month = " + month);
		where.add("f.day = " + day);
		return this;
	}

	public SqlBuilder orderBy(String clause) {
		this.orderBy = clause;
		return this;
	}

	public String build() {
		if (select.isEmpty()) {
			throw new IllegalStateException("No SELECT clause specified");
		}
		if (from == null) {
			throw new IllegalStateException("No FROM clause specified");
		}

		StringBuilder sb = new StringBuilder();
		sb.append("SELECT ").append(String.join(", ", select)).append(" ");
		sb.append("FROM ").append(from).append(" ");

		for (String j : joins) {
			sb.append(j).append(" ");
		}

		if (!where.isEmpty()) {
			sb.append("WHERE ").append(String.join(" AND ", where)).append(" ");
		}

		if (orderBy != null) {
			sb.append("ORDER BY ").append(orderBy);
		}

		return sb.toString().trim();
	}

	// Internal helper used by JoinBuilder
	void addJoin(String joinClause) {
		joins.add(joinClause);
	}

	public static class JoinBuilder {
		private final SqlBuilder parent;
		private final String prefix;
		private final String table;

		JoinBuilder(SqlBuilder parent, String prefix, String table) {
			this.parent = parent;
			this.prefix = prefix;
			this.table = table;
		}

		public SqlBuilder on(String condition) {
			parent.addJoin(prefix + table + " ON " + condition);
			return parent;
		}
	}
}
