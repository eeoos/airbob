package kr.kro.airbob.common.monitoring;

import java.util.Locale;

public enum SqlQueryType {
	SELECT,
	INSERT,
	UPDATE,
	DELETE,
	OTHER,
	TOTAL;

	public static SqlQueryType from(String sql) {
		String statement = stripLeadingComments(sql);
		if (statement.isBlank()) {
			return OTHER;
		}

		String normalized = statement.toLowerCase(Locale.ROOT);
		if (startsWithKeyword(normalized, "select") || startsWithKeyword(normalized, "with")) {
			return SELECT;
		}
		if (startsWithKeyword(normalized, "insert")) {
			return INSERT;
		}
		if (startsWithKeyword(normalized, "update")) {
			return UPDATE;
		}
		if (startsWithKeyword(normalized, "delete")) {
			return DELETE;
		}
		return OTHER;
	}

	private static String stripLeadingComments(String sql) {
		if (sql == null) {
			return "";
		}

		String remaining = sql.stripLeading();
		boolean stripped;
		do {
			stripped = false;
			if (remaining.startsWith("/*")) {
				int endIndex = remaining.indexOf("*/", 2);
				if (endIndex < 0) {
					return "";
				}
				remaining = remaining.substring(endIndex + 2).stripLeading();
				stripped = true;
			} else if (remaining.startsWith("--")) {
				int endIndex = lineEndIndex(remaining);
				remaining = endIndex < 0 ? "" : remaining.substring(endIndex + 1).stripLeading();
				stripped = true;
			}
		} while (stripped);

		return remaining;
	}

	private static int lineEndIndex(String value) {
		int lf = value.indexOf('\n');
		int cr = value.indexOf('\r');
		if (lf < 0) {
			return cr;
		}
		if (cr < 0) {
			return lf;
		}
		return Math.min(lf, cr);
	}

	private static boolean startsWithKeyword(String value, String keyword) {
		if (!value.startsWith(keyword)) {
			return false;
		}
		if (value.length() == keyword.length()) {
			return true;
		}
		return !isIdentifierPart(value.charAt(keyword.length()));
	}

	private static boolean isIdentifierPart(char value) {
		return Character.isLetterOrDigit(value) || value == '_' || value == '$';
	}
}
