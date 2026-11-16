package com.marketplace.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0011 (plan D-15 — DSA (EU) 2022/2065 Art. 27(1)/(2) + Art. 26(1)(d)):
 * the ranking transparency surface is the machine truth the terms and
 * conditions cite. The view's own laws:
 * <ul>
 *   <li>the closed shape — every string non-blank, no free HTML, no
 *       executable payloads (the ADR-0005 validator's own law);</li>
 *   <li>the promoted tier is the FIRST main parameter and carries its
 *       real-time identification (Art. 26(1)(a)/(d));</li>
 *   <li>the no-profiling statement is present (Art. 26(3) satisfied by
 *       construction — the honest negative);</li>
 *   <li>the recipient's options exist (Art. 27(3)) — the sort whitelist,
 *       the radius, the filters, the labeled tier, the declaration.</li>
 * </ul>
 */
class RankingParametersViewTest {

    @Test
    void theTruthCarriesThePromotedTierFirst_withItsRealTimeIdentification() {
        RankingParametersView view = RankingParametersView.thePlatformTruth();

        assertThat(view.version()).isEqualTo("adr-0011/dsa-27");
        assertThat(view.mainParameters()).isNotEmpty();
        RankingParametersView.ParameterGroup first = view.mainParameters().get(0);
        assertThat(first.surface()).contains("promoted tier");
        assertThat(String.join(" ", first.parameters()))
                .as("Art. 26(1)(a): the labeling law is stated as the row's own field")
                .contains("promoted field")
                .as("Art. 26(1)(d): the main parameters of the presentation are stated")
                .contains("budget");
    }

    @Test
    void theHonestNegative_noRecipientProfileTargeting_isStated() {
        RankingParametersView view = RankingParametersView.thePlatformTruth();

        RankingParametersView.ParameterGroup negatives = view.mainParameters().stream()
                .filter(group -> group.surface().contains("NOT exist"))
                .findFirst().orElseThrow();
        assertThat(String.join(" ", negatives.parameters()))
                .as("Art. 26(3): no profiling-based advertising, no special categories")
                .contains("No recipient-profile targeting")
                .contains("special-category");
    }

    @Test
    void theRecipientHoldsTheOptionsToModifyOrInfluence() {
        RankingParametersView view = RankingParametersView.thePlatformTruth();

        assertThat(view.recipientOptions())
                .as("Art. 27(3): the modify/influence functionality is stated")
                .extracting(RankingParametersView.RecipientOption::option)
                .contains("Choose the ordering", "Set the proximity", "Filter the set",
                        "Discount the promoted tier", "Declare commercial content");
    }

    @Test
    void theRelativeImportanceReasonsAreStatedForEachGroup() {
        // Art. 27(2)(b): "the reasons for the relative importance of those
        // parameters" — every group carries its own reason sentence.
        RankingParametersView view = RankingParametersView.thePlatformTruth();

        assertThat(view.mainParameters())
                .allSatisfy(group -> assertThat(group.relativeImportanceReason()).isNotBlank());
        assertThat(view.mainParameters())
                .allSatisfy(group -> assertThat(group.parameters()).allSatisfy(
                        parameter -> assertThat(parameter).isNotBlank()));
    }
}
