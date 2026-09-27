package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import com.rsmaxwell.diaries.responder.model.*;

/** Complete writer matrix; unresolved references remain readable for repair. */
class ResolvedFragmentStateTest {
    record Case(FragmentType type, boolean marquee, String reference, boolean valid) { }

    @TestFactory Stream<DynamicTest> writerInvariantMatrix() {
        return Stream.of(
            new Case(FragmentType.MARQUEE, true, "none", true),
            new Case(FragmentType.MARQUEE, false, "none", true),
            new Case(FragmentType.MARQUEE, true, "existing", false),
            new Case(FragmentType.MARQUEE, false, "existing", false),
            new Case(FragmentType.MARQUEE, true, "missing", false),
            new Case(FragmentType.MARQUEE, false, "missing", false),
            new Case(FragmentType.IMAGE, false, "none", true),
            new Case(FragmentType.IMAGE, false, "existing", true),
            new Case(FragmentType.IMAGE, false, "missing", false),
            new Case(FragmentType.IMAGE, true, "none", false),
            new Case(FragmentType.IMAGE, true, "existing", false),
            new Case(FragmentType.IMAGE, true, "missing", false),
            new Case(null, false, "none", false)
        ).map(c -> DynamicTest.dynamicTest(c.toString(), () -> {
            Page page = new Page(); page.setId(22L);
            Fragment fragment = Fragment.builder().id(123L).page(page).type(c.type())
                    .year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Entry").build();
            if (!c.reference().equals("none")) fragment.setPersistedImageId(91L);
            if (c.reference().equals("existing")) fragment.setImage(Image.builder().id(91L).build());
            Marquee marquee = c.marquee() ? Marquee.builder().id(44L).fragment(fragment).page(page)
                    .x(0d).y(0d).width(40d).height(40d).build() : null;
            var state = new ResolvedFragmentState(fragment, marquee);
            // Resolution itself tolerates legacy/incomplete state; only writes reject it.
            assertSame(fragment, state.getFragment()); assertSame(marquee, state.getMarquee());
            if (c.valid()) assertDoesNotThrow(state::validateForWrite);
            else assertThrows(IllegalArgumentException.class, state::validateForWrite);
            assertEquals(c.reference().equals("none") ? null : 91L, fragment.getImageId());
            assertEquals(c.type(), fragment.getType());
        }));
    }
}
