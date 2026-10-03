package com.jpg.apigateway.repository;

import com.jpg.apigateway.domain.SecurityRolePathEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface SecurityRolePathR2dbcRepository extends ReactiveCrudRepository<SecurityRolePathEntity, Long> {

    Flux<SecurityRolePathEntity> findByRoleName(String roleName);
}
