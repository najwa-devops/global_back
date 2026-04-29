package com.invoice_reader.invoice_reader.dto.auth;

import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateUserRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @NotNull
    private UserRole role;
    private String displayName;
}
