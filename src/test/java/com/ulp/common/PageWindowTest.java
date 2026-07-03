package com.ulp.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the shared pager window computation. */
class PageWindowTest {

    @Test
    void singlePage_returnsEmpty() {
        assertThat(PageWindow.of(0, 1)).isEmpty();
        assertThat(PageWindow.of(0, 0)).isEmpty();
    }

    @Test
    void atOrBelowWindowSize_showsEveryPage() {
        // Exactly WINDOW_SIZE (7) pages: no ellipsis, all indices present.
        assertThat(PageWindow.of(3, PageWindow.WINDOW_SIZE))
                .containsExactly(0, 1, 2, 3, 4, 5, 6);
        // A smaller run still lists all pages.
        assertThat(PageWindow.of(0, 3)).containsExactly(0, 1, 2);
    }

    @Test
    void largeSet_currentAtStart_ellipsisOnlyBeforeLast() {
        // current=0 of 20 pages → 0 1 … 19
        assertThat(PageWindow.of(0, 20)).containsExactly(0, 1, -1, 19);
    }

    @Test
    void largeSet_currentInMiddle_ellipsisOnBothSides() {
        // current=10 of 20 pages → 0 … 9 10 11 … 19
        assertThat(PageWindow.of(10, 20)).containsExactly(0, -1, 9, 10, 11, -1, 19);
    }

    @Test
    void largeSet_currentAtEnd_ellipsisOnlyAfterFirst() {
        // current=19 of 20 pages → 0 … 18 19
        assertThat(PageWindow.of(19, 20)).containsExactly(0, -1, 18, 19);
    }

    @Test
    void largeSet_currentNearStart_noLeadingEllipsis() {
        // current=1 of 20 → window touches first page, so no leading -1.
        assertThat(PageWindow.of(1, 20)).containsExactly(0, 1, 2, -1, 19);
    }

    @Test
    void ellipsisMarkerIsMinusOne() {
        // Every collapsed gap is represented by -1 exactly.
        assertThat(PageWindow.of(10, 100)).contains(-1);
        assertThat(PageWindow.of(10, 100).stream().filter(i -> i == -1).count()).isEqualTo(2);
    }

    @Test
    void query_nullBase_yieldsPageOnly() {
        assertThat(PageWindow.query(null, 4)).isEqualTo("page=4");
    }

    @Test
    void query_preservesFiltersInOrderAndEncodesValues() {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("status", "all");
        base.put("q", "a & b");   // space → '+', '&' → '%26'
        base.put("size", 5);

        // Insertion order preserved; page appended last.
        assertThat(PageWindow.query(base, 2))
                .isEqualTo("status=all&q=a+%26+b&size=5&page=2");
    }

    @Test
    void query_skipsNullAndBlankValues() {
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("status", "");   // blank → skipped
        base.put("q", null);      // null → skipped
        base.put("size", 10);

        assertThat(PageWindow.query(base, 1)).isEqualTo("size=10&page=1");
    }

    @Test
    void of_returnedListIsMutableAndIndependent() {
        List<Integer> a = PageWindow.of(10, 20);
        List<Integer> b = PageWindow.of(10, 20);
        a.clear();
        assertThat(b).isNotEmpty(); // separate instances
    }
}
