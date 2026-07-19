package com.example.cachefallback.service;

public class PropertyNotFoundException extends RuntimeException {

  public PropertyNotFoundException(Long id) {
    super("PropertyData not found: " + id);
  }
}
