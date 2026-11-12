package com.payflow.repo;

import com.payflow.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRateRepository extends JpaRepository<FxRate, String> {
}
