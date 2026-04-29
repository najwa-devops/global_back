package com.invoice_reader.invoice_reader.repository;

import com.invoice_reader.invoice_reader.entity.dynamic.FieldPattern;
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
