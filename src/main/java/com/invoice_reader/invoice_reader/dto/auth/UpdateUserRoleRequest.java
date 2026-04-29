package com.invoice_reader.invoice_reader.dto.auth;

import com.invoice_reader.invoice_reader.entity.auth.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    @NotNull
    private UserRole role;
}
