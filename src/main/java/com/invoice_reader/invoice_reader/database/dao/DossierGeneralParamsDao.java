package com.invoice_reader.invoice_reader.database.dao;

import com.invoice_reader.invoice_reader.database.entity.auth.DossierGeneralParams;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DossierGeneralParamsDao extends JpaRepository<DossierGeneralParams, Long> {
    Optional<DossierGeneralParams> findByDossierId(Long dossierId);
}

