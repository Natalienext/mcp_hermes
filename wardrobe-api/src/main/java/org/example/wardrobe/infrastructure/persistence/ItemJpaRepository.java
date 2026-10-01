package org.example.wardrobe.infrastructure.persistence;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface ItemJpaRepository extends JpaRepository<ItemEntity, Long>, JpaSpecificationExecutor<ItemEntity> {

    @Override
    @EntityGraph(attributePaths = "colors")
    List<ItemEntity> findAll(Specification<ItemEntity> spec, Sort sort);
}
