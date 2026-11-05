// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.bizapp.service;

import com.openvie.api.chat.query.BizAppIntentRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Comprehensive Natural Date and Time Resolution Test")
class NaturalDateTimeResolverTest {

    @Test
    void testDayOfWeekVietnameseNextWeek() {
        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();
        LocalDate mondayNextWeek = today.minusDays(dow - 1).plusDays(7);

        // Thứ 2 tuần sau -> Monday
        assertEquals(mondayNextWeek, BizAppPayloadValidator.resolveNaturalDate("thứ 2 tuần sau"));
        assertEquals(mondayNextWeek.toString(), BizAppIntentRouter.resolveDateString("thứ 2 tuần sau"));

        // Thứ 3 tuần sau -> Tuesday (Monday + 1)
        assertEquals(mondayNextWeek.plusDays(1), BizAppPayloadValidator.resolveNaturalDate("thứ 3 tuần sau"));
        assertEquals(mondayNextWeek.plusDays(1).toString(), BizAppIntentRouter.resolveDateString("thứ 3 tuần sau"));

        // Thứ 4 tuần sau -> Wednesday (Monday + 2)
        assertEquals(mondayNextWeek.plusDays(2), BizAppPayloadValidator.resolveNaturalDate("thứ 4 tuần sau"));

        // Thứ 5 tuần sau -> Thursday (Monday + 3)
        assertEquals(mondayNextWeek.plusDays(3), BizAppPayloadValidator.resolveNaturalDate("thứ 5 tuần sau"));

        // Thứ 6 tuần sau -> Friday (Monday + 4)
        assertEquals(mondayNextWeek.plusDays(4), BizAppPayloadValidator.resolveNaturalDate("thứ 6 tuần sau"));

        // Thứ 7 tuần sau / Thứ bảy tuần tới -> Saturday (Monday + 5)
        assertEquals(mondayNextWeek.plusDays(5), BizAppPayloadValidator.resolveNaturalDate("thứ bảy tuần tới"));

        // Chủ nhật tuần sau -> Sunday (Monday + 6)
        assertEquals(mondayNextWeek.plusDays(6), BizAppPayloadValidator.resolveNaturalDate("chủ nhật tuần sau"));
        assertEquals(mondayNextWeek.plusDays(6).toString(), BizAppIntentRouter.resolveDateString("chủ nhật tuần sau"));
    }

    @Test
    void testDayOfWeekEnglishNextWeek() {
        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();
        LocalDate mondayNextWeek = today.minusDays(dow - 1).plusDays(7);

        assertEquals(mondayNextWeek, BizAppPayloadValidator.resolveNaturalDate("next Monday"));
        assertEquals(mondayNextWeek.plusDays(1), BizAppPayloadValidator.resolveNaturalDate("next Tuesday"));
        assertEquals(mondayNextWeek.plusDays(1).toString(), BizAppIntentRouter.resolveDateString("next Tuesday"));
        assertEquals(mondayNextWeek.plusDays(2), BizAppPayloadValidator.resolveNaturalDate("next Wednesday"));
        assertEquals(mondayNextWeek.plusDays(3), BizAppPayloadValidator.resolveNaturalDate("next Thursday"));
        assertEquals(mondayNextWeek.plusDays(4), BizAppPayloadValidator.resolveNaturalDate("next Friday"));
        assertEquals(mondayNextWeek.plusDays(5), BizAppPayloadValidator.resolveNaturalDate("next Saturday"));
        assertEquals(mondayNextWeek.plusDays(6), BizAppPayloadValidator.resolveNaturalDate("next Sunday"));
        assertEquals(mondayNextWeek.plusDays(6).toString(), BizAppIntentRouter.resolveDateString("next Sunday"));
    }

    @Test
    void testWeekends() {
        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();
        LocalDate mondayThisWeek = today.minusDays(dow - 1);
        LocalDate saturdayThisWeek = mondayThisWeek.plusDays(5);
        LocalDate saturdayNextWeek = mondayThisWeek.plusDays(12);

        assertEquals(saturdayThisWeek, BizAppPayloadValidator.resolveNaturalDate("cuối tuần này"));
        assertEquals(saturdayThisWeek.toString(), BizAppIntentRouter.resolveDateString("cuối tuần này"));
        assertEquals(saturdayThisWeek, BizAppPayloadValidator.resolveNaturalDate("this weekend"));

        assertEquals(saturdayNextWeek, BizAppPayloadValidator.resolveNaturalDate("cuối tuần sau"));
        assertEquals(saturdayNextWeek.toString(), BizAppIntentRouter.resolveDateString("cuối tuần sau"));
        assertEquals(saturdayNextWeek, BizAppPayloadValidator.resolveNaturalDate("next weekend"));
    }

    @Test
    void testRelativeDays() {
        LocalDate today = LocalDate.now();
        assertEquals(today, BizAppPayloadValidator.resolveNaturalDate("hôm nay"));
        assertEquals(today, BizAppPayloadValidator.resolveNaturalDate("chiều nay"));
        assertEquals(today, BizAppPayloadValidator.resolveNaturalDate("today"));

        assertEquals(today.plusDays(1), BizAppPayloadValidator.resolveNaturalDate("ngày mai"));
        assertEquals(today.plusDays(1), BizAppPayloadValidator.resolveNaturalDate("tomorrow"));

        assertEquals(today.plusDays(2), BizAppPayloadValidator.resolveNaturalDate("ngày kia"));
        assertEquals(today.plusDays(2), BizAppPayloadValidator.resolveNaturalDate("mốt"));

        assertEquals(today.minusDays(1), BizAppPayloadValidator.resolveNaturalDate("hôm qua"));
        assertEquals(today.minusDays(1), BizAppPayloadValidator.resolveNaturalDate("yesterday"));
    }

    @Test
    void testNaturalTimeResolution() {
        assertEquals("17:20", BizAppIntentRouter.resolveTimeString("17h20"));
        assertEquals("14:00", BizAppIntentRouter.resolveTimeString("14h"));
        assertEquals("09:30", BizAppIntentRouter.resolveTimeString("9h30"));
        assertEquals("14:00", BizAppIntentRouter.resolveTimeString("2pm"));
        assertEquals("09:00", BizAppIntentRouter.resolveTimeString("9am"));
        assertEquals("15:00", BizAppIntentRouter.resolveTimeString("3h chiều"));
        assertEquals("20:00", BizAppIntentRouter.resolveTimeString("8h tối"));
        assertEquals("08:30", BizAppIntentRouter.resolveTimeString("8h30 sáng"));
        assertEquals("14:15", BizAppIntentRouter.resolveTimeString("14:15"));
    }
}
