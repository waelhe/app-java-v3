package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Agent 1: the page use case's ordering invariants. */
@ExtendWith(MockitoExtension.class)
class CmsPageAdminServiceTest {

    @Mock
    private CmsPageRepository pageRepository;

    @Mock
    private CmsPageTranslationRepository translationRepository;

    @Mock
    private CmsPageTypeRepository pageTypeRepository;

    @Mock
    private CmsPageTypeAttributeRepository attributeRepository;

    @Mock
    private CmsMenuItemRepository itemRepository;

    private CmsPageAdminService service() {
        return new CmsPageAdminService(pageRepository, translationRepository, pageTypeRepository,
                attributeRepository, itemRepository);
    }

    private static Authentication admin() {
        return new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void relativeMoveUsesTheSiblingOrderInsteadOfUuidTieBreaking() {
        UUID pageTypeId = UUID.randomUUID();
        CmsPageType pageType = CmsPageType.create("Landing", "landing");
        CmsPageTypeAttribute first = attribute(pageTypeId, 0, 1);
        CmsPageTypeAttribute second = attribute(pageTypeId, 1, 2);
        CmsPageTypeAttribute third = attribute(pageTypeId, 2, 3);
        List<CmsPageTypeAttribute> pairs = new ArrayList<>(List.of(first, second, third));
        when(pageTypeRepository.findById(pageType.getId())).thenReturn(Optional.of(pageType));
        when(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(pageType.getId()))
                .thenReturn(pairs);

        service().reorderPageTypeAttributes(admin(), pageType.getId(),
                List.of(third.getAttributeId()), java.util.Collections.singletonList(-2));

        assertThat(third.getPosition()).isZero();
        assertThat(first.getPosition()).isEqualTo(1);
        assertThat(second.getPosition()).isEqualTo(2);
    }

    private static CmsPageTypeAttribute attribute(UUID pageTypeId, int position, long idSuffix) {
        CmsPageTypeAttribute pair =
                CmsPageTypeAttribute.create(pageTypeId, UUID.randomUUID(), position);
        ReflectionTestUtils.setField(pair, "id", new UUID(0L, idSuffix));
        return pair;
    }
}
