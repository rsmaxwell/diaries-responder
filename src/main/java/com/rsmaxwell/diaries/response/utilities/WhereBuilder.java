package com.rsmaxwell.diaries.response.utilities;

import java.util.ArrayList;
import java.util.List;

public class WhereBuilder {

	private List<String> list = new ArrayList<String>();

	public WhereBuilder add(String field, String value) {
		StringBuffer sb = new StringBuffer();
		sb.append(field);
		sb.append(" = '");
		sb.append(value);
		sb.append("'");
		list.add(sb.toString());
		return this;
	}

	public WhereBuilder add(String field, Number value) {
		StringBuffer sb = new StringBuffer();
		sb.append(field);
		sb.append(" = ");
		sb.append(value);
		list.add(sb.toString());
		return this;
	}

	public String build() {
		StringBuffer sb = new StringBuffer();
		String separator = "";
		for (String item : list) {
			sb.append(separator);
			sb.append(item);
			separator = " and ";
		}
		return sb.toString();
	}
}
