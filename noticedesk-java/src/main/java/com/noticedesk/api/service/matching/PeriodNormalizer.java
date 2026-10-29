package com.noticedesk.api.service.matching;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the tax-period text the matcher extracts from a notice into a date range. Used for reply
 * template version selection (the template in force on the period's start date).
 * <p>Accepted forms (case-insensitive):
 * <ul>
 *   <li>financial years: {@code FY 2020-21}, {@code 2020-21}, {@code F.Y. 2020-2021}, {@code 2020-2021}</li>
 *   <li>month ranges: {@code April 2020 to March 2021}, {@code Apr-2020 to Mar-2021}, {@code 04/2020 - 03/2021}</li>
 *   <li>date ranges: {@code 01.04.2020 to 31.03.2021} (also with {@code -} or {@code /})</li>
 *   <li>single months: {@code July 2019}, {@code Jul-19}, {@code 07/2019}, {@code 2019-07}</li>
 * </ul>
 */
public final class PeriodNormalizer {

    /** Inclusive date range of a tax period. */
    public record Period(LocalDate start, LocalDate end) {
        /** True when the period spans more than one financial year (April-March). */
        public boolean spansMultipleFinancialYears() {
            return fyStartYear(start) != fyStartYear(end);
        }
    }

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final String MONTH_NAME = "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?";
    private static final String YEAR = "((?:19|20)\\d{2}|\\d{2})";
    private static final String SEP = "\\s*(?:to|till|until|upto|up to|through|-|–|—)\\s*";

    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-]((?:19|20)\\d{2})");
    private static final Pattern DATE_RANGE = Pattern.compile(DATE.pattern() + SEP + DATE.pattern());
    private static final Pattern MONTH_NAME_YEAR = Pattern.compile(MONTH_NAME + "[\\s,'-]*" + YEAR);
    private static final Pattern MONTH_NAME_RANGE = Pattern.compile(MONTH_NAME_YEAR.pattern() + SEP + MONTH_NAME_YEAR.pattern());
    private static final Pattern NUM_MONTH_YEAR = Pattern.compile("(?<!\\d)(\\d{1,2})[/-]((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern NUM_MONTH_RANGE = Pattern.compile(NUM_MONTH_YEAR.pattern() + SEP + NUM_MONTH_YEAR.pattern());
    private static final Pattern YEAR_MONTH = Pattern.compile("((?:19|20)\\d{2})-(0[1-9]|1[0-2])");
    private static final Pattern FINANCIAL_YEAR = Pattern.compile("((?:19|20)\\d{2})\\s*[-/–]\\s*((?:19|20)?\\d{2})(?!\\d)");
    private static final Pattern FINANCIAL_YEAR_RANGE = Pattern.compile(FINANCIAL_YEAR.pattern() + SEP + FINANCIAL_YEAR.pattern());

    private PeriodNormalizer() {}

    public static Optional<Period> parse(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String s = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        try {
            Matcher m = DATE_RANGE.matcher(s);
            if (m.find()) {
                return range(date(m, 1), date(m, 4));
            }
            m = MONTH_NAME_RANGE.matcher(s);
            if (m.find()) {
                return range(month(m.group(1), m.group(2)).atDay(1), month(m.group(3), m.group(4)).atEndOfMonth());
            }
            m = NUM_MONTH_RANGE.matcher(s);
            if (m.find()) {
                return range(numMonth(m.group(1), m.group(2)).atDay(1), numMonth(m.group(3), m.group(4)).atEndOfMonth());
            }
            m = FINANCIAL_YEAR_RANGE.matcher(s);
            if (m.find()) {
                Optional<Period> first = financialYear(m.group(1), m.group(2));
                Optional<Period> last = financialYear(m.group(3), m.group(4));
                if (first.isPresent() && last.isPresent()) return range(first.get().start(), last.get().end());
            }
            m = FINANCIAL_YEAR.matcher(s);
            if (m.find()) {
                Optional<Period> fy = financialYear(m.group(1), m.group(2));
                if (fy.isPresent()) return fy;
            }
            m = DATE.matcher(s);
            if (m.find()) {
                LocalDate d = date(m, 1);
                return Optional.of(new Period(d, d));
            }
            m = MONTH_NAME_YEAR.matcher(s);
            if (m.find()) {
                YearMonth ym = month(m.group(1), m.group(2));
                return Optional.of(new Period(ym.atDay(1), ym.atEndOfMonth()));
            }
            m = NUM_MONTH_YEAR.matcher(s);
            if (m.find()) {
                YearMonth ym = numMonth(m.group(1), m.group(2));
                return Optional.of(new Period(ym.atDay(1), ym.atEndOfMonth()));
            }
            m = YEAR_MONTH.matcher(s);
            if (m.matches()) {
                YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return Optional.of(new Period(ym.atDay(1), ym.atEndOfMonth()));
            }
        } catch (RuntimeException e) {
            // invalid day/month numbers: treated as not parseable
        }
        return Optional.empty();
    }

    private static Optional<Period> range(LocalDate start, LocalDate end) {
        return end.isBefore(start) ? Optional.empty() : Optional.of(new Period(start, end));
    }

    /** "2020"/"21" or "2020"/"2021" as a financial year; empty unless the second year follows the first. */
    private static Optional<Period> financialYear(String startYear, String endYear) {
        int start = Integer.parseInt(startYear);
        int end = Integer.parseInt(endYear);
        if (end < 100) end += (start / 100) * 100;
        if (end != start + 1) return Optional.empty();
        return Optional.of(new Period(LocalDate.of(start, 4, 1), LocalDate.of(end, 3, 31)));
    }

    private static LocalDate date(Matcher m, int g) {
        return LocalDate.of(Integer.parseInt(m.group(g + 2)), Integer.parseInt(m.group(g + 1)), Integer.parseInt(m.group(g)));
    }

    private static YearMonth month(String name, String year) {
        int y = Integer.parseInt(year);
        if (y < 100) y += 2000;
        return YearMonth.of(y, MONTHS.get(name.substring(0, 3)));
    }

    private static YearMonth numMonth(String month, String year) {
        return YearMonth.of(Integer.parseInt(year), Integer.parseInt(month));
    }

    private static int fyStartYear(LocalDate d) {
        return d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
    }
}
