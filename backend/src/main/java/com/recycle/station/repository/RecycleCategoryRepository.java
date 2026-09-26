package com.recycle.station.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.recycle.station.domain.RecycleCategory;

public interface RecycleCategoryRepository extends JpaRepository<RecycleCategory, Long> {

    RecycleCategory findByCategoryNo(String categoryNo);

    List<RecycleCategory> findByStoredTrueOrderByCategoryNoAsc();
}
