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

		if (startsWithKeyword(statement.toLowerCase(Locale.ROOT), "with")) {
			return new CteParser(statement).outerOperation();
		}

		return classifyLeadingStatement(statement);
	}

	private static SqlQueryType classifyLeadingStatement(String statement) {
		String normalized = statement.toLowerCase(Locale.ROOT);
		if (startsWithKeyword(normalized, "select")) {
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
			} else if (isMySqlDashCommentStart(remaining, 0)) {
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

	private static boolean isMySqlDashCommentStart(String value, int position) {
		int followingPosition = position + 2;
		if (position < 0 || followingPosition >= value.length() || !value.startsWith("--", position)) {
			return false;
		}

		char following = value.charAt(followingPosition);
		return Character.isWhitespace(following) || Character.isISOControl(following);
	}

	private static final class CteParser {

		private final String sql;
		private final int length;

		private CteParser(String sql) {
			this.sql = sql;
			this.length = sql.length();
		}

		private SqlQueryType outerOperation() {
			int position = skipIgnorable("with".length());
			if (position < 0) {
				return OTHER;
			}

			if (keywordAt(position, "recursive")) {
				position = skipIgnorable(position + "recursive".length());
			}

			while (position >= 0 && position < length) {
				position = skipCteName(position);
				if (position < 0) {
					return OTHER;
				}

				position = skipIgnorable(position);
				if (position >= 0 && position < length && sql.charAt(position) == '(') {
					position = skipBalancedParentheses(position);
					position = skipIgnorable(position);
				}

				if (position < 0 || !keywordAt(position, "as")) {
					return OTHER;
				}

				position = skipIgnorable(position + "as".length());
				if (position < 0) {
					return OTHER;
				}

				if (keywordAt(position, "not")) {
					position = skipIgnorable(position + "not".length());
					if (position < 0 || !keywordAt(position, "materialized")) {
						return OTHER;
					}
					position = skipIgnorable(position + "materialized".length());
				} else if (keywordAt(position, "materialized")) {
					position = skipIgnorable(position + "materialized".length());
				}

				if (position < 0 || position >= length || sql.charAt(position) != '(') {
					return OTHER;
				}

				position = skipBalancedParentheses(position);
				position = skipIgnorable(position);
				if (position < 0 || position >= length) {
					return OTHER;
				}

				if (sql.charAt(position) == ',') {
					position = skipIgnorable(position + 1);
					continue;
				}

				return classifyOuterKeyword(position);
			}

			return OTHER;
		}

		private SqlQueryType classifyOuterKeyword(int position) {
			if (keywordAt(position, "select")) {
				return SELECT;
			}
			if (keywordAt(position, "insert")) {
				return INSERT;
			}
			if (keywordAt(position, "update")) {
				return UPDATE;
			}
			if (keywordAt(position, "delete")) {
				return DELETE;
			}
			return OTHER;
		}

		private int skipCteName(int position) {
			if (position >= length) {
				return -1;
			}

			char first = sql.charAt(position);
			if (first == '`' || first == '"') {
				return skipQuoted(position, first);
			}

			int cursor = position;
			while (cursor < length && isIdentifierPart(sql.charAt(cursor))) {
				cursor++;
			}
			return cursor == position ? -1 : cursor;
		}

		private int skipBalancedParentheses(int position) {
			int depth = 0;
			int cursor = position;
			while (cursor < length) {
				char current = sql.charAt(cursor);
				if (current == '\'' || current == '"' || current == '`') {
					cursor = skipQuoted(cursor, current);
					if (cursor < 0) {
						return -1;
					}
					continue;
				}
				if (startsAt(cursor, "/*")) {
					int commentEnd = sql.indexOf("*/", cursor + 2);
					if (commentEnd < 0) {
						return -1;
					}
					cursor = commentEnd + 2;
					continue;
				}
				if (isMySqlDashCommentStart(sql, cursor) || current == '#') {
					cursor = skipLineComment(cursor);
					continue;
				}
				if (current == '(') {
					depth++;
				} else if (current == ')') {
					depth--;
					if (depth == 0) {
						return cursor + 1;
					}
					if (depth < 0) {
						return -1;
					}
				}
				cursor++;
			}
			return -1;
		}

		private int skipQuoted(int position, char quote) {
			int cursor = position + 1;
			while (cursor < length) {
				char current = sql.charAt(cursor);
				if (current == '\\') {
					cursor += 2;
					continue;
				}
				if (current == quote) {
					if (cursor + 1 < length && sql.charAt(cursor + 1) == quote) {
						cursor += 2;
						continue;
					}
					return cursor + 1;
				}
				cursor++;
			}
			return -1;
		}

		private int skipIgnorable(int position) {
			int cursor = position;
			while (cursor >= 0 && cursor < length) {
				if (Character.isWhitespace(sql.charAt(cursor))) {
					cursor++;
					continue;
				}
				if (startsAt(cursor, "/*")) {
					int commentEnd = sql.indexOf("*/", cursor + 2);
					if (commentEnd < 0) {
						return -1;
					}
					cursor = commentEnd + 2;
					continue;
				}
				if (isMySqlDashCommentStart(sql, cursor) || sql.charAt(cursor) == '#') {
					cursor = skipLineComment(cursor);
					continue;
				}
				break;
			}
			return cursor;
		}

		private int skipLineComment(int position) {
			int lf = sql.indexOf('\n', position);
			int cr = sql.indexOf('\r', position);
			if (lf < 0) {
				return cr < 0 ? length : cr + 1;
			}
			if (cr < 0) {
				return lf + 1;
			}
			return Math.min(lf, cr) + 1;
		}

		private boolean keywordAt(int position, String keyword) {
			if (position < 0 || position + keyword.length() > length) {
				return false;
			}
			if (!sql.regionMatches(true, position, keyword, 0, keyword.length())) {
				return false;
			}
			int end = position + keyword.length();
			return end == length || !isIdentifierPart(sql.charAt(end));
		}

		private boolean startsAt(int position, String value) {
			return position + value.length() <= length && sql.startsWith(value, position);
		}
	}
}
