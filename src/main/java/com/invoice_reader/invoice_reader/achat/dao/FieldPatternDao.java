package com.invoice_reader.invoice_reader.achat.dao;

import com.invoice_reader.invoice_reader.achat.entity.FieldPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FieldPatternDao extends JpaRepository<FieldPattern,Long> {

    List<FieldPattern> findByFieldNameAndActiveOrderByPriority(String fieldName, Boolean active);

    List<FieldPattern> findByActiveOrderByFieldNameAscPriorityAsc(Boolean active);

    Optional<FieldPattern> findFirstByFieldNameAndDescriptionAndActiveOrderByPriorityDesc(
            String fieldName,
            String description,
            Boolean active
    );

}
