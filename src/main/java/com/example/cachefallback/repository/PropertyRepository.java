package com.example.cachefallback.repository;

import com.example.cachefallback.domain.PropertyData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyRepository extends JpaRepository<PropertyData, Long> {

}
