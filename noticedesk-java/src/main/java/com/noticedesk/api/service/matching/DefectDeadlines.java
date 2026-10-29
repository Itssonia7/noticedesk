package com.noticedesk.api.service.matching;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Statutory dates used by the {@link DefectChecker}, keyed by the start year of the financial year (2019 = FY 2019-20).
 * <p>
 * [VERIFY: partner to confirm the extended dates]
 */
public final class DefectDeadlines {

    private DefectDeadlines() {}

    /**
     * Last date for a Section 73 show cause notice: Section 73(2) read with 73(10), as extended by Notifications
     * 09/2023-CT and 56/2023-CT (three months before the order deadline).
     */
    public static final Map<Integer, LocalDate> SCN_73_DEADLINE = Map.of(
            2017, LocalDate.of(2023, 9, 30),
            2018, LocalDate.of(2024, 1, 31),
            2019, LocalDate.of(2024, 5, 31),
            2020, LocalDate.of(2024, 11, 30),
            2021, LocalDate.of(2025, 9, 30),
            2022, LocalDate.of(2026, 9, 30),
            2023, LocalDate.of(2027, 9, 30));

    /** GSTR-9 annual return due date: a Section 74 SCN dated before it is premature (Section 74(10) runs from it). */
    public static final Map<Integer, LocalDate> GSTR9_DUE_DATE = Map.of(
            2017, LocalDate.of(2020, 2, 7),
            2018, LocalDate.of(2020, 12, 31),
            2019, LocalDate.of(2021, 3, 31),
            2020, LocalDate.of(2022, 2, 28),
            2021, LocalDate.of(2022, 12, 31),
            2022, LocalDate.of(2023, 12, 31),
            2023, LocalDate.of(2024, 12, 31));

    /** DIN on every CBIC communication from this date (CBIC Circular 122/41/2019-GST). */
    public static final LocalDate DIN_MANDATORY_FROM = LocalDate.of(2019, 11, 8);

    /** Financial years whose Section 73 limitation the reply card NDK-105 is written for. */
    public static final Set<Integer> NDK_105_FYS = Set.of(2017, 2018);
}
