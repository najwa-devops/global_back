package com.invoice_reader.invoice_reader.auth.dto;

import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    @NotNull
    private UserRole role;
}
