package com.example.cachefallback.controller;

import com.example.cachefallback.domain.PropertyData;
import com.example.cachefallback.service.PropertyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PropertyController {

  private final PropertyService propertyService;

  public PropertyController(PropertyService propertyService) {
    this.propertyService = propertyService;
  }

  @GetMapping("/properties/{id}")
  public PropertyData getProperty(@PathVariable Long id) {
    return propertyService.getProperty(id);
  }
}
