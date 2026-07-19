package com.example.cachefallback.service;

import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.repository.PropertyRepository;
import org.springframework.stereotype.Service;

@Service
public class PropertyService {

  private final PropertyRepository propertyRepository;

  public PropertyService(PropertyRepository propertyRepository) {
    this.propertyRepository = propertyRepository;
  }

  public PropertyData getProperty(Long id) {
    return propertyRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("PropertyData not found: " + id));
  }
}
