package com.marketplace.identity;

import org.mapstruct.Mapper;

/**
 * I7 note: the neutral "former member" rendering for pseudonymized accounts
 * (plan §5-أ step 3) lives at the response-DTO level only — storage stays
 * NULL. The default method (consumed by MapStruct as-is, the documented
 * behavior for default methods in mapper interfaces) keeps the explicit
 * branch visible instead of hiding it in an expression.
 */
@Mapper(componentModel = "spring")
public interface UserMapper {

    default UserResponse toResponse(User user) {
        if (user.getPseudonymizedAt() != null) {
            return new UserResponse(user.getId(), null,
                    UserService.FORMER_MEMBER_LABEL, user.getCreatedAt(), user.getUpdatedAt());
        }
        return new UserResponse(user.getId(), user.getEmail(),
                user.getDisplayName(), user.getCreatedAt(), user.getUpdatedAt());
    }
}
