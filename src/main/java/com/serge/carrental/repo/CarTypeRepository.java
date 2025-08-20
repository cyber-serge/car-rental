package com.serge.carrental.repo;

import com.serge.carrental.domain.CarType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarTypeRepository extends JpaRepository<CarType, String> {
}

