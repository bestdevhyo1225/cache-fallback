package com.example.cachefallback.cache;

public sealed interface CacheResult<T> permits CacheResult.Hit, CacheResult.Miss, CacheResult.Error {

  record Hit<T>(T value) implements CacheResult<T> {

  }

  record Miss<T>() implements CacheResult<T> {

  }

  record Error<T>(Throwable cause) implements CacheResult<T> {

  }
}
