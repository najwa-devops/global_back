package com.invoice_reader.invoice_reader.dto.auth;

import com.invoice_reader.invoice_reader.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserResponse {
    private Long id;
    private String username;
    private UserRole role;
    private String displayName;
    private Boolean active;

    public static UserResponse fromEntity(UserAccount user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .displayName(user.getDisplayName())
                .active(user.getActive())
                .build();
    }
}
