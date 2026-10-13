package com.zenner.datahub.stats.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D-12 P3 (DL-167): the IMSI leaves the API masked, and never in clear. */
class ImsiMaskTest {

    @ParameterizedTest(name = "{0} with plmn {1} -> {2}")
    @CsvSource(nullValues = "NULL", value = {
            // 15 digits, plmn prefixes it: carrier kept, six hidden, last four shown.
            "262011234567890, 26201,  26201******7890",
            // A six-digit plmn (three-digit MNC) is kept whole.
            "310260123456789, 310260, 310260*****6789",
            // plmn does not prefix the IMSI (a roaming SIM): the first five digits stay.
            "001010123456789, 26201,  00101******6789",
            // No plmn at all: the same five-digit default.
            "001010123456789, NULL,   00101******6789",
            // Twenty digits, the schema's maximum.
            "00012345678901234567, 00012, 00012***********4567",
            // Fewer than eight subscriber digits: nothing after the prefix is shown.
            "2620112345, 26201, 26201*****",
            "262011234, 26201, 26201****",
            // Too short to hide anything after the prefix: masked completely.
            "262011, 26201, ******",
            "123, NULL, ***",
    })
    void masksEverythingButTheNetworkPrefixAndAShortTail(String imsi, String plmn, String expected) {
        String masked = ImsiMask.mask(imsi, plmn);

        assertThat(masked).isEqualTo(expected).hasSameSizeAs(imsi);
        assertThat(masked).matches("^[0-9]{0,6}\\*+[0-9]{0,4}$");
    }

    @ParameterizedTest
    @CsvSource({"262011234567890, 26201", "001010123456789, 00101", "00012345678901234567, 00012"})
    void neverReturnsTheSubscriberNumber(String imsi, String plmn) {
        String subscriber = imsi.substring(plmn.length(), imsi.length() - ImsiMask.VISIBLE_TAIL);

        assertThat(ImsiMask.mask(imsi, plmn)).doesNotContain(subscriber).isNotEqualTo(imsi);
    }

    @org.junit.jupiter.api.Test
    void anAbsentImsiStaysAbsent() {
        assertThat(ImsiMask.mask(null, "26201")).isNull();
    }
}
