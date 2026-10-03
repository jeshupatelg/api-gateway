package com.jpg.apigateway.repository;

import com.jpg.apigateway.domain.SecurityPublicPathEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

@Repository
public interface SecurityPublicPathR2dbcRepository extends ReactiveCrudRepository<SecurityPublicPathEntity, Long> {

    Mono<Void> deleteByPathPattern(String pathPattern);
}
